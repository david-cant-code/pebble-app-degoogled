package com.anopticlabs.gravel.socketfilter

// Declared in the test sources and compiled out of release builds of the library, so release
// carries neither the symbols nor these declarations. Modes: probe_test_mode in socket_filter.c.
internal object ProbeTestHooks {
    external fun setProbeBehavior(mode: Int, exitCode: Int)

    /** Attaches the filter to the calling thread alone; 0 or the errno. */
    external fun attachFilterToThisThread(): Int

    /** Two installs in a forked child that starts as not installed; their packed results. */
    external fun installTwiceInAChild(exePath: String): LongArray?
}
