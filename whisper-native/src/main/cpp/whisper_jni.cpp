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
//  - Cancellation is a process-wide flag polled by whisper's
//    abort_callback. The Kotlin service serializes native calls behind a
//    mutex (a single transcription in flight at any time), which is what
//    makes one flag per process sufficient.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>

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

std::atomic<bool> g_cancel{false};

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
                                                     jstring language) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx == nullptr) {
        set_last_error("transcribe called with a null engine handle");
        return nullptr;
    }

    const jsize n_samples = env->GetArrayLength(pcm);
    jfloat *samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples == nullptr) {
        // A failed pin leaves a pending OutOfMemoryError; clear it and
        // report through the shim's own error channel instead of passing a
        // null buffer into the engine.
        env->ExceptionClear();
        set_last_error("could not pin the PCM buffer");
        return nullptr;
    }

    // Language codes are ASCII (ISO 639-1), so modified UTF-8 is safe on
    // this input path too. Null means in-engine language detection.
    const char *lang = (language != nullptr) ? env->GetStringUTFChars(language, nullptr) : nullptr;

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

    params.abort_callback = [](void *) -> bool {
        return g_cancel.load(std::memory_order_relaxed);
    };
    params.abort_callback_user_data = nullptr;

    const int rc = whisper_full(ctx, params, samples, static_cast<int>(n_samples));

    env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT); // read-only, never copy back
    if (lang != nullptr) {
        env->ReleaseStringUTFChars(language, lang);
    }

    if (rc != 0) {
        if (g_cancel.load(std::memory_order_relaxed)) {
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
Java_coredevices_whisper_WhisperJNI_nativeSetCancel(JNIEnv *, jclass, jboolean cancel) {
    g_cancel.store(cancel == JNI_TRUE, std::memory_order_relaxed);
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
