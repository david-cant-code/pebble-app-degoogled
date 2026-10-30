package coredevices.coreapp.di

import coredevices.ExperimentalDevices
import coredevices.libindex.IndexDevices
import coredevices.libindex.LibIndex
import coredevices.ring.database.Preferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Never scans, never pairs, never reports a ring. :libindex stays on the
 * compile classpath for the watch UI's sake, but binding this instead of
 * loading libIndexModule means RealLibIndex, its BLE scanner, and its Room
 * database are never constructed.
 */
class NoOpLibIndex : LibIndex {
    override val isScanning: StateFlow<Boolean> = MutableStateFlow(false)
    override val rings: IndexDevices = MutableStateFlow(emptyList())
    override fun init(bluetoothPermissionChanged: Flow<Boolean>) {}
    override fun startScan() {}
    override fun stopScan() {}
    override fun warnIfNoCompanionAssociations() {}
}

/**
 * Replaces the unplugged :experimental module's experimentalModule in the
 * Koin graph (see MainApplication). Everything bound here is a fork-owned
 * no-op: the ring/Index runtime is disabled at the DI seam while upstream
 * call sites keep compiling. CoreConfig.enableIndex stays false (its
 * in-app set-points are suppressed in the UI), so no Index UI is reachable
 * either. Debug builds also carry upstream's adb-driven SetSettingReceiver,
 * which can rewrite the whole persisted config, enableIndex included, from
 * the adb shell; release builds drop it, and a flag flipped that way only
 * reaches the empty stubs bound here.
 */
val ringStubsModule = module {
    singleOf(::ExperimentalDevices)
    singleOf(::Preferences)
    singleOf(::NoOpLibIndex) bind LibIndex::class
}
