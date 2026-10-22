// CPU capability gate for the text engine, the same shape as the speech
// engine's whisper_cpu.c (which states why the probe is its own
// baseline-arch library): libllmjni.so is compiled for the floor set in
// CMakeLists.txt, and the Kotlin side loads it only after this returns true.
#include <jni.h>
#include <sys/auxv.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

JNIEXPORT jboolean JNICALL
Java_com_anopticlabs_gravel_llm_LlmCpuJNI_nativeIsLlmSupported(JNIEnv *env, jclass clazz) {
#if defined(__aarch64__)
    // The hwcaps the build floor requires, the same set as whisper_cpu.c
    // (which states the mapping).
    unsigned long hwcap = getauxval(AT_HWCAP);
    return ((hwcap & HWCAP_ATOMICS) && (hwcap & HWCAP_ASIMDRDM) && (hwcap & HWCAP_CRC32) &&
            (hwcap & HWCAP_ASIMDDP) && (hwcap & HWCAP_FPHP) && (hwcap & HWCAP_ASIMDHP)) ? JNI_TRUE : JNI_FALSE;
#else
    // arm64-v8a only; any other ABI here is a repackaged APK.
    return JNI_FALSE;
#endif
}
