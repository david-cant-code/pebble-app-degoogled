package com.anopticlabs.gravel.socketfilter

// Declared in the test sources and compiled out of release builds of the library, so release
// carries neither the symbol nor this declaration. Modes: 0 real probe, 1 the probe child dies
// of SIGSYS, 2 it exits with exitCode.
internal object ProbeTestHooks {
    external fun setProbeBehavior(mode: Int, exitCode: Int)
}
