package coredevices.coreapp.ui.screens

import CoreNav
import NoOpCoreNav
import PlatformUiContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import co.touchlab.kermit.Logger
import com.anopticlabs.gravel.pkjs.DeniedPkjsNotice
import com.anopticlabs.gravel.pkjs.NetworkDenyEnforcement
import com.anopticlabs.gravel.pkjs.deniedPkjsNotice
import com.anopticlabs.gravel.pkjs.networkDenyEnforcement
import com.anopticlabs.gravel.ui.KnownIssuesLink
import com.anopticlabs.gravel.weather.WeatherShareSwitch
import com.anopticlabs.gravel.weather.applyWeatherChoice
import com.anopticlabs.gravel.weather.visibleSwitchColors
import com.anopticlabs.gravel.weather.weatherConsentText
import coredevices.util.weatherOn
import coredevices.util.KnownIssue
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import coreapp.composeapp.generated.resources.Res
import coreapp.composeapp.generated.resources.gravel_logo
import io.rebble.libpebblecommon.connection.LibPebble
import coredevices.pebble.ui.PebbleRoutes
import coredevices.pebble.ui.PreviewWrapper
import coredevices.pebble.ui.WHATS_NEW_VERSION
import coredevices.pebble.weather.WeatherFetcher
import coredevices.ui.PebbleElevatedButton
import coredevices.ui.SignInButtons
import coredevices.util.CoreConfig
import coredevices.util.CoreConfigHolder
import coredevices.util.DoneInitialOnboarding
import coredevices.util.Permission
import coredevices.util.PermissionRequester
import coredevices.util.name
import coredevices.util.rememberUiContext
import coredevices.util.requestIsFullScreen
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview
import org.koin.compose.getKoin
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.module
import theme.onboardingScheme


enum class OnboardingStage {
    Welcome,
    DeviceSelection,
    Permissions,
    // Fork: the user's one-time choice for whether watchfaces/apps may reach the
    // internet and location by default. Runs as the last stage of the fork's flow:
    // Permissions routes here and onDone goes straight to Done, skipping the SignIn
    // stage that sits between them in this enum (kept compiled for cheap upstream
    // merges). Existing installs, which never re-run onboarding, keep the
    // deny-by-default baseline.
    WatchappPrivacy,
    SignIn,
    Done,
}

enum class DeviceChoice {
    Watch,
    Index01,
    Both,
}

class OnboardingViewModel(private val config: CoreConfigHolder) : ViewModel() {
    val stage = mutableStateOf(OnboardingStage.Welcome)
    val deviceChoice = mutableStateOf<DeviceChoice?>(null)
    val requestedPermissions = mutableStateOf(emptySet<Permission>())
    val coreConfig = config.config
    fun setIndexEnabled(enabled: Boolean) {
        config.update(config.config.value.copy(enableIndex = enabled))
    }

    // Fork: stamp the current changelog revision as seen (see WhatsNewDialog). Called on
    // onboarding exit so a fresh install is not shown the update dialog for changes it was
    // just walked through during setup.
    fun markWhatsNewSeen() {
        config.update(config.config.value.copy(lastSeenWhatsNewVersion = WHATS_NEW_VERSION))
    }
}

private val logger = Logger.withTag("OnboardingScreen")

@Preview
@Composable
fun OnboardingScreenPreview() {
    PreviewWrapper(extraModule = module {
        single { OnboardingViewModel(CoreConfigHolder(
            CoreConfig(),
            settings = Settings(),
            Json.Default
        )) }
    }) {
        OnboardingScreen(NoOpCoreNav)
    }
}


@Composable
fun OnboardingScreen(
    coreNav: CoreNav,
) {
    val viewModel = koinViewModel<OnboardingViewModel>()
    val permissionRequester: PermissionRequester = koinInject()
    val scope = rememberCoroutineScope()
    val settings: Settings = koinInject()
    val doneInitialOnboarding: DoneInitialOnboarding = koinInject()

    fun exitOnboarding() {
        logger.v { "exitOnboarding" }
        settings[SHOWN_ONBOARDING] = true
        // Fork: a fresh install has just made its watchapp privacy choice during
        // onboarding, so mark the current "What's New" as seen, otherwise the update
        // dialog would immediately re-announce what the user just set.
        viewModel.markWhatsNewSeen()
        doneInitialOnboarding.onDoneInitialOnboarding()
        coreNav.navigateTo(PebbleRoutes.WatchHomeRoute)
    }

    suspend fun requestPermission(permission: Permission, uiContext: PlatformUiContext) {
        permissionRequester.requestPermission(permission, uiContext)
        viewModel.requestedPermissions.value += permission
    }

    MaterialTheme(colorScheme = onboardingScheme) {
    Scaffold { windowInsets ->
        Box(modifier = Modifier.padding(windowInsets).fillMaxSize()) {
            when (viewModel.stage.value) {
                OnboardingStage.Welcome -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.gravel_logo),
                            contentDescription = "Gravel logo",
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.height(50.dp),
                        )
                        Spacer(modifier = Modifier.height(15.dp))
                        PebbleElevatedButton(
                            text = "Get Started",
                            onClick = {
                                viewModel.stage.value = OnboardingStage.DeviceSelection
                            },
                            primaryColor = true,
                        )
                    }
                }

                OnboardingStage.DeviceSelection -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "I have a:",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(30.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                        ) {
                            DeviceChoiceCard(
                                label = "Watch",
                                icon = Icons.Default.Watch,
                                onClick = {
                                    viewModel.deviceChoice.value = DeviceChoice.Watch
                                    viewModel.stage.value = OnboardingStage.Permissions
                                },
                            )
                            // Gravel: a ring is paired from the Devices tab, where these choices end
                            // as Watch does; upstream's Index feature (enableIndex) stays off.
                            DeviceChoiceCard(
                                label = "Index 01",
                                icon = Icons.Default.RadioButtonUnchecked,
                                onClick = {
                                    viewModel.deviceChoice.value = DeviceChoice.Index01
                                    viewModel.stage.value = OnboardingStage.Permissions
                                },
                            )
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        DeviceChoiceCard(
                            label = "Both",
                            icon = Icons.Default.Devices,
                            onClick = {
                                viewModel.deviceChoice.value = DeviceChoice.Both
                                viewModel.stage.value = OnboardingStage.Permissions
                            },
                        )
                    }
                }

                OnboardingStage.Permissions -> {
                    val uiContext = rememberUiContext()
                    if (uiContext != null) {
                        val missingPermissions by permissionRequester.missingPermissions.collectAsState()
                        val permissionToRequest = missingPermissions.firstOrNull {
                            it !in viewModel.requestedPermissions.value
                        }
                        logger.v { "permissionToRequest = $permissionToRequest  /  missingPermissions = $missingPermissions " }
                        if (permissionToRequest == null) {
                            // Fork: Core-account sign-in is removed with the
                            // Firebase strip, so onboarding skips the SignIn
                            // stage; the stage and its UI stay compiled for
                            // cheap upstream merges. Route through the watchapp
                            // privacy choice before finishing.
                            viewModel.stage.value = OnboardingStage.WatchappPrivacy
                        } else {
                            val warnBeforeFullScreenRequest = permissionToRequest.requestIsFullScreen()
                            LaunchedEffect(permissionToRequest) {
                                if (!warnBeforeFullScreenRequest) {
                                    requestPermission(permissionToRequest, uiContext)
                                }
                            }
                            Column(
                                modifier = Modifier.fillMaxSize().padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = if (warnBeforeFullScreenRequest) {
                                    Arrangement.Center
                                } else {
                                    Arrangement.Top
                                },
                            ) {
                                if (!warnBeforeFullScreenRequest) {
                                    // Space from top of screen
                                    Spacer(modifier = Modifier.height(20.dp))
                                }
                                Text(
                                    text = permissionToRequest.name(),
                                    fontSize = 25.sp,
                                    textAlign = TextAlign.Center,
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Text(permissionToRequest.descriptionOnboarding(), textAlign = TextAlign.Center)
                                if (warnBeforeFullScreenRequest) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    PebbleElevatedButton(
                                        text = "OK",
                                        onClick = {
                                            scope.launch {
                                                requestPermission(
                                                    permissionToRequest,
                                                    uiContext
                                                )
                                            }
                                        },
                                        primaryColor = true,
                                    )
                                }
                            }
                        }
                    }
                }

                OnboardingStage.WatchappPrivacy -> {
                    WatchappPrivacyStage(
                        onDone = { viewModel.stage.value = OnboardingStage.Done },
                    )
                }

                OnboardingStage.SignIn -> {
                    val coreConfig by viewModel.coreConfig.collectAsState()
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = "Sign In",
                            fontSize = 35.sp,
                            modifier = Modifier.padding(bottom = 25.dp),
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Sign in to backup your Pebble account to backup apps, settings, etc", textAlign = TextAlign.Center)
                        SignInButtons(
                            onDismiss = { viewModel.stage.value = OnboardingStage.Done },
                            primaryColor = true,
                            // No anonymous data to preserve at this point — proceed straight
                            // to the existing account if Firebase reports a collision.
                            skipAccountSwitchConfirmation = true,
                        )
                        if (!coreConfig.enableIndex) {
                            PebbleElevatedButton(
                                text = "Skip",
                                onClick = { viewModel.stage.value = OnboardingStage.Done },
                                primaryColor = true,
                            )
                        }
                    }
                }

                OnboardingStage.Done -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        PebbleElevatedButton(
                            text = "Connect a Pebble!",
                            onClick = ::exitOnboarding,
                            primaryColor = true,
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * Fork: one-time onboarding choice for the global watchapp/watchface phone-side
 * permission defaults (internet + location) and for Gravel's own weather. All default to
 * off (deny) here: the user opts in rather than out. The choice is confirmed via a dialog,
 * then written to WatchConfig and CoreConfig, then acknowledged with a note pointing at the
 * settings screens where it can be changed later.
 */
@Composable
private fun WatchappPrivacyStage(onDone: () -> Unit) {
    val koin = getKoin()
    val weatherFetcher: WeatherFetcher = koinInject()
    WatchappPrivacyChoices(
        libPebble = koinInject(),
        networkDenyEnforcement = remember { koin.networkDenyEnforcement() },
        configHolder = koinInject(),
        // As the Settings switch does: on fetches at once, off clears what weather left.
        onWeatherChosen = { GlobalScope.launch { weatherFetcher.fetchWeather(this) } },
        onDone = onDone,
    )
}

/**
 * Gravel: [WatchappPrivacyStage] with its dependencies passed in, so a device test can drive it.
 * [onWeatherChosen] runs after Confirm has recorded the weather choice.
 */
@Composable
fun WatchappPrivacyChoices(
    libPebble: LibPebble,
    networkDenyEnforcement: NetworkDenyEnforcement,
    configHolder: CoreConfigHolder,
    onWeatherChosen: () -> Unit,
    onDone: () -> Unit,
) {
    var allowInternet by remember { mutableStateOf(false) }
    var allowLocation by remember { mutableStateOf(false) }
    val weatherInterval = remember { configHolder.config.value.weatherSyncInterval }
    // Gravel: a new install starts off; a re-run of setup after an earlier Confirm shows what is stored.
    var shareWeather by remember { mutableStateOf(configHolder.config.value.weatherOn) }
    var showConfirm by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    if (saved) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "You're all set",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Watchfaces and apps will have internet ${onOff(allowInternet)} and " +
                    "location ${onOff(allowLocation)} by default. You can change this any time, " +
                    "for all apps or one at a time, in Settings > Apps > Watch App Permissions.\n\n" +
                    if (shareWeather) {
                        "Your weather locations are shared with Core Devices for forecasts, with " +
                            "Current Location as a nearby town. You can turn this off in Settings > Weather."
                    } else {
                        "Gravel's own weather is off, so it sends nothing to Core Devices. You can " +
                            "turn it on in Settings > Weather." +
                            if (allowInternet) {
                                " Watchfaces and apps you allow online can still get weather through " +
                                    "Core Devices' weather server."
                            } else {
                                ""
                            }
                    },
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(20.dp))
            PebbleElevatedButton(
                text = "Continue",
                onClick = onDone,
                primaryColor = true,
            )
        }
        }
        return
    }

    // Gravel: both screens scroll, as the weather text can make them taller than the screen.
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Privacy",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Some watchfaces and apps run code inside Gravel to fetch things like " +
                "weather, which can use your internet connection and location. Choose what " +
                "that code is allowed to do by default. Off is the safer choice, but can " +
                "stop features that need it, such as third-party weather, from working; you " +
                "can allow individual apps later. Separate companion apps on your phone use " +
                "their own Android permissions.",
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Allow internet access", modifier = Modifier.weight(1f))
            Switch(checked = allowInternet, onCheckedChange = { allowInternet = it }, colors = visibleSwitchColors())
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Allow location", modifier = Modifier.weight(1f))
            Switch(checked = allowLocation, onCheckedChange = { allowLocation = it }, colors = visibleSwitchColors())
        }
        Spacer(modifier = Modifier.height(20.dp))
        WeatherShareSwitch(checked = shareWeather, onCheckedChange = { shareWeather = it })
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = weatherConsentText(weatherInterval), textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(24.dp))
        PebbleElevatedButton(
            text = "Finish setup",
            onClick = { showConfirm = true },
            primaryColor = true,
        )
    }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Confirm your choice") },
            text = {
                // Gravel: the app page's notice for code that runs with internet access off.
                val oneLayerNote = deniedPkjsNotice(
                    networkGranted = allowInternet,
                    networkDenyEnforcement,
                    libPebble.config.value.watchConfig.deniedPkjsWithoutPrimaryLayer,
                ) == DeniedPkjsNotice.RunsWithoutPrimaryLayer
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "By default, watchfaces and apps will have:\n\n" +
                            "Internet: ${if (oneLayerNote) "Blocked (see below)" else allowedBlocked(allowInternet)}\n" +
                            "Location: ${allowedBlocked(allowLocation)}\n\n" +
                            (if (oneLayerNote) ONE_LAYER_NOTE + "\n\n" else "") +
                            "Gravel's own weather (sends your weather locations to Core Devices): " +
                            "${if (shareWeather) "On" else "Off"}\n\n" +
                            "You can change this any time in Settings.",
                    )
                    if (oneLayerNote) KnownIssuesLink(KnownIssue.REAL_TIME_CONNECTIONS_ON_ONE_LAYER)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cfg = libPebble.config.value
                    libPebble.updateConfig(
                        cfg.copy(
                            watchConfig = cfg.watchConfig.copy(
                                watchappDefaultNetworkAllowed = allowInternet,
                                watchappDefaultLocationAllowed = allowLocation,
                            ),
                        ),
                    )
                    configHolder.applyWeatherChoice(shareWeather)
                    onWeatherChosen()
                    showConfirm = false
                    saved = true
                }) { Text("Confirm") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Back") }
            },
        )
    }
}

private fun onOff(value: Boolean): String = if (value) "on" else "off"

private fun allowedBlocked(value: Boolean): String = if (value) "Allowed" else "Blocked"

private const val ONE_LAYER_NOTE = "Gravel blocks internet access for watchfaces and apps with " +
    "several layers. One of them isn't active on this phone, so real-time connections (the " +
    "kind video calls use) depend on a single layer, in an up-to-date Android System WebView, " +
    "instead of two. A switch in Settings > Apps > Watch App Permissions stops the code of " +
    "watchfaces and apps with internet access off from running instead."

@Composable
private fun DeviceChoiceCard(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.width(140.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(72.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = label,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

val HighlightStyle = SpanStyle(
    fontWeight = FontWeight.Bold,
    fontStyle = FontStyle.Italic
)

expect fun Permission.descriptionOnboarding(): AnnotatedString

const val SHOWN_ONBOARDING = "shown_onboarding"