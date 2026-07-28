package coredevices.pebble.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import co.touchlab.kermit.Logger
import coredevices.database.WeatherLocationDao
import coredevices.database.WeatherLocationEntity
import coredevices.pebble.weather.WeatherFetcher
import coredevices.pebble.weather.manualWeatherLocation
import coredevices.pebble.weather.parseLatitude
import coredevices.pebble.weather.parseLongitude
import coredevices.pebble.weather.usefulName
import coredevices.ui.M3Dialog
import dev.jordond.compass.Place
import dev.jordond.compass.autocomplete.Autocomplete
import dev.jordond.compass.autocomplete.mobile
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

private const val MAX_WEATHER_LOCATIONS = 5
private val logger = Logger.withTag("WeatherScreen")

@Composable
fun WeatherScreen(navBarNav: NavBarNav, topBarParams: TopBarParams) {
    val weatherFetcher: WeatherFetcher = koinInject()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        topBarParams.searchAvailable(null)
        topBarParams.actions {
            TopBarIconButtonWithToolTip(
                onClick = { scope.launch { weatherFetcher.fetchWeather(GlobalScope) } },
                icon = Icons.Filled.Refresh,
                description = "Refresh Weather",
            )
        }
        topBarParams.title("Weather Locations")
    }
    val weatherLocationDao: WeatherLocationDao = koinInject()
    val locations by weatherLocationDao.getAllLocationsFlow().collectAsState(emptyList())
    var showAddDialog by remember { mutableStateOf(false) }
    var locationToDelete by remember { mutableStateOf<WeatherLocationEntity?>(null) }

    locationToDelete?.let { location ->
        DeleteLocationConfirmDialog(
            locationName = location.name,
            onDismiss = { locationToDelete = null },
            onConfirm = {
                scope.launch {
                    weatherLocationDao.delete(location)
                    weatherFetcher.fetchWeather(GlobalScope)
                }
                locationToDelete = null
            },
        )
    }

    if (showAddDialog) {
        AddWeatherLocationDialog(
            onDismiss = { showAddDialog = false },
            onAddLocation = { location ->
                scope.launch {
                    weatherLocationDao.upsert(location)
                    weatherFetcher.fetchWeather(GlobalScope)
                }
            },
            allowCurrentLocation = !locations.any { it.currentLocation },
            // max+1, not size: delete doesn't compact indices, so size can collide
            // with a surviving row and make the ORDER BY tie-break unspecified.
            orderIndex = (locations.maxOfOrNull { it.orderIndex } ?: -1) + 1,
        )
    }

    var mutableLocations by remember(locations) { mutableStateOf(locations) }
    val hapticFeedback = LocalHapticFeedback.current
    val lazyListState = rememberLazyListState()
    val reorderableLazyListState = rememberReorderableLazyListState(lazyListState) { from, to ->
        logger.v { "drag: from from to $to" }
        mutableLocations = mutableLocations.toMutableList().apply {
            add(to.index, removeAt(from.index))
        }
        hapticFeedback.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    fun onDragStarted() {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
        logger.v { "onDragStarted" }
    }

    fun onDragStopped(key: Uuid) {
        hapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
        scope.launch {
            mutableLocations.forEachIndexed { index, location ->
                weatherLocationDao.updateOrder(location.key, index)
            }
            weatherFetcher.fetchWeather(GlobalScope)
        }
    }

    Box(modifier = Modifier.background(MaterialTheme.colorScheme.background)) {
        Scaffold(
            floatingActionButton = {
                if (locations.size < MAX_WEATHER_LOCATIONS) {
                    FloatingActionButton(
                        onClick = { showAddDialog = true }
                    ) {
                        Icon(Icons.Filled.Add, "Add location")
                    }
                }
            },
        ) {
            LazyColumn(
                state = lazyListState,
            ) {
                items(mutableLocations, key = { it.key }) { location ->
                    ReorderableItem(reorderableLazyListState, key = location.key) { isDragging ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.longPressDraggableHandle(
                                onDragStarted = { onDragStarted() },
                                onDragStopped = { onDragStopped(location.key) },
                            ).shake(isDragging)
                                .fillMaxWidth()
                        ) {
                            ListItem(
                                headlineContent = { Text(location.name) },
                                supportingContent = {
                                    Text(if (location.currentLocation) "Current Location" else "Fixed Location")
                                },
                                trailingContent = {
                                    IconButton(onClick = { locationToDelete = location }) {
                                        Icon(Icons.Filled.Delete, "Remove")
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class AddLocationMode { Choose, Search, Manual }

@Composable
private fun AddWeatherLocationDialog(
    onDismiss: () -> Unit,
    onAddLocation: (WeatherLocationEntity) -> Unit,
    allowCurrentLocation: Boolean,
    orderIndex: Int,
) {
    var mode by remember { mutableStateOf(AddLocationMode.Choose) }
    var addressQuery by remember { mutableStateOf("") }
    val autoComplete = remember { Autocomplete.mobile() }
    var suggestions by remember { mutableStateOf<List<Place>>(emptyList()) }
    var searchFailed by remember { mutableStateOf(false) }
    var latitudeText by remember { mutableStateOf("") }
    var longitudeText by remember { mutableStateOf("") }
    var nameText by remember { mutableStateOf("") }
    val latitude = parseLatitude(latitudeText)
    val longitude = parseLongitude(longitudeText)

    LaunchedEffect(addressQuery) {
        if (addressQuery.length >= 3) {
            delay(300)
            val result = autoComplete.search(addressQuery)
            if (result.isError) {
                logger.e { "Error searching for places: ${result.errorOrNull()}" }
                searchFailed = true
                suggestions = emptyList()
            } else {
                searchFailed = false
                suggestions = result.getOrNull() ?: emptyList()
            }
        } else {
            searchFailed = false
            suggestions = emptyList()
        }
    }

    M3Dialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (mode) {
                    AddLocationMode.Choose -> "Add Weather Location"
                    AddLocationMode.Search -> "Search Location"
                    AddLocationMode.Manual -> "Enter Coordinates"
                }
            )
        },
        scrollableContent = mode == AddLocationMode.Manual,
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            if (mode == AddLocationMode.Manual) {
                Spacer(Modifier.width(8.dp))
                TextButton(
                    enabled = latitude != null && longitude != null,
                    onClick = {
                        if (latitude == null || longitude == null) return@TextButton
                        onAddLocation(manualWeatherLocation(latitude, longitude, nameText, orderIndex))
                        onDismiss()
                    },
                ) { Text("Add") }
            }
        },
    ) {
        when (mode) {
            AddLocationMode.Choose -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (allowCurrentLocation) {
                    AddLocationChoice(
                        icon = Icons.Default.MyLocation,
                        title = "Current Location",
                        subtitle = "Uses your device's location",
                    ) {
                        val location = WeatherLocationEntity(
                            key = Uuid.random(),
                            name = "Current Location",
                            latitude = null,
                            longitude = null,
                            currentLocation = true,
                            orderIndex = orderIndex,
                        )
                        onAddLocation(location)
                        onDismiss()
                    }
                }

                AddLocationChoice(
                    icon = Icons.Default.LocationOn,
                    title = "Fixed Location",
                    subtitle = "Search for a specific address",
                ) { mode = AddLocationMode.Search }

                AddLocationChoice(
                    icon = Icons.Default.EditLocationAlt,
                    title = "Coordinates",
                    subtitle = "Enter latitude and longitude",
                ) { mode = AddLocationMode.Manual }
            }

            AddLocationMode.Search -> Column {
                OutlinedTextField(
                    value = addressQuery,
                    onValueChange = { addressQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Address") },
                    placeholder = { Text("Enter city or address") },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null)
                    },
                    singleLine = true,
                )

                if (suggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        suggestions.forEach { place ->
                            val displayName = place.displayName()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        val locationName = place.usefulName() ?: displayName
                                        val location = WeatherLocationEntity(
                                            key = Uuid.random(),
                                            name = locationName,
                                            latitude = place.coordinates.latitude,
                                            longitude = place.coordinates.longitude,
                                            currentLocation = false,
                                            orderIndex = orderIndex,
                                        )
                                        onAddLocation(location)
                                        onDismiss()
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    displayName,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                } else if (searchFailed) {
                    Text(
                        "Couldn't search for locations. Check your connection and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    TextButton(
                        onClick = { mode = AddLocationMode.Manual },
                        modifier = Modifier.padding(top = 4.dp),
                    ) { Text("Enter coordinates instead") }
                } else if (addressQuery.length >= 3) {
                    Text(
                        "Type to search for locations...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }

            // Plain text keyboard on these fields: some IMEs' decimal layouts
            // have no minus key, which southern/western coordinates need.
            AddLocationMode.Manual -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latitudeText,
                    onValueChange = { latitudeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Latitude") },
                    placeholder = { Text("e.g. 51.5074") },
                    singleLine = true,
                    isError = latitudeText.isNotBlank() && latitude == null,
                    supportingText = if (latitudeText.isNotBlank() && latitude == null) {
                        { Text("Number between -90 and 90") }
                    } else null,
                )
                OutlinedTextField(
                    value = longitudeText,
                    onValueChange = { longitudeText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Longitude") },
                    placeholder = { Text("e.g. -0.1278") },
                    singleLine = true,
                    isError = longitudeText.isNotBlank() && longitude == null,
                    supportingText = if (longitudeText.isNotBlank() && longitude == null) {
                        { Text("Number between -180 and 180") }
                    } else null,
                )
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name (optional)") },
                    placeholder = { Text("Defaults to the coordinates") },
                    singleLine = true,
                )
            }
        }
    }
}

@Composable
private fun AddLocationChoice(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun Place.displayName(): String {
    val name = usefulName()
    return "$name, ${administrativeArea ?: country}"
}

@Composable
private fun DeleteLocationConfirmDialog(
    locationName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    M3Dialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove Location") },
        buttons = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onConfirm) { Text("Remove") }
        },
    ) {
        Text("Are you sure you want to remove \"$locationName\"?")
    }
}