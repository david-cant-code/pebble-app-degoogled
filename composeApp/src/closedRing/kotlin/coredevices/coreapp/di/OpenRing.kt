package coredevices.coreapp.di

import org.koin.core.module.Module

fun openRingModules(): List<Module> = emptyList()

fun startGravelRing() {}

/** The stub build has no ring client, so onboarding offers a watch only. */
fun indexOnboardingAvailable(): Boolean = false
