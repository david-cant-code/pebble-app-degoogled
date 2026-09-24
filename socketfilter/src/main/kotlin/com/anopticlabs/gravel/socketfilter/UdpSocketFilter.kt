package com.anopticlabs.gravel.socketfilter

import android.os.Build
import android.system.OsConstants

sealed interface InstallResult {
    /** The kernel accepted the filter and the installing thread saw a UDP socket refused. */
    data object Installed : InstallResult

    data object AlreadyInstalled : InstallResult

    /**
     * The facility exists and the install did not end as [Installed]. [detail] depends on the
     * stage: a signal, an errno or a thread id, and 0 where the stage has none. At
     * [RefusalStage.PostCheck] the program is attached and stays attached, and [detail] is the
     * errno of the check's socket() call, 0 when the socket was created.
     */
    data class Refused(val stage: RefusalStage, val detail: Int) : InstallResult

    /** This environment cannot carry the filter. */
    data class Unsupported(val reason: UnsupportedReason, val detail: Int) : InstallResult
}

enum class RefusalStage { ProbeSignaled, ProbeFailed, NoNewPrivs, SeccompCall, ThreadSync, PostCheck }

enum class UnsupportedReason { LibraryMissing, ArchitectureMismatch, KernelLacksFilterMode }

/** Socket creation outcomes from one thread: 0 for created, otherwise the errno. */
data class SelfTestReport(val datagram: List<Int>, val others: List<Int>) {
    // Mirrors REFUSAL_ERRNO in socket_filter.c.
    val datagramRefused: Boolean get() = datagram.all { it == OsConstants.EPROTONOSUPPORT }
    val othersCreated: Boolean get() = others.all { it == 0 }
}

/**
 * A process-wide, one-way filter that refuses the creation of IPv4 and IPv6 UDP sockets in
 * every thread. Nothing is installed until [install] is called.
 */
object UdpSocketFilter {
    private const val LIBRARY = "gravelsocketfilter"
    private const val SELF_EXE = "/proc/self/exe"
    private const val DATAGRAM_CASES = 4

    private val lock = Any()
    private var libraryLoaded = false

    /** The most recent [install] result other than [InstallResult.AlreadyInstalled]. */
    @Volatile
    var lastResult: InstallResult? = null
        private set

    /** Idempotent and safe from any thread; a second program is never stacked. */
    fun install(): InstallResult = install(SELF_EXE)

    // The path and the ABI list are parameters so a test can present another architecture.
    internal fun install(
        exePath: String,
        deviceAbis: List<String> = Build.SUPPORTED_ABIS.toList(),
    ): InstallResult = synchronized(lock) {
        val result = when {
            !primaryAbiIsArm(deviceAbis) -> ARCHITECTURE_MISMATCH
            loadLibrary() -> decode(nativeInstall(exePath))
            else -> LIBRARY_MISSING
        }
        if (result != InstallResult.AlreadyInstalled) lastResult = result
        result
    }

    // The library is built for the ARM ABIs only, and its filter refuses every call of another
    // architecture. The first entry is the device's most preferred ABI (android16-release,
    // Build.java, SUPPORTED_ABIS); an x86_64 image that runs ARM code under translation lists
    // x86_64 first. Decided here because the native check runs translated on such a device.
    internal fun primaryAbiIsArm(deviceAbis: List<String>): Boolean =
        deviceAbis.firstOrNull()?.startsWith("arm") == true

    /** Tries to create sockets from the calling thread; null when the library is absent. */
    fun selfTest(): SelfTestReport? {
        if (!loadLibrary()) return null
        val outcomes = nativeSelfTest()?.toList() ?: return null
        return SelfTestReport(outcomes.take(DATAGRAM_CASES), outcomes.drop(DATAGRAM_CASES))
    }

    internal fun program(): IntArray? = if (loadLibrary()) nativeProgram() else null

    internal fun constants(): LongArray? = if (loadLibrary()) nativeConstants() else null

    // A device whose ABI has no build of the library throws here.
    internal fun loadLibrary(): Boolean = synchronized(lock) {
        if (!libraryLoaded) {
            try {
                System.loadLibrary(LIBRARY)
                libraryLoaded = true
            } catch (_: UnsatisfiedLinkError) {
            }
        }
        libraryLoaded
    }

    // Mirrors pack() and the result enums in socket_filter.c.
    internal fun decode(packed: Long): InstallResult {
        val sub = ((packed shr 32) and 0xff).toInt()
        val detail = packed.toInt()
        return when ((packed shr 40).toInt()) {
            0 -> InstallResult.Installed
            1 -> InstallResult.AlreadyInstalled
            2 -> InstallResult.Refused(REFUSAL_STAGES[sub], detail)
            else -> InstallResult.Unsupported(UNSUPPORTED_REASONS.getValue(sub), detail)
        }
    }

    private val LIBRARY_MISSING = InstallResult.Unsupported(UnsupportedReason.LibraryMissing, 0)
    private val ARCHITECTURE_MISMATCH = InstallResult.Unsupported(UnsupportedReason.ArchitectureMismatch, 0)

    private val REFUSAL_STAGES = listOf(
        RefusalStage.ProbeSignaled,
        RefusalStage.ProbeFailed,
        RefusalStage.NoNewPrivs,
        RefusalStage.SeccompCall,
        RefusalStage.ThreadSync,
        RefusalStage.PostCheck,
    )

    private val UNSUPPORTED_REASONS = mapOf(
        1 to UnsupportedReason.ArchitectureMismatch,
        2 to UnsupportedReason.KernelLacksFilterMode,
    )

    private external fun nativeInstall(exePath: String): Long
    private external fun nativeSelfTest(): IntArray?
    private external fun nativeProgram(): IntArray?
    private external fun nativeConstants(): LongArray?
}
