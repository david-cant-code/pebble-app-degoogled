// JNI shim between coredevices.whisper (Kotlin) and the statically linked
// whisper.cpp engine. This file is the entire native boundary for speech
// recognition; it stays deliberately small so it can be audited in one
// sitting.
//
// Contract the Kotlin side relies on:
//  - Strings cross the boundary Kotlin-ward as jbyteArray holding UTF-8,
//    decoded in Kotlin. Engine output can contain byte sequences that are
//    not valid modified UTF-8, and NewStringUTF aborts the whole process
//    under CheckJNI when handed those, so it is never used here.
//  - nativeTranscribe returns null on failure and records a reason for
//    nativeGetLastError; an empty array is a valid result meaning "no
//    speech found".
//  - Cancellation is per call, keyed by a caller-supplied call id, polled
//    by whisper's abort_callback. A cancel request targets exactly one
//    call, so it can never revoke a different call's pending abort. The
//    Kotlin service still serializes native calls behind a mutex, but an
//    abandoned wedged call can briefly run alongside a fresh one, and
//    per-call ids keep each one's cancellation independent.

#include <jni.h>
#include <android/log.h>
#include <sched.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <unordered_set>
#include <vector>

#include "ggml-cpu.h"
#include "whisper.h"

namespace {

constexpr const char *kLogTag = "whisper_jni";

// The model's own per-segment "this is probably not speech" confidence.
// Greedy whisper is known to hallucinate fluent filler ("Thank you.") on
// silence and noise; dropping segments the model itself flags is the
// engine-side half of that defense. The Kotlin validateContainsSpeech
// guard runs independently on top. 0.6 matches whisper's stock
// no_speech_thold default.
constexpr float kNoSpeechThreshold = 0.6f;

// Call ids whose transcription has been asked to abort. Membership is the
// abort signal for that call; a call clears its own id on return. The set
// is guarded by a mutex rather than a lock-free structure because the
// abort_callback polls only between inference passes, not on a hot path.
std::mutex g_cancel_mutex;
std::unordered_set<int64_t> g_cancelled_calls;

bool is_call_cancelled(int64_t call_id) {
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    return g_cancelled_calls.count(call_id) != 0;
}

void request_call_cancel(int64_t call_id) {
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    g_cancelled_calls.insert(call_id);
}

void clear_call_cancel(int64_t call_id) {
    std::lock_guard<std::mutex> lock(g_cancel_mutex);
    g_cancelled_calls.erase(call_id);
}

std::mutex g_error_mutex;
std::string g_last_error;

void set_last_error(const std::string &msg) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", msg.c_str());
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = msg;
}

// whisper/ggml report every failure detail through their log callback and
// Android drops stderr entirely, so without this bridge each init failure
// is a bare null handle with the reason lost. Installed once per process.
void log_to_android(ggml_log_level level, const char *text, void * /*user*/) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        default: break;
    }
    __android_log_print(prio, "whisper.cpp", "%s", text);
}

std::once_flag g_log_bridge_once;

void install_log_bridge() {
    std::call_once(g_log_bridge_once, [] {
        whisper_log_set(log_to_android, nullptr);
        ggml_log_set(log_to_android, nullptr);
    });
}

// Scheduling placement of the calling thread for the duration of one
// engine call, restored on every exit path by the destructor. ggml creates
// its worker threads per graph with pthread_create and no attributes, so
// they inherit whatever affinity mask and nice value the calling thread
// holds when whisper_full runs; this is the only handle the shim has on
// where the decode runs, since whisper exposes no threadpool or cpumask
// control. A zero mask or a zero nice value leaves that dimension alone,
// and a refused syscall is logged and ignored: the process cannot pin to
// CPUs outside its cpuset or raise priority past its rlimit, and a decode
// on the default placement is always better than no decode.
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
                mask_applied_ = true;
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
                nice_applied_ = true;
            } else {
                __android_log_print(ANDROID_LOG_WARN, kLogTag,
                                    "nice %d not applied: %s", nice_value, strerror(errno));
            }
        }
    }

    ~ScopedPlacement() {
        if (mask_applied_) sched_setaffinity(0, sizeof(saved_mask_), &saved_mask_);
        if (nice_applied_) setpriority(PRIO_PROCESS, gettid(), saved_nice_);
    }

    ScopedPlacement(const ScopedPlacement &) = delete;
    ScopedPlacement &operator=(const ScopedPlacement &) = delete;

private:
    cpu_set_t saved_mask_{};
    bool mask_applied_ = false;
    int saved_nice_ = 0;
    bool nice_applied_ = false;
};

// Voice activity detection ahead of the decode. Whisper always evaluates a
// padded window, so a dictation session that streams the firmware's full
// 15 second window with two seconds of speech pays for the whole window;
// cutting the input to its speech segments before whisper_full makes the
// encoder cost follow the speech, and a session with no speech at all
// returns before any encoder pass. The engine's own params.vad path does
// the same cut but always spawns four VAD threads per 32 ms window and
// keeps its detector inside the whisper state; this shim owns the detector
// context instead (one thread, no worker spawn per window) and assembles
// the trimmed buffer the way the engine does: each speech segment plus the
// overlap into the next, joined by 100 ms of silence.

// Dictation-tuned segmentation. A pause inside a sentence must not become a
// cut (min_silence well above the engine's 100 ms default), and word onsets
// and tails must survive the cut (speech_pad well above the 30 ms default).
whisper_vad_params dictation_vad_params() {
    whisper_vad_params p = whisper_vad_default_params();
    p.threshold               = 0.5f;
    p.min_speech_duration_ms  = 250;
    p.min_silence_duration_ms = 500;
    p.speech_pad_ms           = 200;
    p.samples_overlap         = 0.1f;
    return p;
}

// Speech-only copy of [samples]. Returns false when detection itself
// failed (the caller then decodes the untrimmed audio); an empty result
// with true means no speech was detected.
bool trim_to_speech(whisper_vad_context *vctx, const float *samples, int n_samples,
                    std::vector<float> &out) {
    whisper_vad_segments *segments =
        whisper_vad_segments_from_samples(vctx, dictation_vad_params(), samples, n_samples);
    if (segments == nullptr) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag, "vad: detection failed, decoding untrimmed audio");
        return false;
    }
    const int n_segments = whisper_vad_segments_n_segments(segments);
    const int overlap = static_cast<int>(0.1f * WHISPER_SAMPLE_RATE);
    const int gap     = static_cast<int>(0.1f * WHISPER_SAMPLE_RATE);
    out.clear();
    int speech = 0;
    for (int i = 0; i < n_segments; ++i) {
        // Segment bounds are centiseconds on the original timeline.
        int start = static_cast<int>(whisper_vad_segments_get_segment_t0(segments, i) * (WHISPER_SAMPLE_RATE / 100));
        int end   = static_cast<int>(whisper_vad_segments_get_segment_t1(segments, i) * (WHISPER_SAMPLE_RATE / 100));
        if (i < n_segments - 1) end += overlap;
        start = std::max(0, std::min(start, n_samples - 1));
        end   = std::max(start, std::min(end, n_samples - 1));
        if (i > 0) out.insert(out.end(), gap, 0.0f);
        out.insert(out.end(), samples + start, samples + end);
        speech += end - start;
    }
    whisper_vad_free_segments(segments);
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "vad: %d speech segment(s), %.2f s of %.2f s kept",
                        n_segments, speech / float(WHISPER_SAMPLE_RATE), n_samples / float(WHISPER_SAMPLE_RATE));
    return true;
}

// Model-free speed probe: one encoder block of the base model's shape over
// a 512-frame sequence, built straight on ggml with random weights and
// timed on the CPU backend. It exists so the model picker can estimate,
// before anything is downloaded, how long a full watch dictation window
// would take each catalog model on this phone; the catalog constants that
// turn its result into seconds are calibrated against real decodes (see
// WhisperSpeedCalibration in util). The op mix mirrors whisper's encoder
// layer at this engine revision: layer norm, f16 QKV projections against
// f32 activations, flash attention over f16 keys and values, the output
// projection, and the GELU MLP. 512 frames rather than the encoder's 1500
// keeps the block's tensors around 60 MB with every intermediate
// materialized (there is no graph allocator here), cheap enough to run at
// model selection.
//
// ggml aborts the whole process when a context runs out of memory, so the
// data context is sized to the byte from a dry build of the same graph in
// a no-alloc context, and the compute work buffer is owned here rather
// than carved from the context per call (ggml_graph_compute_with_ctx
// allocates a new one on every call, which would exhaust any fixed budget
// inside the timing loop).
constexpr int kBenchState  = 512;   // base model width
constexpr int kBenchHeads  = 8;
constexpr int kBenchCtx    = 512;   // frames (a multiple of 256, as the engine pads to)
constexpr int kBenchMlp    = 4 * kBenchState;
constexpr int64_t kBenchBudgetUs = 1000 * 1000;   // wall-clock budget for the timed loop
constexpr int kBenchMaxIters = 64;
constexpr int kBenchMinIters = 3;

// Deterministic small values in [-0.5, 0.5): the numbers never matter,
// only that no denormals or infinities slow the arithmetic down.
struct BenchRandom {
    uint32_t state = 0x9E3779B9u;
    float next() {
        state = state * 1664525u + 1013904223u;
        return float(state >> 8) / float(1u << 24) - 0.5f;
    }
};

void bench_fill(ggml_tensor *t, BenchRandom &rng) {
    const int64_t n = ggml_nelements(t);
    if (t->type == GGML_TYPE_F16) {
        auto *data = static_cast<ggml_fp16_t *>(t->data);
        for (int64_t i = 0; i < n; ++i) data[i] = ggml_fp32_to_fp16(rng.next());
    } else {
        auto *data = static_cast<float *>(t->data);
        for (int64_t i = 0; i < n; ++i) data[i] = rng.next();
    }
}

// The block's graph plus every tensor it created (views included), so the
// data context can be sized exactly, and the inputs that need values.
struct BenchGraph {
    ggml_cgraph *gf = nullptr;
    std::vector<ggml_tensor *> tensors;
    std::vector<ggml_tensor *> inputs;
};

BenchGraph build_bench_graph(ggml_context *ctx) {
    BenchGraph g;
    auto T = [&](ggml_tensor *t) { g.tensors.push_back(t); return t; };
    auto in32 = [&](int64_t ne0, int64_t ne1) {
        ggml_tensor *t = ne1 > 0 ? ggml_new_tensor_2d(ctx, GGML_TYPE_F32, ne0, ne1)
                                 : ggml_new_tensor_1d(ctx, GGML_TYPE_F32, ne0);
        g.inputs.push_back(t);
        return T(t);
    };
    auto in16 = [&](int64_t ne0, int64_t ne1) {
        ggml_tensor *t = ggml_new_tensor_2d(ctx, GGML_TYPE_F16, ne0, ne1);
        g.inputs.push_back(t);
        return T(t);
    };
    const int S = kBenchState, C = kBenchCtx, H = kBenchHeads, D = kBenchState / kBenchHeads, M = kBenchMlp;

    ggml_tensor *x = in32(S, C);
    ggml_tensor *ln0_w = in32(S, 0), *ln0_b = in32(S, 0), *ln1_w = in32(S, 0), *ln1_b = in32(S, 0);
    ggml_tensor *wq = in16(S, S), *wk = in16(S, S), *wv = in16(S, S), *wo = in16(S, S);
    ggml_tensor *bq = in32(S, 0), *bv = in32(S, 0), *bo = in32(S, 0);
    ggml_tensor *w0 = in16(S, M), *b0 = in32(M, 0), *w1 = in16(M, S), *b1 = in32(S, 0);

    // Attention block, as whisper_build_graph_encoder lays it out.
    ggml_tensor *cur = T(ggml_norm(ctx, x, 1e-5f));
    cur = T(ggml_add(ctx, T(ggml_mul(ctx, cur, ln0_w)), ln0_b));
    ggml_tensor *q = T(ggml_add(ctx, T(ggml_mul_mat(ctx, wq, cur)), bq));
    ggml_tensor *k = T(ggml_mul_mat(ctx, wk, cur));
    ggml_tensor *v = T(ggml_add(ctx, T(ggml_mul_mat(ctx, wv, cur)), bv));
    ggml_tensor *Q = T(ggml_permute(ctx, T(ggml_reshape_3d(ctx, q, D, H, C)), 0, 2, 1, 3));
    ggml_tensor *k16 = T(ggml_cast(ctx, k, GGML_TYPE_F16));
    ggml_tensor *v16 = T(ggml_cast(ctx, v, GGML_TYPE_F16));
    const size_t es = ggml_element_size(k16);
    ggml_tensor *K = T(ggml_view_3d(ctx, k16, D, C, H, es * S, es * D, 0));
    ggml_tensor *V = T(ggml_view_3d(ctx, v16, D, C, H, es * S, es * D, 0));
    cur = T(ggml_flash_attn_ext(ctx, Q, K, V, nullptr, 1.0f / sqrtf(float(D)), 0.0f, 0.0f));
    cur = T(ggml_reshape_2d(ctx, cur, S, C));
    cur = T(ggml_add(ctx, T(ggml_mul_mat(ctx, wo, cur)), bo));
    ggml_tensor *ff = T(ggml_add(ctx, cur, x));

    // Feed-forward block.
    cur = T(ggml_norm(ctx, ff, 1e-5f));
    cur = T(ggml_add(ctx, T(ggml_mul(ctx, cur, ln1_w)), ln1_b));
    cur = T(ggml_gelu(ctx, T(ggml_add(ctx, T(ggml_mul_mat(ctx, w0, cur)), b0))));
    cur = T(ggml_add(ctx, T(ggml_mul_mat(ctx, w1, cur)), b1));
    ggml_tensor *out = T(ggml_add(ctx, cur, ff));

    g.gf = ggml_new_graph(ctx);
    ggml_build_forward_expand(g.gf, out);
    return g;
}

// Bytes a data context needs to hold [g]: an object plus padded data per
// non-view tensor, an object per view, and the graph. Each term rounds up
// separately, so this can only overstate what ggml_init will consume.
size_t bench_context_bytes(const BenchGraph &g) {
    size_t bytes = ggml_graph_overhead() + 4096;
    for (const ggml_tensor *t : g.tensors) {
        bytes += ggml_tensor_overhead();
        if (t->view_src == nullptr) bytes += GGML_PAD(ggml_nbytes(t), GGML_MEM_ALIGN);
    }
    return bytes;
}

// Median nanoseconds per block evaluation at [n_threads], or -1 with the
// reason recorded for nativeGetLastError. The median rather than the mean
// keeps one preempted iteration from moving the score.
int64_t run_benchmark(int n_threads) {
    ggml_time_init();
    ggml_cpu_init();

    // Dry build: shapes only, to learn the data and work sizes.
    size_t data_bytes = 0;
    size_t work_bytes = 0;
    {
        std::vector<uint8_t> meta(ggml_tensor_overhead() * 128 + ggml_graph_overhead() + 4096);
        ggml_init_params mparams = { /*.mem_size =*/ meta.size(), /*.mem_buffer =*/ meta.data(), /*.no_alloc =*/ true };
        ggml_context *mctx = ggml_init(mparams);
        if (mctx == nullptr) {
            set_last_error("benchmark: ggml_init (dry build) failed");
            return -1;
        }
        BenchGraph g = build_bench_graph(mctx);
        data_bytes = bench_context_bytes(g);
        work_bytes = ggml_graph_plan(g.gf, n_threads, nullptr).work_size;
        ggml_free(mctx);
    }

    std::vector<uint8_t> data, work;
    try {
        data.resize(data_bytes);
        work.resize(work_bytes + 1);
    } catch (const std::bad_alloc &) {
        set_last_error("benchmark: could not allocate " + std::to_string((data_bytes + work_bytes) / 1024) + " KB");
        return -1;
    }
    ggml_init_params params = { /*.mem_size =*/ data.size(), /*.mem_buffer =*/ data.data(), /*.no_alloc =*/ false };
    ggml_context *ctx = ggml_init(params);
    if (ctx == nullptr) {
        set_last_error("benchmark: ggml_init failed");
        return -1;
    }
    BenchGraph g = build_bench_graph(ctx);
    BenchRandom rng;
    for (ggml_tensor *t : g.inputs) bench_fill(t, rng);
    ggml_cplan cplan = ggml_graph_plan(g.gf, n_threads, nullptr);
    cplan.work_data = work.data();

    // The first evaluation pays one-time setup, so it is excluded.
    if (ggml_graph_compute(g.gf, &cplan) != GGML_STATUS_SUCCESS) {
        set_last_error("benchmark: graph compute failed");
        ggml_free(ctx);
        return -1;
    }
    std::vector<int64_t> samples;
    samples.reserve(kBenchMaxIters);
    const int64_t loop_start = ggml_time_us();
    while (int(samples.size()) < kBenchMaxIters) {
        const int64_t t0 = ggml_time_us();
        if (ggml_graph_compute(g.gf, &cplan) != GGML_STATUS_SUCCESS) {
            set_last_error("benchmark: graph compute failed");
            ggml_free(ctx);
            return -1;
        }
        const int64_t t1 = ggml_time_us();
        samples.push_back((t1 - t0) * 1000);
        if (int(samples.size()) >= kBenchMinIters && t1 - loop_start >= kBenchBudgetUs) break;
    }
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "benchmark: %zu iterations on %d threads, %zu KB context (%zu KB used), %zu KB work",
                        samples.size(), n_threads, data.size() / 1024, ggml_used_mem(ctx) / 1024, work.size() / 1024);
    ggml_free(ctx);
    std::sort(samples.begin(), samples.end());
    return samples[samples.size() / 2];
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

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_coredevices_whisper_WhisperJNI_nativeInit(JNIEnv *env, jclass, jstring model_path) {
    install_log_bridge();

    // GetStringUTFChars yields modified UTF-8, which matches real UTF-8
    // only for ASCII; model paths are filesDir plus catalog ids, ASCII by
    // construction, so this is safe here (and only here).
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0; // pending OutOfMemoryError
    }

    whisper_context_params cparams = whisper_context_default_params();
    // CPU only: no GPU backend is compiled in, and leaving the flag set
    // would make init probe for one and log noise on every start.
    cparams.use_gpu = false;

    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    if (ctx == nullptr) {
        set_last_error(std::string("whisper_init_from_file_with_params failed for ") + path
                       + " (see whisper.cpp logcat lines for the engine's reason)");
    }
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jlong JNICALL
Java_coredevices_whisper_WhisperJNI_nativeVadInit(JNIEnv *env, jclass, jstring model_path) {
    install_log_bridge();
    // ASCII by construction, as for nativeInit: files dir plus catalog id.
    const char *path = env->GetStringUTFChars(model_path, nullptr);
    if (path == nullptr) {
        return 0; // pending OutOfMemoryError
    }
    whisper_vad_context_params vparams = whisper_vad_default_context_params();
    // One thread: the detector's graph is tiny (about 0.7 M multiply-adds
    // per 32 ms window) and ggml spawns its workers per graph, so any
    // second thread costs more in thread creation than it saves.
    vparams.n_threads = 1;
    vparams.use_gpu   = false;
    whisper_vad_context *vctx = whisper_vad_init_from_file_with_params(path, vparams);
    if (vctx == nullptr) {
        set_last_error(std::string("whisper_vad_init_from_file_with_params failed for ") + path);
    }
    env->ReleaseStringUTFChars(model_path, path);
    return reinterpret_cast<jlong>(vctx);
}

extern "C" JNIEXPORT void JNICALL
Java_coredevices_whisper_WhisperJNI_nativeVadFree(JNIEnv *, jclass, jlong handle) {
    auto *vctx = reinterpret_cast<whisper_vad_context *>(handle);
    if (vctx != nullptr) {
        whisper_vad_free(vctx);
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_coredevices_whisper_WhisperJNI_nativeBenchmark(JNIEnv *, jclass, jint n_threads,
                                                    jlong cpu_mask, jint nice_value) {
    install_log_bridge();
    // Same placement as a decode, so the score reflects where dictation runs.
    ScopedPlacement placement(cpu_mask, nice_value);
    return run_benchmark(n_threads > 0 ? n_threads : 4);
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_coredevices_whisper_WhisperJNI_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                     jfloatArray pcm, jint n_threads,
                                                     jstring language, jlong call_id,
                                                     jlong cpu_mask, jint nice_value,
                                                     jlong vad_handle, jintArray stats) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        set_last_error("transcribe called with a null engine handle");
        return nullptr;
    }

    // Reports the sample count the engine is given (after any detector
    // cut) into the caller's optional one-slot array, so the Kotlin side
    // can time the decode per second of speech rather than of input.
    auto report_decoded = [&](int decoded) {
        if (stats != nullptr && env->GetArrayLength(stats) > 0) {
            const jint value = decoded;
            env->SetIntArrayRegion(stats, 0, 1, &value);
        }
    };

    // This call's abort key lives on the stack for the whole whisper_full
    // call; the abort_callback reads it through user_data. Cleared on every
    // return path so a cancel request for this id cannot linger.
    const int64_t this_call = call_id;

    const jsize n_pinned = env->GetArrayLength(pcm);
    jfloat *pinned = env->GetFloatArrayElements(pcm, nullptr);
    if (pinned == nullptr) {
        // A failed pin leaves a pending OutOfMemoryError; clear it and
        // report through the shim's own error channel instead of passing a
        // null buffer into the engine.
        env->ExceptionClear();
        set_last_error("could not pin the PCM buffer");
        clear_call_cancel(this_call);
        return nullptr;
    }

    // With a detector, the engine sees the speech-only buffer; without one
    // (or when detection fails) it sees the pinned input unchanged.
    std::vector<float> trimmed;
    const float *samples = pinned;
    int n_samples = static_cast<int>(n_pinned);
    auto *vctx = reinterpret_cast<whisper_vad_context *>(vad_handle);
    if (vctx != nullptr && trim_to_speech(vctx, pinned, n_samples, trimmed)) {
        if (trimmed.empty()) {
            // No speech: "" without an encoder pass. The language string is
            // only pinned further down, so nothing else needs releasing.
            env->ReleaseFloatArrayElements(pcm, pinned, JNI_ABORT);
            clear_call_cancel(this_call);
            report_decoded(0);
            return utf8_bytes(env, std::string());
        }
        samples   = trimmed.data();
        n_samples = static_cast<int>(trimmed.size());
    }
    report_decoded(n_samples);

    // Language codes are ASCII (ISO 639-1), so modified UTF-8 is safe on
    // this input path too. Null means in-engine language detection.
    const char *lang = (language != nullptr) ? env->GetStringUTFChars(language, nullptr) : nullptr;

    // whisper_full does not validate an explicitly requested language: an
    // unknown code yields lang_id -1, and whisper_token_lang(ctx, -1) puts
    // a second SOT token into the decoder prompt on multilingual models,
    // silently degrading output with rc == 0. The Kotlin layer normalizes
    // the legacy Java locale codes it knows about; this is the backstop
    // for everything else the ~190-entry language picker can produce that
    // whisper's ~100-entry map does not know. Fall back to in-engine
    // detection, which is what the caller gets for a null language anyway.
    if (lang != nullptr && whisper_lang_id(lang) == -1) {
        __android_log_print(ANDROID_LOG_WARN, kLogTag,
                            "unknown language '%s', falling back to in-engine detection", lang);
        env->ReleaseStringUTFChars(language, lang);
        lang = nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads       = n_threads > 0 ? n_threads : 4;
    params.language        = lang;
    params.translate       = false;
    params.no_timestamps   = true;  // dictation wants plain text
    params.print_progress  = false;
    params.print_realtime  = false;
    params.print_special   = false;
    params.print_timestamps = false;
    params.suppress_blank  = true;
    params.suppress_nst    = true;  // suppress non-speech token output

    // Whisper always evaluates a padded 30 second mel window (encoder
    // context 1500), but dictation clips are a few seconds long. Trimming
    // the encoder context to the real audio length cuts the dominant
    // encoder cost several-fold on short clips, which is what keeps the
    // large models inside the watch firmware's transcription deadline. One
    // encoder position covers 20 ms (WHISPER_SAMPLE_RATE / 50 samples);
    // the margin leaves the decoder room to attend past the end of speech.
    const int wanted_ctx = static_cast<int>(n_samples / (WHISPER_SAMPLE_RATE / 50)) + 64;
    params.audio_ctx = std::min(wanted_ctx, 1500);

    // Disable the temperature-fallback ladder: this transcription path
    // serves interactive dictation with a hard caller-side deadline, and
    // the ladder multiplies worst-case decode time by the number of retry
    // temperatures. Measured on degraded watch captures on phone-class
    // hardware, a clip whose single greedy pass costs ~1-3 s ground for
    // 38-89 s on the default ladder before returning the same low-quality
    // text the first pass produced. A single pass keeps the worst case
    // near one encoder plus one decoder evaluation; audio bad enough that
    // the ladder would have engaged yields its garbage immediately, and
    // the caller-side no-speech and text-validation gates reject it.
    params.temperature_inc = 0.0f;

    // Cap tokens per segment: without the ladder, a decoder repetition
    // loop on degraded audio runs to whisper's internal per-segment limit
    // (~10 s on the base model for one such runaway; the small model
    // decodes ~3x slower, which would breach the dictation deadline on
    // its own). Hitting the cap closes the segment and decoding continues
    // from there, so legitimate speech gets split across segments rather
    // than truncated; real watch dictations measure ~15-30 tokens total.
    // Measured on the degraded reference capture, a 64 cap still let the
    // small model's multi-segment runaway reach 12.6-14.4 s against the
    // 14 s dictation deadline; 32 holds it comfortably inside.
    params.max_tokens = 32;

    // Poll this specific call's abort key. user_data carries the stack
    // address of this_call, valid for the whole whisper_full call.
    params.abort_callback = [](void *data) -> bool {
        return is_call_cancelled(*static_cast<const int64_t *>(data));
    };
    params.abort_callback_user_data = const_cast<int64_t *>(&this_call);

    int rc;
    {
        // Scoped to the engine call alone; the destructor restores the
        // thread before any JNI call below runs.
        ScopedPlacement placement(cpu_mask, nice_value);
        rc = whisper_full(ctx, params, samples, static_cast<int>(n_samples));
    }
    const bool was_cancelled = is_call_cancelled(this_call);
    // The abort key is per call, so clearing it here cannot revoke any
    // other in-flight call's pending abort.
    clear_call_cancel(this_call);

    env->ReleaseFloatArrayElements(pcm, pinned, JNI_ABORT); // read-only, never copy back
    if (lang != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }

    if (rc != 0) {
        if (was_cancelled) {
            set_last_error("transcription aborted by cancellation");
        } else {
            set_last_error("whisper_full failed with code " + std::to_string(rc));
        }
        return nullptr;
    }

    std::string text;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        if (whisper_full_get_segment_no_speech_prob(ctx, i) > kNoSpeechThreshold) {
            continue;
        }
        const char *segment = whisper_full_get_segment_text(ctx, i);
        if (segment != nullptr) {
            text += segment; // segments carry their own leading whitespace
        }
    }
    return utf8_bytes(env, text);
}

extern "C" JNIEXPORT void JNICALL
Java_coredevices_whisper_WhisperJNI_nativeCancel(JNIEnv *, jclass, jlong call_id) {
    request_call_cancel(call_id);
}

extern "C" JNIEXPORT void JNICALL
Java_coredevices_whisper_WhisperJNI_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_coredevices_whisper_WhisperJNI_nativeGetLastError(JNIEnv *env, jclass) {
    std::string copy;
    {
        std::lock_guard<std::mutex> lock(g_error_mutex);
        copy = g_last_error;
    }
    return utf8_bytes(env, copy);
}
