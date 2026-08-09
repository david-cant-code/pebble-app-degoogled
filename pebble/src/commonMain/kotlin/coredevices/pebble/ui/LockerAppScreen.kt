package coredevices.pebble.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import coil3.compose.AsyncImage
import coredevices.database.AppstoreSource
import coredevices.database.AppstoreSourceDao
import coredevices.pebble.Platform
import coredevices.pebble.rememberLibPebble
import coredevices.pebble.services.AppstoreService
import coredevices.pebble.services.PEBBLE_FEED_URL
import coredevices.pebble.services.SettingsPageState
import coredevices.pebble.services.isPebbleFeed
import coredevices.ui.ConfirmDialog
import coredevices.ui.PebbleElevatedButton
import io.ktor.http.URLProtocol
import io.ktor.http.parseUrl
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.database.entity.LockerAppPermissionType
import io.rebble.libpebblecommon.locker.AppCapability
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.orderIndexForInsert
import io.rebble.libpebblecommon.metadata.WatchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.Padding
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

private val logger = Logger.withTag("LockerAppScreen")

class LockerAppViewModel(
//    private val pebbleWebServices: RealPebbleWebServices,
    private val platform: Platform,
) : ViewModel(), KoinComponent {
//    var storeEntries by mutableStateOf<List<AppVariant>?>(null)
    var addedToLocker by mutableStateOf(false)
    var selectedStoreEntry by mutableStateOf<CommonApp?>(null)
    var hearts by mutableStateOf<Int?>(null)
    var loadingFromStore = false

    fun loadAppFromStore(id: String, watchType: WatchType, source: AppstoreSource, useCache: Boolean) {
        val service = get<AppstoreService> { parametersOf(source) }
        viewModelScope.launch {
            loadingFromStore = true
            val result = service.fetchAppStoreApp(id, watchType, useCache)?.data?.firstOrNull()
//            if (result != null) {
//                storeEntries = getAppVariants(Uuid.parse(result.uuid), watchType, sources)
//            }
            selectedStoreEntry = result?.asCommonApp(
                watchType = watchType,
                platform = platform,
                source = source,
                categories = service.cachedCategoriesOrDefaults(AppType.fromString(result.type)),
            ).also {
                hearts = it?.hearts
            }
            loadingFromStore = false
        }
    }

    fun serviceFor(entry: CommonApp) = get<AppstoreService> { parametersOf(entry.appstoreSource) }

//    private suspend fun getAppVariants(
//        uuid: Uuid,
//        watchType: WatchType,
//        storeSources: List<Pair<String, AppstoreSource>>? = null,
//    ): List<AppVariant> {
//        val sources = storeSources ?: pebbleWebServices.searchUuidInSources(uuid)
//        return sources.map { (id, source) ->
//            val service = get<AppstoreService> { parametersOf(source) }
//            //TODO: Search by uuid instead of id to get all variants, id is source-specific except for OG apps
//            viewModelScope.async(Dispatchers.IO) {
//                val result = service.fetchAppStoreApp(id, watchType)
//                val categories = result?.data?.firstOrNull()?.let { service.fetchCategories(AppType.fromString(it.type)!!) }
//                result?.data?.map { appEntry ->
//                    appEntry.asCommonApp(watchType, platform, source, categories)?.let { app ->
//                        AppVariant(
//                            source = source,
//                            app = app
//                        )
//                    }
//                } ?: emptyList()
//            }
//        }.awaitAll().flatten().filterNotNull().sortedBy { it.app.version ?: "0" }.reversed()
//    }
}

//data class AppVariant(
//    val source: AppstoreSource,
//    val app: CommonApp,
//)

@Composable
private fun appstoreSourceFromId(id: Int?): AppstoreSource? {
    val storeSourceDao: AppstoreSourceDao = koinInject()
    val storeSource by produceState<AppstoreSource?>(null, id) {
        value = id?.let { storeSourceDao.getSourceById(it) }
    }
    return storeSource
}

@Composable
fun LockerAppScreen(topBarParams: TopBarParams, uuid: Uuid?, navBarNav: NavBarNav, storeId: String?, storeSourceId: Int?) {
    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        val scope = rememberCoroutineScope()
        val libPebble = rememberLibPebble()
        val lastConnectedWatch = lastConnectedWatch()
        val runningApp by (lastConnectedWatch as? ConnectedPebbleDevice)?.runningApp?.collectAsState(
            null
        ) ?: mutableStateOf(null)
        val appIsRunning = runningApp == uuid
        val connected = lastConnectedWatch is ConnectedPebbleDevice
        val viewModel = koinViewModel<LockerAppViewModel>()
        val sharedViewModel: SharedLockerViewModel = koinInject()
        sharedViewModel.Init()
        var showRemoveConfirmDialog = remember { mutableStateOf(false) }
        var loadingToWatch by remember { mutableStateOf(false) }

        val lockerEntry = loadLockerEntry(uuid, sharedViewModel.watchType.value)
        val entry = remember(lockerEntry, viewModel.selectedStoreEntry) {
            lockerEntry ?: viewModel.selectedStoreEntry
        }
        val storeEntry = viewModel.selectedStoreEntry
        val commonAppStore =
            remember(storeEntry) { storeEntry?.commonAppType as? CommonAppType.Store }
        val storeSource = appstoreSourceFromId(storeSourceId)
        val platform: Platform = koinInject()
        val urlLauncher = LocalUriHandler.current
        val nativeLockerAddUtil: NativeLockerAddUtil = koinInject()

        fun reloadFromStore(useCache: Boolean) {
            if (storeId != null && storeSource != null) {
                viewModel.loadAppFromStore(
                    id = storeId,
                    watchType = sharedViewModel.watchType.value,
                    source = storeSource,
                    useCache = useCache,
                )
            }
        }

        LaunchedEffect(storeId, storeSource, sharedViewModel.watchType.value) {
            reloadFromStore(useCache = true)
        }

        LaunchedEffect(entry) {
            topBarParams.searchAvailable(null)
            topBarParams.actions {
                if (entry != null) {
                    ConfirmDialog(
                        show = showRemoveConfirmDialog,
                        title = "Remove ${entry.title}?",
                        text = "Are you sure you want to remove this app from your Pebble?",
                        onConfirm = {
                            // Don't use local scope: that will die because we moved back
                            GlobalScope.launch {
                                logger.d { "removing app ${entry.uuid}" }
                                topBarParams.showSnackbar("Removing ${entry.title}")
                                val removed = nativeLockerAddUtil.removeFromLocker(storeSource, entry.uuid)
                                logger.d { "removed = $removed" }
                                topBarParams.showSnackbar("Removed ${entry.title}")
                            }
                        },
                        confirmText = "Remove",
                    )
                }
                val showRemove = entry?.commonAppType is CommonAppType.Locker
                if (showRemove) {
                    TopBarIconButtonWithToolTip(
                        onClick = { showRemoveConfirmDialog.value = true },
                        icon = Icons.Filled.Delete,
                        description = "Remove",
                        enabled = !showRemoveConfirmDialog.value,
                    )
                }
            }
            topBarParams.title(entry?.type?.name ?: "")
        }
        PullToRefreshBox(
            isRefreshing = viewModel.loadingFromStore,
            onRefresh = { reloadFromStore(useCache = false) },
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 5.dp)
            ) {
                entry?.let { entry ->
                    if (commonAppStore?.headerImageUrl != null) {
                        AsyncImage(
                            model = commonAppStore.headerImageUrl,
                            contentDescription = "banner",
                            modifier = Modifier.fillMaxWidth().padding(10.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                    Row {
                        AppImage(
                            entry = entry,
                            modifier = Modifier.padding(10.dp).clip(RoundedCornerShape(12.dp)),
                            size = 140.dp,
                        )
                        Column(modifier = Modifier.padding(horizontal = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (entry.type == AppType.Watchapp) {
                                    AsyncImage(
                                        model = entry.listImageUrl,
                                        contentDescription = null,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                }
                                Text(
                                    text = entry.title,
                                    fontSize = 22.sp,
                                    modifier = Modifier.padding(vertical = 5.dp),
                                )
                            }
                            val hearts = if (viewModel.hearts != null) {
                                viewModel.hearts
                            } else {
                                entry.hearts
                            }
                            val isHearted = entry.isHearted()
                            if (hearts != null && isHearted != null) {
                                Row(
                                    modifier = Modifier.padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val service = remember(entry) { viewModel.serviceFor(entry) }
                                    val loggedInForHearts = remember(entry) { service.isLoggedIn() }
                                    val addUrl = commonAppStore?.addHeartUrl
                                    val removeUrl = commonAppStore?.removeHeartUrl
                                    var isChanging by remember { mutableStateOf(false) }
                                    val canAddAndRemove =
                                        loggedInForHearts && addUrl != null && removeUrl != null && entry.storeId != null && !isChanging
                                    val icon = remember(entry, isHearted) {
                                        when {
                                            isHearted -> Icons.Outlined.Favorite
                                            else -> Icons.Outlined.FavoriteBorder
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            if (entry.storeId == null) {
                                                return@IconButton
                                            }
                                            scope.launch {
                                                if (canAddAndRemove) {
                                                    if (!isHearted) {
                                                        isChanging = true
                                                        val result =
                                                            service.addHeart(addUrl, entry.storeId)
                                                        isChanging = false
                                                        if (result) {
                                                            viewModel.hearts?.let {
                                                                viewModel.hearts = it + 1
                                                            }
                                                            reloadFromStore(useCache = false)
                                                        } else {
                                                            topBarParams.showSnackbar("Failed to heart app")
                                                        }
                                                    } else {
                                                        isChanging = true
                                                        val result = service.removeHeart(
                                                            removeUrl,
                                                            entry.storeId
                                                        )
                                                        isChanging = false
                                                        if (result) {
                                                            viewModel.hearts?.let {
                                                                viewModel.hearts = it - 1
                                                            }
                                                            reloadFromStore(useCache = false)
                                                        } else {
                                                            topBarParams.showSnackbar("Failed to remove heart")
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        modifier = Modifier.animateContentSize(),
                                        enabled = canAddAndRemove,
                                    ) {
                                        Icon(
                                            icon,
                                            contentDescription = "Hearts",
                                            tint = if (isHearted) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                LocalContentColor.current
                                            },
                                        )
                                    }
                                    Text(
                                        text = "$hearts",
                                        modifier = Modifier.padding(start = 5.dp),
                                    )
                                }
                            }
                            if (entry.type == AppType.Watchapp) {
                                entry.category?.let {
                                    Spacer(Modifier.height(5.dp))
                                    FilterChip(
                                        true,
                                        onClick = {
                                            if (entry.categorySlug != null && storeSource != null) {
                                                navBarNav.navigateTo(
                                                    PebbleNavBarRoutes.AppStoreCollectionRoute(
                                                        sourceId = storeSource.id,
                                                        path = "category/${entry.categorySlug}",
                                                        title = entry.category
                                                    )
                                                )
                                            }
                                        },
                                        label = { Text(entry.category) }
                                    )
                                }
                            }
                            Spacer(Modifier.height(5.dp))
                            val watchName = lastConnectedWatch?.displayName() ?: ""
                            val onWatchText = if (entry.isCompatible && entry.isSynced()) {
                                if (appIsRunning) {
                                    "Running On Watch $watchName"
                                } else {
                                    "On Watch $watchName"
                                }
                            } else if (!entry.isCompatible) {
                                "Not Compatible with $watchName"
                            } else if (entry.commonAppType !is CommonAppType.Store) {
                                "Not On Watch $watchName"
                            } else {
                                null
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                entry.CompatibilityWarning(topBarParams)
                                if (onWatchText != null) {
                                    Text(
                                        onWatchText,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(vertical = 5.dp),
                                    )
                                }
                            }
                        }
                    }

                    val connectedIdentifier = lastConnectedWatch?.identifier
                    val showStartApp =
                        entry.isCompatible && entry.commonAppType.canStartApp() && !appIsRunning
                                && !viewModel.addedToLocker
                    if (showStartApp) {
                        val text = if (entry.type == AppType.Watchapp) {
                            "Start App"
                        } else {
                            "Start Watchface"
                        }
                        PebbleElevatedButton(
                            text = text,
                            onClick = {
                                // Global scope because it could take a second to download/sync/load app
                                GlobalScope.launch {
                                    if (connectedIdentifier != null) {
                                        loadingToWatch = true
                                        libPebble.launchApp(
                                            entry,
                                            topBarParams,
                                            connectedIdentifier
                                        )
                                        loadingToWatch = false
                                    }
                                }
                            },
                            enabled = connected && !loadingToWatch,
                            icon = Icons.Default.PlayCircle,
                            contentDescription = text,
                            primaryColor = true,
                            modifier = Modifier.padding(5.dp),
                        )
                    }
                    // Fork: phone-side permission surface. For an installed (locker) app,
                    // show the real per-app controls (internet + location tri-state). For a
                    // store listing not yet installed, show what it declares plus an honest
                    // note that phone-side JS means internet access; the appstore capability
                    // vocabulary can't express "network", so upstream never disclosed it.
                    when (entry.commonAppType) {
                        is CommonAppType.Locker -> {
                            WatchappPermissionControls(
                                uuid = entry.uuid,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }

                        is CommonAppType.Store -> {
                            StoreCapabilityDisclosure(
                                capabilities = storeEntry?.capabilities.orEmpty(),
                                entry = entry,
                                topBarParams = topBarParams,
                            )
                        }

                        is CommonAppType.System -> Unit
                    }
                    if (entry.commonAppType is CommonAppType.Store && !viewModel.addedToLocker) {
//                    val hasUuidConflict = remember(viewModel.storeEntries) { // One store has multiple entries with same uuid
//                        viewModel.storeEntries?.let {
//                            it.distinctBy { it.source.url }.size < it.size
//                        } ?: false
//                    }
//                    var variantsExpanded by remember { mutableStateOf(false) }
                        Row(
                            Modifier.padding(5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PebbleElevatedButton(
                                text = "Add To Watch",
                                onClick = {
                                    // Global scope because it could take a second to download/sync/load app
                                    GlobalScope.launch {
                                        val watch = lastConnectedWatch as? ConnectedPebbleDevice
                                        viewModel.addedToLocker = true
                                        val addResult = nativeLockerAddUtil.addAppToLocker(
                                            entry.commonAppType,
                                            entry.commonAppType.storeSource
                                        )
                                        if (!addResult) {
                                            topBarParams.showSnackbar("Failed to add app")
                                            return@launch
                                        }
                                        if (watch != null) {
                                            libPebble.launchApp(
                                                entry = entry,
                                                snackbarDisplay = topBarParams,
                                                connectedIdentifier = watch.identifier,
                                            )
                                        }
                                    }
                                },
                                icon = Icons.Default.Add,
                                contentDescription = "Add To Watch",
                                primaryColor = true,
                                modifier = Modifier.padding(end = 8.dp),
                            )
//                        if (viewModel.storeEntries != null && (viewModel.storeEntries?.size ?: 1) > 1) {
//                            ExposedDropdownMenuBox(
//                                expanded = variantsExpanded,
//                                onExpandedChange = { variantsExpanded = it },
//                                modifier = Modifier.weight(1f)
//                            ) {
//                                val version = entry.version?.let { "v$it" } ?: "Unknown version"
//                                val textContent = if (hasUuidConflict) {
//                                    "${entry.commonAppType.storeSource.title}: ${entry.title} ($version)"
//                                } else {
//                                    "${entry.commonAppType.storeSource.title} ($version)"
//                                }
//                                TextField(
//                                    value = textContent,
//                                    onValueChange = { },
//                                    readOnly = true,
//                                    singleLine = true,
//                                    trailingIcon = {
//                                        ExposedDropdownMenuDefaults.TrailingIcon(
//                                            expanded = variantsExpanded
//                                        )
//                                    },
//                                    modifier = Modifier
//                                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
//                                )
//                                ExposedDropdownMenu(
//                                    expanded = variantsExpanded,
//                                    onDismissRequest = { variantsExpanded = false }
//                                ) {
//                                    logger.d { "Showing ${viewModel.storeEntries!!.size} variants in dropdown" }
//                                    viewModel.storeEntries!!.forEach { variant ->
//                                        DropdownMenuItem(
//                                            text = {
//                                                if (hasUuidConflict) {
//                                                    Text("${variant.source.title}: ${variant.app.title} (${variant.app.version?.let { "v$it" } ?: "Unknown version"})")
//                                                } else {
//                                                    Text("${variant.source.title} (${variant.app.version?.let { "v$it" } ?: "Unknown version"})")
//                                                }
//                                            },
//                                            onClick = {
//                                                viewModel.selectedStoreEntry = variant.app
//                                                variantsExpanded = false
//                                            }
//                                        )
//                                    }
//                                }
//                            }
//                        }
                        }
                    }

                    Row {
                        entry.SettingsButton(
                            navBarNav = navBarNav,
                            topBarParams = topBarParams,
                            connected = connected,
                        )
                        if (entry.commonAppType is CommonAppTypeLocal && entry.commonAppType.order > 0) {
                            PebbleElevatedButton(
                                text = "Move To Top",
                                onClick = {
                                    scope.launch {
                                        libPebble.setAppOrder(entry.uuid, -1)
                                    }
                                },
                                icon = Icons.Default.VerticalAlignTop,
                                contentDescription = "Move To Top",
                                primaryColor = false,
                                modifier = Modifier.padding(5.dp),
                            )
                        }
                    }
                    if (commonAppStore?.settingsPageState == SettingsPageState.PageDoesntLoad) {
                        Row {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    text = "This app's settings page may not work any more",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(15.dp)
                                )
                            }
                        }
                    }
                    val screenshotsToDisplay = remember(storeEntry) {
                        when (storeEntry?.commonAppType) {
                            is CommonAppType.Store -> {
//                            when (entry.type) {
//                                AppType.Watchface -> entry.commonAppType.allScreenshotUrls.drop(1)
//                                AppType.Watchapp -> entry.commonAppType.allScreenshotUrls
//                            }
                                storeEntry.commonAppType.allScreenshotUrls.drop(1)
                            }

                            else -> emptyList()
                        }
                    }
                    if (screenshotsToDisplay.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .wrapContentHeight()
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(horizontal = 5.dp),
                        ) {
                            items(screenshotsToDisplay, key = { it }) { screenshotUrl ->
                                AsyncImage(
                                    model = screenshotUrl,
                                    contentDescription = "Screenshot",
                                    modifier = Modifier.size(110.dp).clip(RoundedCornerShape(7.dp)),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    // Shared minimum width for the name column — grows to fit the widest label.
                    var propertyNameWidthPx by remember { mutableIntStateOf(0) }
                    val density = LocalDensity.current
                    val propertyNameModifier = Modifier
                        .widthIn(min = with(density) { propertyNameWidthPx.toDp() })
                        .onSizeChanged {
                            propertyNameWidthPx = maxOf(propertyNameWidthPx, it.width)
                        }
                    if (platform == Platform.Android && entry.androidCompanion != null) {
                        PropertyRow(
                            name = "COMPANION",
                            nameModifier = propertyNameModifier,
                            value = entry.androidCompanion.name,
                            onClick = {
                                urlLauncher.open(entry.androidCompanion.url)
                            },
                        )
                    }
                    val description = viewModel.selectedStoreEntry?.description ?: entry.description
                    if (description != null && description.isNotBlank()) {
                        PropertyRow(
                            name = "DESCRIPTION",
                            nameModifier = propertyNameModifier,
                            value = description,
                            multiRow = true,
                        )
                    }

                    PropertyRow(
                        name = "DEVELOPER",
                        nameModifier = propertyNameModifier,
                        value = entry.developerName,
                        onClick = if (entry.developerId != null && storeSource != null) {
                            {
                                val developerId = entry.developerId
                                navBarNav.navigateTo(
                                    PebbleNavBarRoutes.AppStoreCollectionRoute(
                                        sourceId = storeSource.id,
                                        path = "dev/$developerId",
                                        title = "Developer: ${entry.developerName}"
                                    )
                                )
                            }
                        } else {
                            null
                        },
                        onClickIcon = Icons.AutoMirrored.Default.ArrowForward,
                    )
                    entry.version?.let { version ->
                        val sideloadedText =
                            if (entry.commonAppType is CommonAppType.Locker && entry.commonAppType.sideloaded) {
                                " (sideloaded)"
                            } else {
                                ""
                            }
                        val updatedDateText = if (commonAppStore?.publishedDate != null) {
                            " - ${
                                PUBLISHED_DATE_FORMAT.format(
                                    commonAppStore.publishedDate
                                        .toLocalDateTime(TimeZone.currentSystemDefault())
                                ).uppercase()
                            }"
                        } else {
                            ""
                        }
                        PropertyRow(
                            name = "VERSION",
                            nameModifier = propertyNameModifier,
                            value = "$version$sideloadedText$updatedDateText"
                        )
                    }
                    entry.sourceLink?.let { sourceLink ->
                        PropertyRow(
                            name = "SOURCE CODE",
                            nameModifier = propertyNameModifier,
                            value = "External Link",
                            onClick = { urlLauncher.open(sourceLink) }
                        )
                    }

                    commonAppStore?.developerLink?.let { developerLink ->
                        PropertyRow(
                            name = "WEBSITE LINK",
                            nameModifier = propertyNameModifier,
                            value = "External Link",
                            onClick = { urlLauncher.open(developerLink) }
                        )
                    }
                    val contactStoreId = entry.storeId
                    if (
                        commonAppStore?.contactable == true &&
                        commonAppStore.storeSource.isPebbleFeed() &&
                        contactStoreId != null
                    ) {
                        PropertyRow(
                            name = "CONTACT DEVELOPER",
                            nameModifier = propertyNameModifier,
                            value = "Send Message",
                            onClick = {
                                navBarNav.navigateTo(
                                    PebbleRoutes.ContactDeveloperRoute(
                                        appId = contactStoreId,
                                        appTitle = entry.title,
                                    )
                                )
                            },
                            onClickIcon = Icons.AutoMirrored.Default.ArrowForward,
                        )
                    }
                    commonAppStore?.changelog?.let { changelog ->
                        if (changelog.isNotEmpty()) {
                            val show = remember { mutableStateOf(false) }
                            PropertyRow(
                                name = "CHANGELOG",
                                nameModifier = propertyNameModifier,
                                value = "View",
                                onClick = { show.value = true },
                                onClickIcon = Icons.AutoMirrored.Default.ArrowForward,
                            )
                            if (show.value) {
                                AlertDialog(
                                    onDismissRequest = {
                                        show.value = false
                                    },
                                    title = { Text("Changelog") },
                                    text = {
                                        LazyColumn {
                                            items(changelog) { item ->
                                                Row(modifier = Modifier.padding(5.dp)) {
                                                    Text(
                                                        item.version ?: "Unknown version",
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Spacer(modifier = Modifier.weight(1f))
                                                    val date = remember(item.publishedDate) {
                                                        item.publishedDate?.let {
                                                            PUBLISHED_DATE_FORMAT.format(
                                                                it.toLocalDateTime(TimeZone.currentSystemDefault())
                                                            ).uppercase()
                                                        } ?: ""
                                                    }
                                                    Text(
                                                        date,
                                                        fontSize = 20.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                                Text(
                                                    item.releaseNotes ?: "\n",
                                                    modifier = Modifier.padding(5.dp)
                                                )
                                            }
                                        }
                                    },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            show.value = false
                                        }) { Text("Dismiss") }
                                    }
                                )
                            }
                        }
                    }

                    storeSource?.let { storeSource ->
                        val onClick = if (entry.appstoreSource?.url == PEBBLE_FEED_URL) {
                            {
                                urlLauncher.open("https://apps.repebble.com/${entry.storeId}")
                                Unit
                            }
                        } else {
                            null
                        }
                        PropertyRow(
                            name = "STORE",
                            nameModifier = propertyNameModifier,
                            value = storeSource.title,
                            onClick = onClick,
                        )
                    }
                }
            }
        }
    }
}

fun UriHandler.open(url : String): Boolean {
    val urlParsed = parseUrl(url)
    if (urlParsed != null && urlParsed.protocolOrNull in listOf(URLProtocol.HTTP, URLProtocol.HTTPS)) {
        try {
            openUri(url)
            return true
        } catch (e: Exception) {
            // No browser / VIEW handler installed (Android Go, stripped ROMs, DPC policy).
            logger.w(e) { "Failed to open URL: $url" }
            return false
        }
    } else {
        logger.w { "Not opening invalid URL: $url" }
        return false
    }
}

@Composable
private fun PropertyRow(
    name: String,
    value: String,
    onClick: (() -> Unit)? = null,
    onClickIcon: ImageVector = Icons.AutoMirrored.Default.Launch,
    multiRow: Boolean = false,
    nameModifier: Modifier = Modifier,
) {
    Row(modifier = Modifier.padding(5.dp).let{
        if (onClick != null) {
            it.then(Modifier.clickable(onClick = onClick))
        } else {
            it
        }
    }) {
        Text(
            text = name,
            color = Color.Gray,
            modifier = nameModifier,
            maxLines = 1
        )
        if (!multiRow) {
            Text(text = value, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
        }
        if (onClick != null) {
            Icon(
                imageVector = onClickIcon,
                contentDescription = null,
                modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterVertically)
            )
        }
    }
    if (multiRow) {
        Text(text = value, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
fun PermissionItem(permission: AppCapability, entry: CommonApp, topBarParams: TopBarParams) {
    if (entry.commonAppType is CommonAppType.Locker) {
        // Unreachable from current call sites: PermissionItem is only rendered for
        // store listings (via StoreCapabilityDisclosure); installed apps get
        // WatchappPermissionControls on their detail page instead. The branch is
        // kept so the function keeps its upstream shape for cheap merges.
    } else {
        AssistChip(
            onClick = {
                topBarParams.showSnackbar(permission.description())
            },
            label = { Text(permission.name()) },
            leadingIcon = { Icon(permission.icon(), null) },
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/**
 * Fork: honest capability disclosure for a store listing that isn't installed yet.
 * Shows the capabilities the listing declares (location/health/timeline) and, crucially,
 * states plainly that apps can also reach the internet through phone-side code (a fact the
 * appstore metadata cannot express and upstream never surfaced), and points to where that
 * can be controlled once installed.
 */
@Composable
fun StoreCapabilityDisclosure(
    capabilities: List<AppCapability>,
    entry: CommonApp,
    topBarParams: TopBarParams,
) {
    Column(modifier = Modifier.padding(horizontal = 5.dp)) {
        if (capabilities.isNotEmpty()) {
            FlowRow(modifier = Modifier.padding(5.dp)) {
                capabilities.forEach { permission ->
                    PermissionItem(permission, entry, topBarParams)
                }
            }
        }
        Text(
            "Apps can also use the internet through code that runs on your phone (for example " +
                "to fetch weather). After installing, control internet and location for this app " +
                "in Settings > Apps > Watch App Permissions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
        )
    }
}

fun AppCapability.icon(): ImageVector = when (this) {
    AppCapability.Health -> Icons.Default.HealthAndSafety
    AppCapability.Location -> Icons.Default.LocationSearching
    AppCapability.Timeline -> Icons.Default.LocationOn
}

fun AppCapability.name(): String = when (this) {
    AppCapability.Health -> "Health"
    AppCapability.Location -> "Location"
    AppCapability.Timeline -> "Timeline"
}

fun AppCapability.description(): String = when (this) {
    AppCapability.Health -> "Can access health data"
    // Fork: state the real flow. A watchface asking for location almost always feeds it
    // to a phone-side network call (e.g. weather), so "access location" understates it.
    AppCapability.Location -> "Can read your phone's location, and may send it to outside servers over the internet"
    AppCapability.Timeline -> "Can create timeline pins"
}

suspend fun LibPebble.launchApp(
    entry: CommonApp,
    snackbarDisplay: SnackbarDisplay,
    connectedIdentifier: PebbleIdentifier
): Boolean {
    logger.d { "launchApp: ${entry.uuid} - ${entry.title}" }
    val typeText = when (entry.type) {
        AppType.Watchface -> "Watchface"
        AppType.Watchapp -> "WatchApp"
    }
    if (!entry.isSynced()) {
        try {
            withTimeout(15.seconds) {
                snackbarDisplay.showSnackbar("Waiting to sync $typeText to watch...")
                setAppOrder(entry.uuid, orderIndexForInsert(entry.type))
            }
        } catch (_: TimeoutCancellationException) {
            logger.w { "timed out waiting for order change to sync to watch" }
            snackbarDisplay.showSnackbar("$typeText failed to sync to watch")
            return false
        }
    }
    if (!waitUntilAppSyncedToWatch(entry.uuid, connectedIdentifier, 15.seconds)) {
        logger.w { "timed out waiting for blobdb item to sync to watch" }
        snackbarDisplay.showSnackbar("$typeText failed to sync to watch")
        return false
    }
    // Give it a small bit of time to settle
    delay(0.5.seconds)
    snackbarDisplay.showSnackbar("Loading $typeText")
    val launched = withTimeoutOrNull(30.seconds) {
        launchApp(entry.uuid)
        true
    }
    logger.d { "launched = $launched" }
    if (launched == null) {
        snackbarDisplay.showSnackbar("$typeText failed to load")
        return false
    }
    return true
}

fun SnackbarHostState.showSnackbar(scope: CoroutineScope, message: String) {
    scope.launch {
        showSnackbar(message)
    }
}

fun CommonApp.hasSettings(): Boolean = when (commonAppType) {
    is CommonAppType.Locker -> commonAppType.configurable
    is CommonAppType.System -> false
    is CommonAppType.Store -> false
}

suspend fun CommonApp.showSettings(
    navBarNav: NavBarNav,
    libPebble: LibPebble,
    topBarParams: TopBarParams,
) {
    when (commonAppType) {
        is CommonAppType.Locker -> {
            // Fork: the developer settings page URL is built by the app's own PKJS and
            // loaded in a WebView, so opening it is an app-controlled network request to
            // a developer-chosen server, and the app can pack anything it has gathered
            // (location included) into that URL. If the user has denied this app's
            // internet access, honour that here rather than loading the page; otherwise
            // the config page would be a hole exactly the size of the thing we closed.
            if (!libPebble.isWatchappPermissionGranted(uuid, LockerAppPermissionType.Network)) {
                logger.d { "Config page blocked for $uuid: network denied" }
                topBarParams.showSnackbar(
                    "This app's settings page needs internet, which is turned off for it. " +
                        "Enable it in Settings > Apps > Watch App Permissions."
                )
                return
            }
            val watch = libPebble.watches.value.filterIsInstance<ConnectedPebbleDevice>()
                .firstOrNull()
            //TODO: Handle multiple watches connected, selector?
            if (watch == null) {
                logger.w("No connected watch found, cannot show settings")
                return
            }
            val session = if (watch.currentPKJSSession.value?.uuid == uuid) {
                watch.currentPKJSSession.value
            } else {
                logger.d("Launching app $uuid for pkjs settings...")
                libPebble.launchApp(this, topBarParams, watch.identifier)
                watch.currentPKJSSession.first { it?.uuid == uuid }
            }
            if (session?.uuid != uuid) {
                logger.e("PKJS session UUID mismatch: expected $uuid, got ${session?.uuid}")
            } else {
                logger.d { "Opening app settings for $uuid" }
                val url = session.requestConfigurationUrl()
                url ?: run {
                    logger.e("No configuration URL returned for app $uuid")
                    return
                }
                logger.d { "Got app settings URL" }
                WatchappSettingsUrlCache.put(watch.identifier.asString, url)
                navBarNav.navigateTo(
                    PebbleRoutes.WatchappSettingsRoute(
                        watchIdentifier = watch.identifier.asString,
                        title = title,
                    )
                )
            }
        }

        is CommonAppType.System -> Unit

        is CommonAppType.Store -> Unit
    }
}

private val PUBLISHED_DATE_FORMAT = LocalDateTime.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day(Padding.NONE)
    char(',')
    char(' ')
    year()
}