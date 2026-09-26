package coredevices.pebble.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.pkjs.DeniedPkjsNotice
import com.anopticlabs.gravel.pkjs.deniedPkjsNotice
import com.anopticlabs.gravel.pkjs.deniedPkjsSwitchApplies
import com.anopticlabs.gravel.pkjs.networkDenyEnforcement
import com.anopticlabs.gravel.ui.KnownIssuesLink
import coredevices.util.KnownIssue
import coredevices.pebble.rememberLibPebble
import coredevices.ui.M3Dialog
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.locker.PermissionSetting
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid
import org.koin.compose.getKoin

/**
 * Fork feature: Settings > Apps > Watch App Permissions.
 *
 * Third-party watchapps/watchfaces run companion JavaScript on the phone (PebbleKit
 * JS in a WebView), through which they can reach the internet and the phone's GPS with
 * no involvement from the watch. Upstream exposed neither disclosure nor control over
 * that; this screen is the control surface. It holds the global defaults (what an app
 * with no explicit choice inherits) and a list of installed apps; the per-app tri-state
 * controls themselves live on each app's page (and are shared via
 * [WatchappPermissionControls]).
 * It also holds the classic PebbleKit and PebbleKit 2 toggles, and, where it applies, the
 * switch for Network-denied apps' code (WatchConfig.deniedPkjsWithoutPrimaryLayer).
 */
@Composable
fun WatchappPermissionsScreen(nav: NavBarNav, topBarParams: TopBarParams) {
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {}
        topBarParams.title("Watch App Permissions")
    }

    val libPebble = rememberLibPebble()
    val config by libPebble.config.collectAsState()
    val watchConfig = config.watchConfig
    val koin = getKoin()
    val networkDenyEnforcement = remember { koin.networkDenyEnforcement() }

    // From the config at the tap: two switches tapped within one frame would otherwise each
    // write back the other's old value from the composition's snapshot.
    fun updateWatchConfig(change: (WatchConfig) -> WatchConfig) {
        val current = libPebble.config.value
        libPebble.updateConfig(current.copy(watchConfig = change(current.watchConfig)))
    }

    // Installed watchapps + watchfaces (system apps excluded: they are first-party and
    // do not run third-party phone-side code). Limit is generous; the locker is small.
    val apps by remember {
        combine(
            libPebble.getLocker(AppType.Watchapp, null, 500),
            libPebble.getLocker(AppType.Watchface, null, 500),
        ) { watchapps, watchfaces ->
            (watchapps + watchfaces)
                .filterIsInstance<LockerWrapper.NormalApp>()
                .sortedBy { it.properties.title.lowercase() }
        }
    }.collectAsState(emptyList())

    var showClassicInfo by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Some watchfaces and apps run code inside Gravel to fetch things like " +
                        "weather. The defaults and per-app choices below control what that " +
                        "code can reach. Companion apps on your phone are separate Android " +
                        "apps with their own permissions; the switches further down decide " +
                        "whether Gravel talks to them at all. Turning access off can stop " +
                        "features that rely on it, such as third-party weather, from working " +
                        "for those apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "Default for all apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Applied to any app you haven't set individually.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                GlobalDefaultToggle(
                    label = "Internet access",
                    checked = watchConfig.watchappDefaultNetworkAllowed,
                    onCheckedChange = { allowed ->
                        updateWatchConfig { it.copy(watchappDefaultNetworkAllowed = allowed) }
                    },
                )
                GlobalDefaultToggle(
                    label = "Location",
                    checked = watchConfig.watchappDefaultLocationAllowed,
                    onCheckedChange = { allowed ->
                        updateWatchConfig { it.copy(watchappDefaultLocationAllowed = allowed) }
                    },
                )
                if (networkDenyEnforcement.deniedPkjsSwitchApplies) {
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Apps with internet access off",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DescribedToggle(
                        label = "Run app code with internet access off",
                        description = "Gravel blocks internet access for watchfaces and apps with several " +
                            "layers. One of them isn't active on this phone, so real-time connections " +
                            "(the kind video calls use) depend on a single layer, in an up-to-date Android " +
                            "System WebView, instead of two. With this on, apps whose internet access is " +
                            "off still run their code inside Gravel under the remaining layers. With it " +
                            "off, that code doesn't run, and features that depend on it won't work. On by " +
                            "default.",
                        checked = watchConfig.deniedPkjsWithoutPrimaryLayer,
                        onCheckedChange = { on ->
                            updateWatchConfig { it.copy(deniedPkjsWithoutPrimaryLayer = on) }
                        },
                    )
                    KnownIssuesLink(KnownIssue.REAL_TIME_CONNECTIONS_ON_ONE_LAYER)
                }
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Companion apps on your phone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { showClassicInfo = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "About classic PebbleKit")
                    }
                }
                Text(
                    "Some watchapps talk to a separate app on your phone through PebbleKit. " +
                        "Notifications, replies and dictation don't use it and keep working " +
                        "with both switches off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                DescribedToggle(
                    label = "Classic PebbleKit",
                    description = "For apps made for the original Pebble phone app. While this " +
                        "is on, any app on your phone can receive what any watchapp or " +
                        "watchface sends from the watch, can send messages to them, and can " +
                        "start or stop watchapps; watchapps that name a PebbleKit 2 companion " +
                        "use PebbleKit 2 instead. Off by default; turn it on if a companion " +
                        "app of yours stops receiving watch data.",
                    checked = watchConfig.classicPebbleKitEnabled,
                    onCheckedChange = { enabled ->
                        updateWatchConfig { it.copy(classicPebbleKitEnabled = enabled) }
                    },
                )
                DescribedToggle(
                    label = "PebbleKit 2",
                    description = "For newer companion apps. Only an app that an installed " +
                        "watchapp names can use it, to exchange data with that watchapp and " +
                        "add timeline pins; such an app can also see which watchface or app " +
                        "is running and the watch model and firmware.",
                    checked = watchConfig.pebbleKit2Enabled,
                    onCheckedChange = { enabled ->
                        updateWatchConfig { it.copy(pebbleKit2Enabled = enabled) }
                    },
                )
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text(
                    "Individual apps",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (apps.isEmpty()) {
            item {
                Text(
                    "No watchapps or watchfaces installed yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(apps, key = { it.properties.id.toString() }) { app ->
            WatchappPermissionListRow(
                app = app,
                libPebble = libPebble,
                onClick = {
                    // Reuse the app's own detail page, which hosts the per-app controls.
                    nav.navigateTo(
                        PebbleNavBarRoutes.LockerAppRoute(
                            uuid = app.properties.id.toString(),
                            storedId = null,
                            storeSource = null,
                        ),
                    )
                },
            )
        }
    }

    if (showClassicInfo) {
        M3Dialog(
            onDismissRequest = { showClassicInfo = false },
            title = { Text("About classic PebbleKit") },
            buttons = {
                TextButton(onClick = { showClassicInfo = false }) { Text("Close") }
            },
            scrollableContent = true,
        ) {
            Text(CLASSIC_PEBBLEKIT_INFO)
        }
    }
}

private const val CLASSIC_PEBBLEKIT_INFO =
    "Classic PebbleKit is how apps made for the original Pebble phone app talk to " +
        "watchapps. It works by broadcasting: Gravel hands what the watch sends to every " +
        "app on the phone that listens, because classic PebbleKit names no recipient and " +
        "defines no permission that delivery could be limited to.\n\n" +
        "Every watchapp and watchface that does not name a PebbleKit 2 companion gets a " +
        "classic PebbleKit session when it runs on the watch. That includes watchfaces " +
        "that only run code inside Gravel and apps with no companion at all. While classic " +
        "PebbleKit is on, whatever those apps send from the watch can be received by any " +
        "app on your phone, and any app can send messages to them.\n\n" +
        "It is off by default for that reason. Turn it on if you use a companion app made " +
        "for the original Pebble app; such an app stops receiving watch data while this is " +
        "off. Notifications, replies and dictation do not use PebbleKit and work either way."

@Composable
private fun DescribedToggle(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun GlobalDefaultToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun WatchappPermissionListRow(
    app: LockerWrapper.NormalApp,
    libPebble: LibPebble,
    onClick: () -> Unit,
) {
    val uuid = app.properties.id
    val network by libPebble.watchappPermissionGranted(uuid, LockerAppPermissionType.Network)
        .collectAsState(false)
    val location by libPebble.watchappPermissionGranted(uuid, LockerAppPermissionType.Location)
        .collectAsState(false)
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(app.properties.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                buildString {
                    append("Internet: ")
                    append(if (network) "On" else "Off")
                    append("   •   Location: ")
                    append(if (location) "On" else "Off")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/**
 * Shared per-app permission controls, embedded on an app's detail page. Renders the two
 * tri-state selectors (Default / Allow / Deny) plus a disclosure of what they cover: the app's
 * PebbleKit JS, not a separate companion app. [WatchappPermissionsScreen] deliberately reuses
 * the app detail page rather than duplicating these controls.
 */
@Composable
fun WatchappPermissionControls(uuid: Uuid, modifier: Modifier = Modifier) {
    val libPebble = rememberLibPebble()
    val koin = getKoin()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "This app's access on your phone",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "If this app runs code inside Gravel (many watchfaces do, to fetch weather), " +
                "these control what that code can reach. They do not cover a separate " +
                "companion app on your phone, which uses its own Android permissions. " +
                "Turning internet off can stop features that need it, such as third-party " +
                "weather, from working.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        WatchappPermissionSelector(
            uuid = uuid,
            type = LockerAppPermissionType.Network,
            label = "Internet access",
            libPebble = libPebble,
        )
        val networkDenyEnforcement = remember { koin.networkDenyEnforcement() }
        // Counts as on until the stored grant arrives, so the notice does not flash.
        val networkGranted by libPebble.watchappPermissionGranted(uuid, LockerAppPermissionType.Network)
            .collectAsState(true)
        val config by libPebble.config.collectAsState()
        // The locker entry does not record whether the app has PebbleKit JS, so the doesn't-run
        // wording is conditional.
        val notice = deniedPkjsNotice(networkGranted, networkDenyEnforcement, config.watchConfig.deniedPkjsWithoutPrimaryLayer)
        if (notice != null) {
            val doesNotRun = "If this app runs code inside Gravel, it doesn't run while internet " +
                "access is off, so features that depend on it won't work."
            val switchPointer = " Settings > Apps > Watch App Permissions has a switch for this."
            Text(
                when (notice) {
                    DeniedPkjsNotice.RunsWithoutPrimaryLayer ->
                        "Gravel blocks this app's internet access with several layers. One of them " +
                            "isn't active on this phone, so real-time connections (the kind video " +
                            "calls use) depend on a single layer, in an up-to-date Android System " +
                            "WebView, instead of two. Gravel can't double-check that layer." +
                            switchPointer
                    DeniedPkjsNotice.DoesNotRunSwitchOff -> doesNotRun + switchPointer
                    DeniedPkjsNotice.DoesNotRun -> doesNotRun
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            KnownIssuesLink(
                if (notice == DeniedPkjsNotice.DoesNotRun) KnownIssue.UDP_FILTER_REFUSED
                else KnownIssue.REAL_TIME_CONNECTIONS_ON_ONE_LAYER,
            )
        }
        Spacer(Modifier.height(12.dp))
        WatchappPermissionSelector(
            uuid = uuid,
            type = LockerAppPermissionType.Location,
            label = "Location",
            libPebble = libPebble,
        )
    }
}

@Composable
private fun WatchappPermissionSelector(
    uuid: Uuid,
    type: LockerAppPermissionType,
    label: String,
    libPebble: LibPebble,
) {
    val scope = rememberCoroutineScope()
    val setting by libPebble.watchappPermissionSetting(uuid, type)
        .collectAsState(PermissionSetting.FollowGlobal)
    // Resolved by the permission resolver, which owns the type-to-config-field
    // mapping, so this label cannot drift from what FollowGlobal actually grants.
    val globalAllowed by libPebble.globalDefault(type).collectAsState(false)
    // The "Default" option names what it currently resolves to, so the choice is honest
    // about the effect (e.g. "Default (Off)") rather than hiding it behind a word.
    val options = listOf(
        PermissionSetting.FollowGlobal to "Default (${if (globalAllowed) "On" else "Off"})",
        PermissionSetting.Allow to "Allow",
        PermissionSetting.Deny to "Deny",
    )
    Text(label, style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(4.dp))
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, text) ->
            SegmentedButton(
                selected = setting == value,
                onClick = { scope.launch { libPebble.setWatchappPermission(uuid, type, value) } },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(text)
            }
        }
    }
}
