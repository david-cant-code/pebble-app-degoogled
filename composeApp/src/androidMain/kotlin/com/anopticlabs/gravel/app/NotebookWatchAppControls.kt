package com.anopticlabs.gravel.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.ui.NotebookWatchAppValues
import com.anopticlabs.gravel.ui.WatchAppStatus
import io.ktor.client.HttpClient
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.dao.WatchPreference
import io.rebble.libpebblecommon.database.entity.QuickLaunchSetting
import io.rebble.libpebblecommon.database.entity.QuicklaunchWatchPref
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid

/** How a watch app action ended, as Settings and the guide show it, verbatim. */
internal object WatchAppLines {
    const val INSTALLED = "Installed."
    const val INSTALLED_NO_WATCH = "Installed. It reaches your watch the next time it connects."
    const val DOWNLOAD_FAILED = "Couldn't download the watch app. Try again."
    const val CHECK_FAILED = "The download did not pass Gravel's check. Nothing was installed."
    const val INSTALL_FAILED = "Couldn't install the watch app. Try again."
    const val REMOVED = "Removed."
    const val REMOVE_FAILED = "Couldn't remove the watch app. Try again."
    const val TRUSTED = "This copy can now save recordings."
    const val TRUST_FAILED = "Couldn't save that. Try again."
    const val COPY_CHANGED = "The watch app changed since you looked. Nothing was trusted."
    const val TRUST_ENDED = "This copy is no longer trusted."
}

/**
 * Gravel: the Notebook watch app's status and the actions Settings and the guide offer on it. One action runs at a
 * time. The status is read again when the app's locker entry changes, after an action that did not throw, and on
 * [reread].
 */
class NotebookWatchAppControls(
    private val releases: List<WatchAppRelease>,
    /** Emits whether the locker has the watch app, again on every change of its entry. */
    private val lockerEntry: Flow<Boolean>,
    private val servedSha256: suspend () -> String?,
    /** Whether the copy with this digest is the one the user marked as trusted. */
    private val marked: (String) -> Boolean,
    private val writeTrust: (String) -> Boolean,
    private val clearTrust: () -> Boolean,
    private val installRelease: suspend (WatchAppRelease) -> WatchAppInstallResult,
    private val removeApp: suspend () -> Boolean,
    private val watchConnected: () -> Boolean,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _values = MutableStateFlow(NotebookWatchAppValues(offered = releases.lastOrNull()?.version, offeredBytes = releases.lastOrNull()?.sizeBytes))
    private val shared = _values.asStateFlow()
    private val following = AtomicBoolean(false)
    private val oneRead = Mutex()

    /** The locker entry is followed from the first read on. */
    val values: StateFlow<NotebookWatchAppValues>
        get() {
            if (following.compareAndSet(false, true)) scope.launch { lockerEntry.collect { refreshOrLog() } }
            return shared
        }

    /** Reads the status again: the copy the phone caches can change with no change to the locker entry. */
    fun reread() {
        scope.launch { refreshOrLog() }
    }

    /** Puts away the result [line] once a screen has shown it. */
    fun resultShown(line: String) = _values.update { if (it.message == line) it.copy(message = null) else it }

    private suspend fun refreshOrLog() {
        try {
            refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "the Notebook watch app's status could not be read" }
        }
    }

    private suspend fun refresh() = oneRead.withLock {
        val here = lockerEntry.first()
        val sha256 = if (here) servedDigest() else null
        val trusted = sha256 != null && withContext(io) { marked(sha256) }
        _values.update {
            it.copy(
                status = watchAppStatus(here, sha256, trusted, releases),
                installedVersion = releases.firstOrNull { release -> release.sha256 == sha256 }?.version,
                sha256 = sha256,
            )
        }
    }

    /** A copy whose digest cannot be read is shown as one that cannot be checked. */
    private suspend fun servedDigest(): String? = try {
        servedSha256()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w(e) { "the Notebook watch app's digest could not be read" }
        null
    }

    /** After an install or a removal no copy stays marked as trusted. */
    private suspend fun forgetTrust() {
        if (!withContext(io) { clearTrust() }) logger.w { "the Notebook watch app's trust mark could not be removed" }
    }

    fun install() {
        val release = releases.lastOrNull() ?: return
        act(WatchAppLines.INSTALL_FAILED) {
            when (installRelease(release)) {
                WatchAppInstallResult.INSTALLED -> {
                    forgetTrust()
                    if (watchConnected()) WatchAppLines.INSTALLED else WatchAppLines.INSTALLED_NO_WATCH
                }
                WatchAppInstallResult.DOWNLOAD_FAILED -> WatchAppLines.DOWNLOAD_FAILED
                WatchAppInstallResult.CHECK_FAILED -> WatchAppLines.CHECK_FAILED
                WatchAppInstallResult.INSTALL_FAILED -> WatchAppLines.INSTALL_FAILED
            }
        }
    }

    fun remove() = act(WatchAppLines.REMOVE_FAILED) {
        if (removeApp()) {
            forgetTrust()
            WatchAppLines.REMOVED
        } else {
            WatchAppLines.REMOVE_FAILED
        }
    }

    /** Trusts the copy in the locker only while it is still the one with [sha256], the one the user was asked about. */
    fun trustCopy(sha256: String) = act(WatchAppLines.TRUST_FAILED) {
        when {
            servedDigest() != sha256 -> WatchAppLines.COPY_CHANGED
            withContext(io) { writeTrust(sha256) } -> WatchAppLines.TRUSTED
            else -> WatchAppLines.TRUST_FAILED
        }
    }

    fun stopTrusting() = act(WatchAppLines.TRUST_FAILED) {
        if (withContext(io) { clearTrust() }) WatchAppLines.TRUST_ENDED else WatchAppLines.TRUST_FAILED
    }

    private fun act(failed: String, action: suspend () -> String) {
        if (!claim()) return
        scope.launch {
            var line = failed
            try {
                line = action()
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.w(e) { "a Notebook watch app action failed" }
            } finally {
                _values.update { it.copy(busy = false, message = line) }
            }
        }
    }

    /** Takes the one action slot, which is [NotebookWatchAppValues.busy]; false while another action has it. */
    private fun claim(): Boolean {
        while (true) {
            val now = _values.value
            if (now.busy) return false
            if (_values.compareAndSet(now, now.copy(busy = true, message = null))) return true
        }
    }

    private companion object {
        val logger = Logger.withTag("NotebookWatchAppControls")
    }
}

/** The version the Notebook tab's update line names: null unless an update is available, [dismissed] was read (it is null until then) and names another version. */
fun watchAppUpdateToAnnounce(values: NotebookWatchAppValues, dismissed: String?): String? =
    values.offered.takeIf { values.status == WatchAppStatus.UPDATE_AVAILABLE && dismissed != null && it != dismissed }

/** The Notebook tab's line about a newer watch app, there until its OK is tapped for that version. */
@Composable
internal fun WatchAppUpdateLine(watchApp: StateFlow<NotebookWatchAppValues>, dismissed: StateFlow<String?>, dismiss: (String) -> Unit) {
    val values by watchApp.collectAsState()
    val seen by dismissed.collectAsState()
    val version = watchAppUpdateToAnnounce(values, seen) ?: return
    Column {
        Text(
            "Version $version of the Notebook watch app is available. Update it in Settings > Notebook.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { dismiss(version) }) { Text("OK") }
    }
}

/** The Quick Launch buttons in [prefs] that open the app [id]. */
internal fun quickLaunchButtonsFor(prefs: List<WatchPreference<*>>, id: Uuid): List<QuicklaunchWatchPref> =
    prefs.mapNotNull { item -> (item.pref as? QuicklaunchWatchPref)?.takeIf { (item.valueOrDefault() as? QuickLaunchSetting)?.uuid == id } }

/** Turns off each Quick Launch button that opens the watch app, then removes the app from the locker. */
internal suspend fun removeNotebookWatchApp(libPebble: LibPebble, id: Uuid = NOTEBOOK_WATCH_APP_UUID): Boolean {
    for (button in quickLaunchButtonsFor(libPebble.watchPrefs.first(), id)) {
        libPebble.setWatchPref(WatchPreference(button, QuickLaunchSetting(enabled = false, uuid = null)))
    }
    return libPebble.removeApp(id)
}

/** The controls over the app's own locker, release list, trust file and download client; [libPebble] and [httpClient] are looked up at first use. */
internal fun notebookWatchAppControls(context: Context, libPebble: () -> LibPebble, httpClient: () -> HttpClient): NotebookWatchAppControls {
    val trust = watchAppTrustFile(context.noBackupFilesDir)
    val installer by lazy {
        WatchAppInstaller(httpClient(), File(context.cacheDir, "notebook_watch_app"), { libPebble().sideloadAppWithoutLaunch(it, NOTEBOOK_WATCH_APP_UUID) })
    }
    return NotebookWatchAppControls(
        releases = SHIPPED_WATCH_APP_RELEASES,
        lockerEntry = flow { emitAll(libPebble().getLockerApp(NOTEBOOK_WATCH_APP_UUID).map { it != null }) },
        servedSha256 = { libPebble().cachedPbwSha256(NOTEBOOK_WATCH_APP_UUID) },
        marked = { trust.read() == it },
        writeTrust = trust::write,
        clearTrust = trust::clear,
        installRelease = { installer.install(it) },
        removeApp = { removeNotebookWatchApp(libPebble()) },
        watchConnected = { libPebble().watches.value.any { it is ConnectedPebbleDevice } },
    )
}
