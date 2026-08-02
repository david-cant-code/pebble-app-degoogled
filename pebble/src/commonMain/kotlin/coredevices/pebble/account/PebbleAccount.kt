package coredevices.pebble.account

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import coredevices.firestore.UsersDao
import coredevices.pebble.services.PebbleWebServices
import coredevices.util.security.EncryptedStringSetting
import coredevices.util.security.SecretCipher
import io.rebble.libpebblecommon.connection.TokenProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

interface PebbleAccount {
    val loggedIn: StateFlow<String?>
    val devToken: StateFlow<String?>

    suspend fun setToken(token: String?, bootUrl: String?)
    suspend fun setDevPortalId()
}

class RealPebbleAccount(
    private val settings: Settings,
    private val pebbleWebServices: PebbleWebServices,
    private val bootConfigProvider: BootConfigProvider,
    private val usersDao: UsersDao,
    secretCipher: SecretCipher,
) : PebbleAccount {
    private val logger = Logger.withTag("PebbleAccount")

    // The account bearer token is the highest-value secret the app persists, and nothing else in
    // the app encrypts anything at rest, so it would otherwise sit in plaintext SharedPreferences
    // and travel in every backup and device transfer.
    private val tokenSetting = EncryptedStringSetting(settings, secretCipher, TOKEN_KEY)
    private val _loggedIn = MutableStateFlow(getToken())
    override val loggedIn = _loggedIn.asStateFlow()
    private val _devToken = MutableStateFlow(getDevPortalId())
    override val devToken = _devToken.asStateFlow()

    override suspend fun setToken(token: String?, bootUrl: String?) {
        logger.d("setToken")
        tokenSetting.set(token)
        _loggedIn.value = token
        bootConfigProvider.setUrl(bootUrl)
        setDevPortalId()
    }

    override suspend fun setDevPortalId() {
        val devPortalId = pebbleWebServices.fetchUsersMePebble()?.users?.firstOrNull()?.id
        if (devPortalId == null) {
            logger.e { "couldn't fetch dev portal id" }
            return
        }
        settings.putString(DEV_KEY, devPortalId)
        usersDao.initUserDevToken(devPortalId)
        _devToken.value = devPortalId
    }

    private fun getToken(): String? = tokenSetting.get()
    private fun getDevPortalId(): String? = settings.getStringOrNull(DEV_KEY)

    companion object {
        private val TOKEN_KEY = "account_token_key"
        private val DEV_KEY = "dev_token_key"
    }
}

class PebbleTokenProvider(
    private val usersDao: UsersDao,
    private val pebbleAccount: PebbleAccount,
) : TokenProvider {
    override suspend fun getDevToken(): String? {
        val userConfig = usersDao.user.firstOrNull()
        return userConfig?.user?.rebbleUserToken ?: userConfig?.user?.pebbleUserToken ?: pebbleAccount.devToken.value
    }
}

@Serializable
data class UsersMeResponse(
    val users: List<UsersMeUser>
)

@Serializable
data class UsersMeUser(
    val id: String,
    @SerialName("voted_ids")
    val votedIds: List<String>,
)