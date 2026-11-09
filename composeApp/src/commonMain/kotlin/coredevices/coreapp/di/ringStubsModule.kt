package coredevices.coreapp.di

import coredevices.ExperimentalDevices
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module

/**
 * Replaces the unplugged :experimental module's experimentalModule in the
 * Koin graph (see MainApplication) with a fork-owned no-op facade. The ring
 * seams that do work, LibIndex and the paired ring behind Preferences, are
 * bound by Gravel's ring client (`ringClientModule`), never by upstream's
 * libIndexModule, so RealLibIndex, its BLE scanner and its Room database are
 * never constructed. CoreConfig.enableIndex stays false (its in-app
 * set-points are suppressed in the UI), so upstream's Index UI is not
 * reachable either. Debug builds also carry upstream's adb-driven
 * SetSettingReceiver, which can rewrite the whole persisted config,
 * enableIndex included, from the adb shell; release builds drop it.
 */
val ringStubsModule = module {
    singleOf(::ExperimentalDevices)
}
