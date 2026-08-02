package coredevices.pebble.account

import com.russhwolf.settings.MapSettings
import coredevices.database.AppstoreSource
import coredevices.firestore.PebbleUser
import coredevices.firestore.UsersDao
import coredevices.pebble.services.AppStoreHomeResult
import coredevices.pebble.services.CoreUsersMe
import coredevices.pebble.services.PebbleWebServices
import coredevices.pebble.services.StoreSearchResult
import coredevices.pebble.ui.CommonAppType
import coredevices.pebble.weather.WeatherResponse
import coredevices.util.WeatherUnit
import coredevices.util.security.DecryptResult
import coredevices.util.security.SecretCipher
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.web.LockerAddResponse
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/**
 * Pins that constructing the account performs no cryptography. The class is built on the main
 * thread during DI graph init in Application.onCreate, and the stored token is encrypted, so an
 * eager read would block startup on Keystore IPC; the read must happen on first use instead,
 * while staying synchronously available so no consumer sees a transient signed-out state.
 */
class RealPebbleAccountTest {

    @Test
    fun `construction does not decrypt the stored token`() {
        val cipher = CountingCipher()
        val settings = MapSettings().apply { putString("account_token_key", "enc1:nekot") }

        buildAccount(settings, cipher)

        assertEquals(0, cipher.decrypts, "construction must not perform Keystore work")
    }

    @Test
    fun `the token is synchronously available on first read and cached after`() {
        val cipher = CountingCipher()
        val settings = MapSettings().apply { putString("account_token_key", "enc1:nekot") }
        val account = buildAccount(settings, cipher)

        assertEquals("token", account.loggedIn.value)
        assertEquals("token", account.loggedIn.value)
        assertEquals(1, cipher.decrypts, "the stored token is decrypted once, not per read")
    }

    private fun buildAccount(settings: MapSettings, cipher: SecretCipher) = RealPebbleAccount(
        settings = settings,
        pebbleWebServices = FakeWebServices,
        bootConfigProvider = FakeBootConfig,
        usersDao = FakeUsersDao,
        secretCipher = cipher,
    )

    /** Reverses strings, like the storage-wrapper tests' fake, and counts decrypts. */
    private class CountingCipher : SecretCipher {
        var decrypts = 0

        override fun encrypt(plaintext: String): String = plaintext.reversed()

        override fun decrypt(stored: String): DecryptResult {
            decrypts++
            return DecryptResult.Success(stored.reversed())
        }
    }

    private object FakeBootConfig : BootConfigProvider {
        override suspend fun setUrl(url: String?) {}
        override fun getUrl(): String? = null
        override suspend fun getBootConfig(): BootConfig? = null
    }

    private object FakeUsersDao : UsersDao {
        override val user: Flow<PebbleUser?> = flow { emit(null) }
        override val loginEvents: Flow<PebbleUser> = flow {}
        override suspend fun updateTodoBlockId(todoBlockId: String) {}
        override suspend fun initUserDevToken(rebbleUserToken: String?) {}
        override suspend fun updateLastConnectedWatch(serial: String) {}
        override suspend fun updateRingLifetimeCollectionCount(serial: String, count: Int) {}
        override fun init() {}
    }

    private object FakeWebServices : PebbleWebServices {
        override suspend fun fetchUsersMePebble(): UsersMeResponse? = null
        override suspend fun fetchUsersMeCore(): CoreUsersMe? = null
        override suspend fun fetchPebbleLocker(): LockerModel? = null
        override suspend fun addToLegacyLocker(uuid: String): Boolean = false
        override suspend fun fetchAppStoreHome(
            type: AppType,
            hardwarePlatform: WatchType?,
            enabledOnly: Boolean,
            useCache: Boolean,
        ): List<AppStoreHomeResult> = emptyList()

        override suspend fun fetchPebbleAppStoreHomes(
            hardwarePlatform: WatchType?,
            useCache: Boolean,
        ): Map<AppType, AppStoreHomeResult?> = emptyMap()

        override suspend fun searchAppStore(
            search: String,
            appType: AppType,
            watchType: WatchType,
            page: Int,
            pageSize: Int,
        ): List<Pair<AppstoreSource, StoreSearchResult>> = emptyList()

        override suspend fun addToLegacyLockerWithResponse(uuid: String): LockerAddResponse? = null
        override suspend fun addToLocker(entry: CommonAppType.Store, timelineToken: String?): Boolean = false
        override suspend fun removeFromLegacyLocker(id: Uuid): Boolean = false
        override suspend fun fetchUserHearts() {}
        override suspend fun getWeather(
            latitude: Double,
            longitude: Double,
            units: WeatherUnit,
            language: String,
        ): WeatherResponse? = null
    }
}
