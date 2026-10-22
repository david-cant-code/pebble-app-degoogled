package coredevices.coreapp.di

import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineHacksDelegate
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.libindex.database.BasePreferences
import coredevices.libindex.database.PrefsCollectionIndexStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

actual fun startGravelRing() {}

actual fun gravelRingModule(): Module = module {
    single { RingPreferences(get(), get()) } binds arrayOf(RingPreferences::class, BasePreferences::class)
    single { PrefsCollectionIndexStorage(get()) } bind CollectionIndexStorage::class
    single<KMPHaversineHacksDelegate> { GravelHacksDelegate(get()) }
    single<KMPHaversineDebugDelegate> { LocalRingDebugDelegate() }
    single {
        val prefs = get<RingPreferences>()
        KMPHaversineSatelliteManager(
            pairedSatelliteIdProvider = { prefs.ringPaired.value },
            debugDelegate = get(),
            hacksDelegate = get(),
            collectionIndexStorage = get(),
            hwVersion = RING_HW_MAJOR to RING_HW_MINOR,
            scope = CoroutineScope(Dispatchers.Default),
        )
    }
}
