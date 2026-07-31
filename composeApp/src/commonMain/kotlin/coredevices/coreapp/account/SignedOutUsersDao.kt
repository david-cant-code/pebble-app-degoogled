package coredevices.coreapp.account

import coredevices.firestore.EncryptionInfo
import coredevices.firestore.PebbleUser
import coredevices.firestore.UsersDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Fork-owned UsersDao bound at the Koin seam in place of upstream's
 * UsersDaoImpl. With the Firebase stubs the app is permanently signed out,
 * and UsersDaoImpl's startup observer is actively harmful in that world: on
 * a device that ever had a Firebase account (any install upgraded from a
 * pre-strip build has the hadAnonymousAccount marker set), it parks in an
 * endless "waiting for auth restoration" loop that logs a warning every
 * minute, forever. Replacing the DAO here, rather than teaching UsersDaoImpl
 * about the fork, keeps the upstream file untouched for cheap merges.
 *
 * Semantics: [user] is a hot flow holding null (the signed-out state
 * consumers already handle), [loginEvents] never fires, every updater
 * no-ops. Both flows stay open like the SharedFlows in UsersDaoImpl, so
 * combine()-style consumers keep their subscription semantics.
 */
object SignedOutUsersDao : UsersDao {
    override val user: Flow<PebbleUser?> = MutableStateFlow(null)

    override val loginEvents: Flow<PebbleUser> = MutableSharedFlow<PebbleUser>().asSharedFlow()

    override suspend fun updateTodoBlockId(todoBlockId: String) {}

    override suspend fun updateNotionPageId(pageId: String) {}

    override suspend fun initUserDevToken(rebbleUserToken: String?) {}

    override suspend fun updateLastConnectedWatch(serial: String) {}

    override suspend fun updateRingLifetimeCollectionCount(serial: String, count: Int) {}

    override suspend fun updateEncryptionInfo(info: EncryptionInfo) {}

    override fun init() {}
}
