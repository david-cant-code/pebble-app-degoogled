package com.anopticlabs.gravel.socketfilter

// Declared in the test sources and compiled out of release builds of the library, so release
// carries neither the symbols nor these declarations. Modes: 0 real probe, 1 the probe child
// dies of SIGSYS, 2 it exits with exitCode, 3 a post-check that saw the refusal reports exitCode.
internal object ProbeTestHooks {
    external fun setProbeBehavior(mode: Int, exitCode: Int)

    /** Two installs in a forked child that starts as not installed; their packed results. */
    external fun installTwiceInAChild(exePath: String): LongArray?
}
