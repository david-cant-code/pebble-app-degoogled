package com.anopticlabs.gravel.firmware

import CoreNav
import CoreRoute
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coreapp.util.generated.resources.Res
import coreapp.util.generated.resources.back
import coredevices.pebble.firmware.GithubReleases
import coredevices.pebble.firmware.VerifiedFirmwareInstaller
import coredevices.pebble.firmware.isCoreDevice
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.ui.installStateFor
import coredevices.ui.PebbleElevatedButton
import io.rebble.libpebblecommon.connection.CommonConnectedDevice
import io.rebble.libpebblecommon.connection.PebbleDevice
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

@Serializable
data class FirmwarePickerRoute(
    /** Null opens the picker for the first connected Core watch. */
    val identifier: String? = null,
) : CoreRoute

/** The picker serves connected Core watches, whose updates Gravel checks against Core's GitHub releases. */
fun PebbleDevice.firmwarePickerServes(): Boolean =
    this is CommonConnectedDevice && watchInfo.platform.isCoreDevice()

const val EXTRA_FIRMWARE_PICKER_WATCH = "com.anopticlabs.gravel.extra.FIRMWARE_PICKER_WATCH"
const val EXTRA_FIRMWARE_PICKER_TOKEN = "com.anopticlabs.gravel.extra.FIRMWARE_PICKER_TOKEN"

/**
 * A secret of this process, carried by the PebbleOS update notification's tap
 * intent so that the tap can open the picker while an intent another app
 * composes cannot.
 */
object FirmwareNotificationToken {
    val value: String by lazy { newFirmwareNotificationToken() }
}

// Uuid.random draws from java.security.SecureRandom on the JVM
// (kotlin-stdlib 2.4.10, kotlin.uuid secureRandomBytes).
internal fun newFirmwareNotificationToken(): String = Uuid.random().toHexString()

/**
 * The picker a notification tap opens: only for this process's token, a
 * connected Core watch, and a launch that is not a relaunch from Recents
 * (which replays the notification's intent). Otherwise null, and the intent is
 * handled as a link.
 */
fun firmwarePickerRouteForNotification(
    readExtra: (String) -> String?,
    launchedFromHistory: Boolean,
    isConnectedCoreWatch: (String) -> Boolean,
    expectedToken: String = FirmwareNotificationToken.value,
): FirmwarePickerRoute? {
    val (watch, token) = try {
        readExtra(EXTRA_FIRMWARE_PICKER_WATCH) to readExtra(EXTRA_FIRMWARE_PICKER_TOKEN)
    } catch (e: RuntimeException) {
        // Before Android 13, reading one extra unparcels them all, and a value
        // of a class this app cannot load throws (android12-release,
        // BaseBundle.initializeFromParcelLocked and Parcel.readParcelableCreator).
        return null
    }
    if (launchedFromHistory || watch == null || token != expectedToken || !isConnectedCoreWatch(watch)) return null
    return FirmwarePickerRoute(watch)
}

private const val CHANGELOG_URL = "https://ndocs.repebble.com/pebbleos-changelog"

private val checkedDateFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day(padding = Padding.NONE)
    chars(", ")
    year()
}

@Composable
fun FirmwarePickerScreen(identifier: String?, coreNav: CoreNav) {
    val libPebble = rememberLibPebble()
    val watches by libPebble.watches.collectAsState()
    val watch = watches.firstOrNull {
        (identifier == null || it.identifier.asString == identifier) && it.firmwarePickerServes()
    } as CommonConnectedDevice?
    // Kept across disconnects so a reconnect on the same firmware reuses its list.
    val loaded = remember { mutableStateMapOf<ChoicesKey, FirmwareBuildChoicesResult>() }
    val uriHandler = LocalUriHandler.current
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = coreNav::goBack) {
                        Icon(Icons.AutoMirrored.Default.ArrowBack, contentDescription = stringResource(Res.string.back))
                    }
                },
                title = { Text("Choose a PebbleOS build") },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            if (watch == null) {
                Text("Connect a Core watch to choose a PebbleOS build.")
            } else {
                BuildChoices(watch, loaded, coreNav)
            }
            TextButton(onClick = { uriHandler.openUri(CHANGELOG_URL) }) {
                Text("Open Core's PebbleOS changelog")
                Spacer(Modifier.width(6.dp))
                Icon(Icons.AutoMirrored.Default.Launch, contentDescription = null)
            }
        }
    }
}

@Composable
private fun BuildChoices(
    watch: CommonConnectedDevice,
    loaded: SnapshotStateMap<ChoicesKey, FirmwareBuildChoicesResult>,
    coreNav: CoreNav,
) {
    val githubReleases: GithubReleases = koinInject()
    val installer: VerifiedFirmwareInstaller = koinInject()
    val running = watch.watchInfo.runningFwVersion
    val key = ChoicesKey(watch.identifier.asString, running.stringVersion, running.isRecovery)
    var attempt by remember { mutableIntStateOf(0) }
    LaunchedEffect(key, attempt) {
        if (key !in loaded) loaded[key] = githubReleases.getBuildChoices(watch.watchInfo)
    }
    val result = loaded[key]
    val forkInstallState by installer.installStateFor(watch)
    val installing = forkInstallState.isActive || watch.firmwareUpdateState !is FirmwareUpdateStatus.NotInProgress

    Text(
        if (running.isRecovery) {
            "${watch.displayName()} is in recovery mode"
        } else {
            "${watch.displayName()} runs PebbleOS ${running.stringVersion}"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Gravel recommends the newest build on Core's public PebbleOS changelog. Builds not on " +
            "the changelog are on GitHub, but Core has not listed them.",
    )
    Spacer(Modifier.height(8.dp))
    when (val current = result) {
        null -> CircularProgressIndicator(Modifier.padding(16.dp))
        is FirmwareBuildChoicesResult.Failed -> {
            Text(current.message, color = MaterialTheme.colorScheme.error)
            if (current.retryable) {
                TextButton(onClick = {
                    loaded.remove(key)
                    attempt++
                }) { Text("Try again") }
            }
        }
        is FirmwareBuildChoicesResult.Success -> {
            var selectedUrl by remember(current) {
                mutableStateOf(current.builds.firstOrNull { it.isRecommended }?.update?.url)
            }
            if (current.builds.isEmpty()) {
                Text("No newer PebbleOS build is available for this watch.")
            }
            current.builds.forEach { choice ->
                val selected = choice.update.url == selectedUrl
                ListItem(
                    headlineContent = { Text("PebbleOS ${choice.update.version.stringVersion}") },
                    supportingContent = choice.listingText()?.let { text -> { Text(text) } },
                    leadingContent = { RadioButton(selected = selected, onClick = null) },
                    modifier = Modifier.selectable(
                        selected = selected,
                        onClick = { selectedUrl = choice.update.url },
                        role = Role.RadioButton,
                    ),
                )
            }
            val selected = current.builds.firstOrNull { it.update.url == selectedUrl }
            if (installing) {
                Text("A PebbleOS install is in progress.")
            }
            if (selected != null) {
                PebbleElevatedButton(
                    text = "Install PebbleOS ${selected.update.version.stringVersion}",
                    onClick = {
                        installer.install(watch, selected.update)
                        coreNav.goBack()
                    },
                    enabled = !installing,
                    primaryColor = true,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            val checkedAt = current.listCheckedAt
            Text(
                if (checkedAt != null) {
                    val date = checkedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date
                    "Changelog list last checked ${date.format(checkedDateFormat)}"
                } else {
                    "Gravel couldn't check which builds are on Core's changelog."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** A list answers only for the watch and firmware it was loaded for. */
private data class ChoicesKey(val identifier: String, val runningVersion: String, val isRecovery: Boolean)

private fun FirmwareBuildChoice.listingText(): String? = when (listing) {
    ChangelogListing.Listed -> if (isRecommended) "On Core's changelog, recommended" else "On Core's changelog"
    ChangelogListing.NotListed -> "Not on Core's changelog"
    ChangelogListing.Unknown -> null
}
