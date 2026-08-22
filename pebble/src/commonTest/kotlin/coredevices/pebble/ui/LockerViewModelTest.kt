package coredevices.pebble.ui

import coredevices.database.AppstoreSource
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.Platform
import coredevices.pebble.account.UsersMeResponse
import coredevices.pebble.services.AppStoreHomeResult
import coredevices.pebble.services.CoreUsersMe
import coredevices.pebble.services.PebbleWebServices
import coredevices.pebble.services.StoreSearchResult
import coredevices.pebble.weather.WeatherResponse
import coredevices.util.WeatherUnit
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.web.LockerAddResponse
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.fail
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

/**
 * The debounce is the control that keeps a typed word from leaving the phone
 * as each of its prefixes, so these tests drive the view model on a virtual
 * clock and watch exactly when a search starts. No pager is ever collected,
 * so the web services double fails on any call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockerViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest
    fun installMain() = Dispatchers.setMain(dispatcher)

    @AfterTest
    fun restoreMain() = Dispatchers.resetMain()

    private fun search(vm: LockerViewModel, query: String, type: AppType = AppType.Watchface) =
        vm.searchStore(query, WatchType.BASALT, Platform.Android, type)

    @Test
    fun aTypedWordIsSearchedOnceTheTypingStops() = runTest(dispatcher) {
        val vm = LockerViewModel(NoNetworkWebServices, NoSourcesDao)
        for (prefix in listOf("c", "ca", "cat")) {
            search(vm, prefix)
            advanceTimeBy(100.milliseconds)
            assertNull(vm.activeSearch, "a prefix must not be searched while typing continues ($prefix)")
            assertNull(vm.searchPager)
        }
        advanceTimeBy(STORE_SEARCH_DEBOUNCE)
        runCurrent()
        assertEquals("cat", vm.activeSearch?.query)
        assertNotNull(vm.searchPager)
    }

    @Test
    fun emptyingTheFieldInsideTheWindowCancelsThePendingQuery() = runTest(dispatcher) {
        val vm = LockerViewModel(NoNetworkWebServices, NoSourcesDao)
        search(vm, "c")
        advanceTimeBy(100.milliseconds)
        vm.clearPendingSearch()
        advanceTimeBy(STORE_SEARCH_DEBOUNCE * 3)
        runCurrent()
        assertNull(vm.activeSearch)
        assertNull(vm.searchPager)
    }

    @Test
    fun repeatingASearchKeepsThePagerAndChangingTheTypeStartsANewOne() = runTest(dispatcher) {
        val vm = LockerViewModel(NoNetworkWebServices, NoSourcesDao)
        search(vm, "cat")
        advanceTimeBy(STORE_SEARCH_DEBOUNCE * 2)
        runCurrent()
        val first = assertNotNull(vm.searchPager)

        search(vm, "cat")
        advanceTimeBy(STORE_SEARCH_DEBOUNCE * 2)
        runCurrent()
        assertSame(first, vm.searchPager)

        search(vm, "cat", AppType.Watchapp)
        advanceTimeBy(STORE_SEARCH_DEBOUNCE * 2)
        runCurrent()
        assertNotSame(first, vm.searchPager)
        assertEquals(AppType.Watchapp, vm.activeSearch?.appType)
    }
}

private object NoNetworkWebServices : PebbleWebServices {
    private fun unexpected(): Nothing = fail("the debounce must never reach the web services")
    override suspend fun fetchUsersMePebble(): UsersMeResponse? = unexpected()
    override suspend fun fetchUsersMeCore(): CoreUsersMe? = unexpected()
    override suspend fun fetchPebbleLocker(): LockerModel? = unexpected()
    override suspend fun addToLegacyLocker(uuid: String): Boolean = unexpected()
    override suspend fun fetchAppStoreHome(type: AppType, hardwarePlatform: WatchType?, enabledOnly: Boolean, useCache: Boolean): List<AppStoreHomeResult> = unexpected()
    override suspend fun fetchPebbleAppStoreHomes(hardwarePlatform: WatchType?, useCache: Boolean): Map<AppType, AppStoreHomeResult?> = unexpected()
    override suspend fun searchAppStore(search: String, appType: AppType, watchType: WatchType, page: Int, pageSize: Int): List<Pair<AppstoreSource, StoreSearchResult>> = unexpected()
    override suspend fun addToLegacyLockerWithResponse(uuid: String): LockerAddResponse? = unexpected()
    override suspend fun addToLocker(entry: CommonAppType.Store, timelineToken: String?): Boolean = unexpected()
    override suspend fun removeFromLegacyLocker(id: Uuid): Boolean = unexpected()
    override suspend fun fetchUserHearts() = unexpected()
    override suspend fun getWeather(latitude: Double, longitude: Double, units: WeatherUnit, language: String): WeatherResponse? = unexpected()
}

private object NoSourcesDao : AppstoreSourceDao {
    private fun unexpected(): Nothing = fail("the debounce must never touch the source table")
    override suspend fun insertSource(source: AppstoreSource): Long = unexpected()
    override fun getAllSources(): Flow<List<AppstoreSource>> = unexpected()
    override fun getAllEnabledSourcesFlow(): Flow<List<AppstoreSource>> = unexpected()
    override suspend fun getAllEnabledSources(): List<AppstoreSource> = unexpected()
    override suspend fun deleteSourceById(sourceId: Int) = unexpected()
    override suspend fun setSourceEnabled(sourceId: Int, isEnabled: Boolean) = unexpected()
    override suspend fun getSourceById(sourceId: Int): AppstoreSource? = unexpected()
}
