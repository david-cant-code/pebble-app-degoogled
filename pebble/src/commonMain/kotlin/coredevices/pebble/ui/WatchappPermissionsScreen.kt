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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coredevices.pebble.rememberLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.locker.PermissionSetting
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

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

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "Some watchfaces and apps run code on your phone to fetch things like " +
                        "weather. These settings control what that code can reach. Turning " +
                        "access off can stop features that rely on it, such as third-party " +
                        "weather, from working for those apps.",
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
                        libPebble.updateConfig(
                            config.copy(watchConfig = watchConfig.copy(watchappDefaultNetworkAllowed = allowed)),
                        )
                    },
                )
                GlobalDefaultToggle(
                    label = "Location",
                    checked = watchConfig.watchappDefaultLocationAllowed,
                    onCheckedChange = { allowed ->
                        libPebble.updateConfig(
                            config.copy(watchConfig = watchConfig.copy(watchappDefaultLocationAllowed = allowed)),
                        )
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
 * tri-state selectors (Default / Allow / Deny) plus an honest disclosure of what phone-side
 * network access means. [WatchappPermissionsScreen] deliberately reuses the app detail page
 * rather than duplicating these controls.
 */
@Composable
fun WatchappPermissionControls(uuid: Uuid, modifier: Modifier = Modifier) {
    val libPebble = rememberLibPebble()
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "This app's access on your phone",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            "If this app runs code on your phone (many watchfaces do, to fetch weather), " +
                "these control what it can reach. Turning internet off also stops it sending " +
                "your data to outside servers, but can stop features that need it, such as " +
                "third-party weather, from working.",
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
    val scope = rememberCoroutineScopeForPermissions()
    val setting by libPebble.watchappPermissionSetting(uuid, type)
        .collectAsState(PermissionSetting.FollowGlobal)
    val config by libPebble.config.collectAsState()
    val globalAllowed = when (type) {
        LockerAppPermissionType.Network -> config.watchConfig.watchappDefaultNetworkAllowed
        LockerAppPermissionType.Location -> config.watchConfig.watchappDefaultLocationAllowed
    }
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

// Small local helper so the two composables above share one scope-obtaining call without
// each importing the same set of runtime symbols; keeps the imports at the top tidy.
@Composable
private fun rememberCoroutineScopeForPermissions() =
    androidx.compose.runtime.rememberCoroutineScope()
