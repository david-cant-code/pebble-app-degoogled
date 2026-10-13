// JNI shim between com.anopticlabs.gravel.llm (Kotlin) and the statically
// linked llama.cpp engine. This file is the entire native boundary for the
// text model; it stays small so it can be audited in one sitting.
//
// Contract the Kotlin side relies on:
//  - Text crosses the boundary in both directions as jbyteArray holding
//    UTF-8; no JNI string function is used (model output can be bytes
//    that are not valid modified UTF-8, and a prompt must reach the
//    tokenizer as standard UTF-8).
//  - nativeGenerate and nativeFormatChat return null on failure and record
//    a reason for nativeGetLastError, kept per thread; an empty array is a
//    valid result.
//  - nativeInitFd takes the model as an open file descriptor that the
//    Kotlin side has detached from its owner; the shim owns it from that
//    call on and closes it on every path. The engine never opens a path.
//  - Cancellation targets a call by its caller-supplied id, checked
//    inside every decode through the engine's abort callback and between
//    tokens; a cancel for an id with no call in flight is kept for that
//    id's call, for the most recent kMaxEarlyCancels such ids.

#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <climits>
#include <cstdio>
#include <cstring>
#include <deque>
#include <exception>
#include <memory>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

#include "ggml-cpu.h"
#include "llama.h"

namespace {

constexpr const char *kLogTag = "llm_jni";

// Fallbacks for a non-positive count, for a direct native caller only; the
// Kotlin side always sends positive values.
constexpr int kDefaultThreads = 4;
constexpr int kDefaultMaxTokens = 512;
constexpr int kDefaultCtx = 4096;

// Bounds of this shim: the thread count, and the output in bytes as well
// as in tokens because a token piece can be long. The byte cap is the
// value the Kotlin side accepts (LlmEngineLimits.MAX_TEXT_BYTES, pinned by
// LlmShimConstantsTest); the service checks it again.
constexpr int kMaxThreads = 16;
constexpr size_t kMaxOutputBytes = 256u * 1024u;

// Slots of the caller's stats array, filled on every exit path once known.
constexpr int kStatPromptTokens = 0;
constexpr int kStatGeneratedTokens = 1;
constexpr int kStatPrefillMicros = 2;
constexpr int kStatDecodeMicros = 3;
constexpr int kStatStopReason = 4;

// Stop reasons reported in the stats; the Kotlin side reads the same values.
constexpr int64_t kStopEndOfGeneration = 0;
constexpr int64_t kStopTokenCap = 1;
constexpr int64_t kStopContextFull = 2;
constexpr int64_t kStopByteCap = 3;

// The calls in flight, by id, each with its abort flag, and the ids
// cancelled before their call registered, oldest first. A call registers
// itself on entry, taking its early cancel, and removes itself on every
// exit, so a cancel that races its call's start still stops it and the
// early set holds at most kMaxEarlyCancels ids however many never run.
constexpr size_t kMaxEarlyCancels = 64;
std::mutex g_calls_mutex;
std::unordered_map<int64_t, bool> g_calls;
std::deque<int64_t> g_early_cancels;

// Registers a call for its lifetime; false when the id is already in flight.
bool register_call(int64_t call_id) {
    std::lock_guard<std::mutex> lock(g_calls_mutex);
    if (g_calls.count(call_id) != 0) return false;
    const auto early = std::find(g_early_cancels.begin(), g_early_cancels.end(), call_id);
    const bool cancelled = early != g_early_cancels.end();
    if (cancelled) g_early_cancels.erase(early);
    g_calls.emplace(call_id, cancelled);
    return true;
}

void unregister_call(int64_t call_id) {
    std::lock_guard<std::mutex> lock(g_calls_mutex);
    g_calls.erase(call_id);
}

bool is_call_cancelled(int64_t call_id) {
    std::lock_guard<std::mutex> lock(g_calls_mutex);
    const auto it = g_calls.find(call_id);
    return it != g_calls.end() && it->second;
}

void request_call_cancel(int64_t call_id) {
    std::lock_guard<std::mutex> lock(g_calls_mutex);
    const auto it = g_calls.find(call_id);
    if (it != g_calls.end()) {
        it->second = true;
        return;
    }
    if (std::find(g_early_cancels.begin(), g_early_cancels.end(), call_id) != g_early_cancels.end()) return;
    g_early_cancels.push_back(call_id);
    if (g_early_cancels.size() > kMaxEarlyCancels) g_early_cancels.pop_front();
}

// Per thread: the service reads it on the thread that made the failed
// call, and calls on different handles run on different binder threads.
thread_local std::string t_last_error;

void set_last_error(const std::string &msg) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", msg.c_str());
    t_last_error = msg;
}

// llama/ggml report every failure detail through their log callback and
// Android drops stderr, so without this bridge a failed load is a bare null
// handle with the reason lost. Installed once per process.
void log_to_android(ggml_log_level level, const char *text, void * /*user*/) {
    if (text == nullptr || *text == '\0') return;
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default: break;
    }
    __android_log_write(prio, "llama.cpp", text);
}

std::once_flag g_backend_once;

void init_backend() {
    std::call_once(g_backend_once, [] {
        llama_log_set(log_to_android, nullptr);
        ggml_log_set(log_to_android, nullptr);
        llama_backend_init();
    });
}

// Scheduling placement of the calling thread for the duration of one
// engine call, restored on every exit path by the destructor. A thread
// created inside the scope inherits the calling thread's affinity mask
// and its nice value, except that a thread marked reset-on-fork passes on
// no negative one (kernel/common android16-6.12, kernel/fork.c,
// dup_task_struct, and kernel/sched/core.c, sched_fork; bionic
// android16-release, libc/bionic/pthread_create.cpp, __init_thread). A
// zero mask or nice leaves that dimension alone; a refused syscall is
// logged and ignored, since a decode on the default placement beats no
// decode.
class ScopedPlacement {
public:
    ScopedPlacement(int64_t mask_bits, int nice_value) {
        if (mask_bits != 0) {
            cpu_set_t want;
            CPU_ZERO(&want);
            for (int cpu = 0; cpu < 64 && cpu < CPU_SETSIZE; ++cpu) {
                if (mask_bits & (int64_t(1) << cpu)) CPU_SET(cpu, &want);
            }
            if (sched_getaffinity(0, sizeof(saved_mask_), &saved_mask_) == 0 &&
                sched_setaffinity(0, sizeof(want), &want) == 0) {
                applied_mask_ = mask_bits;
            } else {
                __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                    "affinity mask 0x%llx not applied: %s",
                                    (unsigned long long) mask_bits, strerror(errno));
            }
        }
        if (nice_value != 0) {
            errno = 0;
            const int current = getpriority(PRIO_PROCESS, gettid());
            if (errno == 0 && setpriority(PRIO_PROCESS, gettid(), nice_value) == 0) {
                saved_nice_ = current;
                applied_nice_ = nice_value;
            } else {
                __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                    "nice %d not applied: %s", nice_value, strerror(errno));
            }
        }
    }

    ~ScopedPlacement() {
        if (applied_mask_ != 0) sched_setaffinity(0, sizeof(saved_mask_), &saved_mask_);
        if (applied_nice_ != 0) setpriority(PRIO_PROCESS, gettid(), saved_nice_);
    }

    // Zero for a dimension left alone or refused.
    int64_t applied_mask() const { return applied_mask_; }
    int applied_nice() const { return applied_nice_; }

    ScopedPlacement(const ScopedPlacement &) = delete;
    ScopedPlacement &operator=(const ScopedPlacement &) = delete;

private:
    cpu_set_t saved_mask_{};
    int64_t applied_mask_ = 0;
    int saved_nice_ = 0;
    int applied_nice_ = 0;
};

// A ggml threadpool and the placement accepted for the call that created it.
struct Pool {
    int n_threads;
    int64_t cpu_mask;
    int nice_value;
    ggml_threadpool *pool;
};

// One loaded model with its context. The context is sized at init and
// reused across generations; every generation clears the memory first.
// The pools are freed only after the context: its CPU backend keeps the
// address of the last pool it computed on and locks that pool when it is
// given another (llama.cpp v0.4.1, ggml/src/ggml-cpu/ggml-cpu.cpp,
// ggml_backend_cpu_set_threadpool), and detaching a pool from the context
// leaves that address in place (src/llama-context.cpp,
// llama_context::detach_threadpool). Nothing caps their number: each thread
// count and placement a handle's calls bring adds one.
struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::vector<Pool> pools;
};

// Attaches the handle's threadpool for this thread count and placement,
// creating it on first use: without a pool the CPU backend creates and
// joins its workers for every graph, which is once per generated token
// (llama.cpp v0.4.1, ggml/src/ggml-cpu/ggml-cpu.c, ggml_graph_compute, the
// disposable threadpool branch). Called inside the placement's scope; ggml
// sets no mask or priority of its own on the workers of a pool made from
// default parameters (same file, ggml_graph_compute_secondary_thread and
// ggml_thread_apply_priority).
void attach_threadpool(Engine *engine, int n_threads, int64_t cpu_mask, int nice_value) {
    auto found = std::find_if(engine->pools.begin(), engine->pools.end(), [&](const Pool &p) {
        return p.n_threads == n_threads && p.cpu_mask == cpu_mask && p.nice_value == nice_value;
    });
    if (found == engine->pools.end()) {
        // Appended first: a failed append after it would orphan the pool.
        engine->pools.push_back({n_threads, cpu_mask, nice_value, nullptr});
        found = engine->pools.end() - 1;
        ggml_threadpool_params params = ggml_threadpool_params_default(n_threads);
        found->pool = ggml_threadpool_new(&params);
    }
    llama_set_n_threads(engine->ctx, n_threads, n_threads);
    llama_attach_threadpool(engine->ctx, found->pool, found->pool);
}

// UTF-8 bytes out, exactly as produced; the Kotlin side decodes. Returns
// null only on allocation failure (a pending OutOfMemoryError).
jbyteArray utf8_bytes(JNIEnv *env, const std::string &s) {
    jbyteArray arr = env->NewByteArray(static_cast<jsize>(s.size()));
    if (arr == nullptr) {
        return nullptr;
    }
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(s.size()),
                            reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

// The bytes of a Kotlin ByteArray, copied; false with a cleared exception
// when the array is null or cannot be read.
bool bytes_in(JNIEnv *env, jbyteArray array, std::string &out) {
    if (array == nullptr) return false;
    const jsize n = env->GetArrayLength(array);
    out.resize(static_cast<size_t>(n));
    if (n > 0) {
        env->GetByteArrayRegion(array, 0, n, reinterpret_cast<jbyte *>(&out[0]));
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return false;
        }
    }
    return true;
}

// One token's piece. The stack buffer covers nearly every piece; for a
// longer one the engine returns the negative required size and the piece
// is fetched into an exactly sized buffer.
std::string token_piece(const llama_vocab *vocab, llama_token id) {
    char piece[256];
    int n = llama_token_to_piece(vocab, id, piece, sizeof(piece), 0, /*special=*/true);
    if (n >= 0) return std::string(piece, static_cast<size_t>(n));
    std::vector<char> big(static_cast<size_t>(-n));
    n = llama_token_to_piece(vocab, id, big.data(), static_cast<int>(big.size()), 0, /*special=*/true);
    return n > 0 ? std::string(big.data(), static_cast<size_t>(n)) : std::string();
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_anopticlabs_gravel_llm_LlmJNI_nativeInitFd(JNIEnv *, jclass, jint fd, jint n_ctx) {
    init_backend();

    if (fd < 0) {
        set_last_error("init called with an invalid model fd " + std::to_string(fd));
        return 0;
    }
    // The stream owns the descriptor and closes it when this function
    // returns, on every path.
    std::unique_ptr<FILE, int (*)(FILE *)> file(fdopen(fd, "rb"), fclose);
    if (!file) {
        set_last_error("fdopen failed for model fd " + std::to_string(fd) + ": " + strerror(errno));
        close(fd); // fdopen adopts the fd only on success
        return 0;
    }

    llama_model_params mparams = llama_model_default_params();
    // CPU only: no GPU backend is compiled in. The plain load mode streams
    // every tensor into its buffer through the descriptor with reads, the
    // one file access an isolated process is granted on a descriptor it was
    // handed, and the lazy mode is off because it needs a mapping; the
    // loader does not take ownership of the stream (llama.cpp v0.4.1,
    // src/llama-mmap.cpp, llama_file::impl(FILE *), owns_fp false).
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_NONE;
    mparams.lazy_mode = LLAMA_LAZY_MODE_OFF;

    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    try {
        model = llama_model_load_from_file_ptr(file.get(), mparams);
        if (model == nullptr) {
            set_last_error("llama_model_load_from_file_ptr failed for model fd " + std::to_string(fd)
                           + " (see llama.cpp logcat lines for the engine's reason)");
            return 0;
        }

        llama_context_params cparams = llama_context_default_params();
        cparams.n_ctx = n_ctx > 0 ? static_cast<uint32_t>(n_ctx) : kDefaultCtx;
        // A generation submits its whole prompt as one batch, and the
        // engine aborts the process on a batch larger than n_batch
        // (llama.cpp v0.4.1, src/llama-context.cpp, llama_context::decode,
        // the GGML_ASSERT on n_tokens_all). The engine clamps the batch to
        // the context as requested and then pads the context to 256 (same
        // file, the llama_context constructor), so the batch, not the
        // padded context, bounds a prompt; the physical micro-batch stays
        // at the engine's default.
        cparams.n_batch = cparams.n_ctx;
        cparams.n_threads = kDefaultThreads;
        cparams.n_threads_batch = kDefaultThreads;
        cparams.no_perf = true;
        ctx = llama_init_from_model(model, cparams);
        if (ctx == nullptr) {
            set_last_error("llama_init_from_model failed for a context of " + std::to_string(cparams.n_ctx) + " tokens");
            llama_model_free(model);
            return 0;
        }
    } catch (const std::exception &e) {
        set_last_error(std::string("model load threw: ") + e.what());
        if (ctx != nullptr) llama_free(ctx);
        if (model != nullptr) llama_model_free(model);
        return 0;
    }
    return reinterpret_cast<jlong>(new Engine{model, ctx});
}

// Renders one conversation with the engine's built-in formatter for the
// template family it detects on the model's template text, the assistant
// turn opened (llama.cpp v0.4.1, src/llama-chat.cpp,
// llm_chat_detect_template: a substring match over the Jinja source).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_anopticlabs_gravel_llm_LlmJNI_nativeFormatChat(JNIEnv *env, jclass, jlong handle,
                                                        jobjectArray roles, jobjectArray contents) try {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) {
        set_last_error("formatChat called with a null engine handle");
        return nullptr;
    }
    if (roles == nullptr || contents == nullptr) {
        set_last_error("formatChat called with a null message array");
        return nullptr;
    }
    const jsize n = env->GetArrayLength(roles);
    if (n <= 0 || env->GetArrayLength(contents) != n) {
        set_last_error("formatChat called with " + std::to_string(n) + " roles and "
                       + std::to_string(env->GetArrayLength(contents)) + " contents");
        return nullptr;
    }

    // Owned copies, so the message pointers stay valid across the call.
    std::vector<std::string> role_text(static_cast<size_t>(n)), content_text(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, i));
        const bool ok = bytes_in(env, role, role_text[i]) && bytes_in(env, content, content_text[i]);
        if (role != nullptr) env->DeleteLocalRef(role);
        if (content != nullptr) env->DeleteLocalRef(content);
        if (!ok) {
            set_last_error("formatChat could not read message " + std::to_string(i));
            return nullptr;
        }
    }
    // Content containing the text of a special token would tokenize as
    // that token (llama.cpp v0.4.1, src/llama-vocab.cpp,
    // tokenizer_st_partition, with parse_special) and could open a turn
    // of its own; the render refuses it before the formatter sees it.
    const llama_vocab *vocab = llama_model_get_vocab(engine->model);
    const int32_t n_vocab = llama_vocab_n_tokens(vocab);
    for (llama_token id = 0; id < n_vocab; ++id) {
        const llama_token_attr attr = llama_vocab_get_attr(vocab, id);
        if ((attr & (LLAMA_TOKEN_ATTR_CONTROL | LLAMA_TOKEN_ATTR_USER_DEFINED)) == 0) continue;
        const char *text = llama_vocab_get_text(vocab, id);
        if (text == nullptr || *text == '\0') continue;
        for (jsize i = 0; i < n; ++i) {
            if (content_text[i].find(text) != std::string::npos) {
                set_last_error("message " + std::to_string(i) + " contains the control token text " + text);
                return nullptr;
            }
        }
    }
    std::vector<llama_chat_message> messages(static_cast<size_t>(n));
    for (jsize i = 0; i < n; ++i) {
        messages[i].role = role_text[i].c_str();
        messages[i].content = content_text[i].c_str();
    }

    const char *tmpl = llama_model_chat_template(engine->model, /*name=*/nullptr);
    if (tmpl == nullptr) {
        set_last_error("the model carries no chat template");
        return nullptr;
    }
    // The first call sizes the output; negative means no family detected.
    const int32_t needed = llama_chat_apply_template(tmpl, messages.data(), messages.size(), /*add_ass=*/true, nullptr, 0);
    if (needed < 0) {
        set_last_error("the model's chat template is not one the engine can render");
        return nullptr;
    }
    std::string formatted(static_cast<size_t>(needed), '\0');
    const int32_t wrote = llama_chat_apply_template(tmpl, messages.data(), messages.size(), true,
                                                    needed > 0 ? &formatted[0] : nullptr, needed);
    if (wrote < 0 || wrote != needed) {
        set_last_error("chat template rendering wrote " + std::to_string(wrote) + " of " + std::to_string(needed) + " bytes");
        return nullptr;
    }
    return utf8_bytes(env, formatted);
} catch (const std::exception &e) {
    set_last_error(std::string("formatChat threw: ") + e.what());
    return nullptr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_anopticlabs_gravel_llm_LlmJNI_nativeGenerate(JNIEnv *env, jclass, jlong handle,
                                                      jbyteArray prompt, jint max_tokens, jint n_threads,
                                                      jlong call_id, jlong cpu_mask, jint nice_value,
                                                      jlongArray stats) try {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) {
        set_last_error("generate called with a null engine handle");
        return nullptr;
    }
    // This call's abort key, read by the engine's abort callback through
    // its user data for the whole call; registered for the call's lifetime.
    const int64_t this_call = call_id;
    if (!register_call(this_call)) {
        set_last_error("call id " + std::to_string(this_call) + " is already in flight");
        return nullptr;
    }
    struct Unregister {
        int64_t id;
        ~Unregister() { unregister_call(id); }
    } unregister_on_exit{this_call};
    if (is_call_cancelled(this_call)) {
        set_last_error("generation canceled before it started");
        return nullptr;
    }

    // Reports into the caller's optional stats array as each value becomes
    // known, so a failed call still reports what it got to.
    auto report = [&](int slot, int64_t value) {
        if (stats != nullptr && env->GetArrayLength(stats) > slot) {
            const jlong v = value;
            env->SetLongArrayRegion(stats, slot, 1, &v);
        }
    };

    std::string prompt_text;
    if (!bytes_in(env, prompt, prompt_text)) {
        set_last_error("generate could not read the prompt");
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(engine->model);
    // Each generation starts from an empty memory: the whole prompt is
    // resent, so state from an earlier call would corrupt the positions.
    llama_memory_clear(llama_get_memory(engine->ctx), /*data=*/true);

    // The rendered prompt is tokenized as llama.cpp's own tools tokenize a
    // formatted chat: control tokens parsed as specials, the vocabulary's
    // own BOS added when it asks for one. The sizing call answers the
    // negated count, or INT32_MIN past the int32 range.
    const int32_t sized = llama_tokenize(vocab, prompt_text.c_str(), static_cast<int32_t>(prompt_text.size()),
                                         nullptr, 0, /*add_special=*/true, /*parse_special=*/true);
    if (sized >= 0 || sized == INT32_MIN) {
        set_last_error("the prompt tokenizes to nothing");
        return nullptr;
    }
    const int n_needed = -sized;
    std::vector<llama_token> tokens(static_cast<size_t>(n_needed));
    if (llama_tokenize(vocab, prompt_text.c_str(), static_cast<int32_t>(prompt_text.size()),
                       tokens.data(), static_cast<int32_t>(tokens.size()), true, true) < 0) {
        set_last_error("tokenizing the prompt failed");
        return nullptr;
    }
    report(kStatPromptTokens, n_needed);

    // A prompt that fills the context, or the batch it must fit as one
    // (see init), is refused; the token count above is the caller's to
    // act on.
    const int n_ctx = static_cast<int>(llama_n_ctx(engine->ctx));
    const int usable = std::min(n_ctx, static_cast<int>(llama_n_batch(engine->ctx)));
    if (n_needed >= usable) {
        set_last_error("prompt of " + std::to_string(n_needed) + " tokens does not fit the context of "
                       + std::to_string(usable) + " tokens");
        return nullptr;
    }
    const int wanted = max_tokens > 0 ? max_tokens : kDefaultMaxTokens;
    const int cap = std::min(wanted, n_ctx - n_needed);
    const int threads = std::min(n_threads > 0 ? n_threads : kDefaultThreads, kMaxThreads);

    std::string out;
    // Reaching the cap means the token budget ran out, or the context when
    // it was the smaller of the two.
    int64_t stop_reason = cap < wanted ? kStopContextFull : kStopTokenCap;
    int generated = 0;
    int64_t prefill_us = -1;
    int64_t decode_us = -1;
    bool cancelled = false;
    std::string failure;
    {
        // Scoped to the engine calls alone; the destructor restores the
        // thread before any JNI call below runs.
        ScopedPlacement placement(cpu_mask, nice_value);
        attach_threadpool(engine, threads, placement.applied_mask(), placement.applied_nice());
        // The engine polls the abort callback inside every decode, so a
        // cancel lands during the prompt's prefill too, not only between
        // tokens. Detached before the scope ends so no later call can reach
        // this call's key.
        llama_set_abort_callback(
            engine->ctx,
            [](void *data) -> bool { return is_call_cancelled(*static_cast<const int64_t *>(data)); },
            const_cast<int64_t *>(&this_call));
        struct ClearAbort {
            llama_context *ctx;
            ~ClearAbort() { llama_set_abort_callback(ctx, nullptr, nullptr); }
        } clear_abort{engine->ctx};

        llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        std::unique_ptr<llama_sampler, void (*)(llama_sampler *)> sampler_owner(sampler, llama_sampler_free);

        const int64_t t0 = ggml_time_us();
        llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
        int rc = llama_decode(engine->ctx, batch);
        const int64_t t1 = ggml_time_us();
        prefill_us = t1 - t0;
        if (rc != 0) {
            cancelled = rc == 2 || is_call_cancelled(this_call);
            if (!cancelled) {
                failure = rc == 1
                    ? "prompt of " + std::to_string(n_needed) + " tokens found no room in the context of " + std::to_string(n_ctx) + " tokens"
                    : "prompt decode failed with code " + std::to_string(rc);
            }
        }
        llama_token id = 0; // outlives each decode: the batch holds its address
        while (failure.empty() && !cancelled && generated < cap) {
            if (is_call_cancelled(this_call)) {
                cancelled = true;
                break;
            }
            id = llama_sampler_sample(sampler, engine->ctx, -1);
            if (llama_vocab_is_eog(vocab, id)) {
                stop_reason = kStopEndOfGeneration;
                break;
            }
            const std::string piece = token_piece(vocab, id);
            if (out.size() + piece.size() > kMaxOutputBytes) {
                stop_reason = kStopByteCap;
                break;
            }
            out += piece;
            ++generated;
            if (generated >= cap) break;
            batch = llama_batch_get_one(&id, 1);
            rc = llama_decode(engine->ctx, batch);
            if (rc == 1) {
                stop_reason = kStopContextFull;
                break;
            }
            if (rc != 0) {
                cancelled = rc == 2 || is_call_cancelled(this_call);
                if (!cancelled) failure = "decode failed with code " + std::to_string(rc);
                break;
            }
        }
        decode_us = ggml_time_us() - t1;
    }
    report(kStatGeneratedTokens, generated);
    report(kStatPrefillMicros, prefill_us);
    report(kStatDecodeMicros, decode_us);

    if (cancelled) {
        set_last_error("generation aborted by cancellation");
        return nullptr;
    }
    if (!failure.empty()) {
        set_last_error(failure);
        return nullptr;
    }
    report(kStatStopReason, stop_reason);
    return utf8_bytes(env, out);
} catch (const std::exception &e) {
    set_last_error(std::string("generate threw: ") + e.what());
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_anopticlabs_gravel_llm_LlmJNI_nativeCancel(JNIEnv *, jclass, jlong call_id) {
    request_call_cancel(call_id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_anopticlabs_gravel_llm_LlmJNI_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *engine = reinterpret_cast<Engine *>(handle);
    if (engine == nullptr) return;
    if (engine->ctx != nullptr) llama_free(engine->ctx);
    // Pools after the context (see Engine).
    for (const Pool &p : engine->pools) ggml_threadpool_free(p.pool);
    if (engine->model != nullptr) llama_model_free(engine->model);
    delete engine;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_anopticlabs_gravel_llm_LlmJNI_nativeGetLastError(JNIEnv *env, jclass) {
    return utf8_bytes(env, t_last_error);
}
