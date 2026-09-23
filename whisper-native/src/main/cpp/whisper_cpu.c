// CPU capability gate for the whisper engine. libwhisperjni.so is compiled
// for armv8.2-a with dotprod and fp16 (the floor set in CMakeLists.txt);
// those features are selected at compile time with no runtime dispatch, so
// on a CPU missing any of them it may fault with SIGILL at inference time.
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
    // getauxval needs no /proc parsing. The floor makes NDK r28c clang
    // define six __ARM_FEATURE_* macros beyond plain aarch64-android:
    // ATOMICS, QRDMX and CRC32 from armv8.2-a, DOTPROD, and FP16 scalar and
    // vector arithmetic. Linux v6.12 reports those CPU features as the six
    // hwcaps tested here (arch/arm64/kernel/cpufeature.c, arm64_elf_hwcaps).
    unsigned long hwcap = getauxval(AT_HWCAP);
    return ((hwcap & HWCAP_ATOMICS) && (hwcap & HWCAP_ASIMDRDM) && (hwcap & HWCAP_CRC32) &&
            (hwcap & HWCAP_ASIMDDP) && (hwcap & HWCAP_FPHP) && (hwcap & HWCAP_ASIMDHP)) ? JNI_TRUE : JNI_FALSE;
#else
    // The module ships arm64-v8a only; any other ABI reaching this code
    // means a repackaged APK, so answer unsupported rather than guess.
    return JNI_FALSE;
#endif
}
