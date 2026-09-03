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
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_set>

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

extern "C" JNIEXPORT jbyteArray JNICALL
Java_coredevices_whisper_WhisperJNI_nativeTranscribe(JNIEnv *env, jclass, jlong handle,
                                                     jfloatArray pcm, jint n_threads,
                                                     jstring language, jlong call_id,
                                                     jlong cpu_mask, jint nice_value) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        set_last_error("transcribe called with a null engine handle");
        return nullptr;
    }

    // This call's abort key lives on the stack for the whole whisper_full
    // call; the abort_callback reads it through user_data. Cleared on every
    // return path so a cancel request for this id cannot linger.
    const int64_t this_call = call_id;

    const jsize n_samples = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        // A failed pin leaves a pending OutOfMemoryError; clear it and
        // report through the shim's own error channel instead of passing a
        // null buffer into the engine.
        env->ExceptionClear();
        set_last_error("could not pin the PCM buffer");
        clear_call_cancel(this_call);
        return nullptr;
    }

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

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT); // read-only, never copy back
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
