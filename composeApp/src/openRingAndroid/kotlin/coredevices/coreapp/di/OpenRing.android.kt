package coredevices.coreapp.di

import android.content.Context
import coredevices.coreapp.indexfeed.AndroidIndexArchiveSharer
import coredevices.coreapp.indexfeed.AndroidIndexAudioPlayer
import coredevices.coreapp.indexfeed.FileIndexRecordingStore
import coredevices.coreapp.indexfeed.IndexArchiveSharer
import coredevices.coreapp.indexfeed.HybridIndexTranscriber
import coredevices.coreapp.indexfeed.IndexAudioPlayer
import coredevices.coreapp.indexfeed.IndexFeedViewModel
import coredevices.coreapp.indexfeed.IndexRecordingStore
import coredevices.coreapp.indexfeed.IndexTranscriber
import coredevices.coreapp.indexfeed.IndexTranscriptionPipeline
import coredevices.haversine.CollectionIndexStorage
import coredevices.haversine.KMPHaversineDebugDelegate
import coredevices.haversine.KMPHaversineHacksDelegate
import coredevices.haversine.KMPHaversineSatelliteManager
import coredevices.libindex.IndexRingClient
import coredevices.libindex.LibIndex
import coredevices.libindex.database.BasePreferences
import coredevices.libindex.database.PrefsCollectionIndexStorage
import coredevices.libindex.database.repository.RingIterationStore
import coredevices.libindex.di.libIndexModule
import coredevices.util.Permission
import coredevices.util.PermissionRequester
import coredevices.util.transcription.TranscriptionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map
import kotlinx.io.files.Path
import org.koin.core.context.GlobalContext
import org.koin.core.module.Module
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.module

actual fun startGravelRing() {
    val koin = GlobalContext.get()
    val requester = koin.get<PermissionRequester>()
    koin.get<LibIndex>().init(requester.missingPermissions.map { Permission.Bluetooth !in it })
    koin.get<GravelRingSync>().start()
}

actual fun gravelRingModule(): Module = module {
    includes(libIndexModule)
    single { RingPreferences(get(), get()) } binds arrayOf(RingPreferences::class, BasePreferences::class)
    single { PrefsCollectionIndexStorage(get()) } bind CollectionIndexStorage::class
    single<KMPHaversineHacksDelegate> { GravelHacksDelegate(get()) }
    single<KMPHaversineDebugDelegate> { LocalRingDebugDelegate() }
    single {
        FileIndexRecordingStore(Path(get<Context>().filesDir.absolutePath, "index-recordings"))
    } binds arrayOf(IndexRecordingStore::class, RingIterationStore::class)
    single<IndexTranscriber> { HybridIndexTranscriber(get<TranscriptionService>()) }
    single {
        IndexTranscriptionPipeline(
            get(),
            get(),
            CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
    }
    single { AndroidIndexAudioPlayer() } bind IndexAudioPlayer::class
    single { IndexFeedViewModel(get(), get(), get()) }
    single { AndroidIndexArchiveSharer(get()) } bind IndexArchiveSharer::class
    single {
        val prefs = get<RingPreferences>()
        KMPHaversineSatelliteManager(
            pairedSatelliteIdProvider = { prefs.ringPaired.value },
            debugDelegate = get(),
            hacksDelegate = get(),
            collectionIndexStorage = get(),
            context = get<Context>(),
            hwVersion = RING_HW_MAJOR to RING_HW_MINOR,
            CoroutineScope(Dispatchers.Default),
        )
    }
    single { GravelIndexClient() } bind IndexRingClient::class
    single { GravelRingSync(get(), get(), get(), get(), get()) }
}
