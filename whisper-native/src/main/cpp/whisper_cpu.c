// CPU capability gate for the whisper engine. libwhisperjni.so is compiled
// for armv8.2-a with dotprod and fp16 (the floor set in CMakeLists.txt);
// those features are selected at compile time with no runtime dispatch, so
// loading it on a CPU missing either one means SIGILL at inference time.
// This probe lives in its own baseline-arch library precisely so the check
// itself can run anywhere, before any engine code is mapped. The Kotlin
// side loads the engine library only after this returns true.
#include <jni.h>
#include <sys/auxv.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

JNIEXPORT jboolean JNICALL
Java_coredevices_whisper_WhisperCpuJNI_nativeIsWhisperSupported(JNIEnv *env, jclass clazz) {
#if defined(__aarch64__)
    // getauxval needs no /proc parsing and reports exactly the hwcaps the
    // build floor requires: SDOT (ASIMDDP) and FP16 arithmetic (ASIMDHP).
    unsigned long hwcap = getauxval(AT_HWCAP);
    return ((hwcap & HWCAP_ASIMDDP) && (hwcap & HWCAP_ASIMDHP)) ? JNI_TRUE : JNI_FALSE;
#else
    // The module ships arm64-v8a only; any other ABI reaching this code
    // means a repackaged APK, so answer unsupported rather than guess.
    return JNI_FALSE;
#endif
}
