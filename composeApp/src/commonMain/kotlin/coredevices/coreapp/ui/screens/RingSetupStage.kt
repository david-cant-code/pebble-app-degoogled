package coredevices.coreapp.ui.screens

import PlatformUiContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coredevices.libindex.LibIndex
import coredevices.libindex.device.DiscoveredIndexDevice
import coredevices.libindex.device.IndexImage
import coredevices.libindex.device.IndexPairingResult
import coredevices.libindex.device.IndexPairingState
import coredevices.libindex.device.KnownIndexDevice
import coredevices.libindex.device.isFailsafe
import coredevices.pebble.ui.scanPermission
import coredevices.ui.PebbleElevatedButton
import coredevices.util.CompanionDevice
import coredevices.util.PermissionRequester
import coredevices.util.PermissionResult
import coredevices.util.rememberUiContext
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
fun RingPermissionStage(
    onGranted: () -> Unit,
    onSkip: () -> Unit,
) {
    val permissionRequester: PermissionRequester = koinInject()
    val uiContext = rememberUiContext()
    val scope = rememberCoroutineScope()
    val needed = remember { scanPermission() }
    val missing by permissionRequester.missingPermissions.collectAsState()
    val granted = needed == null || needed !in missing
    var denied by remember { mutableStateOf(false) }

    LaunchedEffect(granted) {
        if (granted) onGranted()
    }
    if (granted) return

    OnboardingStageContent {
        Text(
            text = "Bluetooth",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Gravel needs Bluetooth to find and pair your Index 01.",
            textAlign = TextAlign.Center,
        )
        if (denied) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Permission was not granted. You can try again, or skip and pair later from Devices.",
                textAlign = TextAlign.Center,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        PebbleElevatedButton(
            text = "Allow Bluetooth",
            onClick = {
                val context = uiContext ?: return@PebbleElevatedButton
                val permission = needed ?: return@PebbleElevatedButton
                scope.launch {
                    val result = permissionRequester.requestPermission(permission, context)
                    denied = result != PermissionResult.Granted
                }
            },
            primaryColor = true,
        )
        Spacer(modifier = Modifier.height(12.dp))
        PebbleElevatedButton(
            text = "Skip for now",
            onClick = onSkip,
            primaryColor = false,
        )
    }
}

@Composable
fun RingSetupStage(onDone: () -> Unit) {
    val libIndex: LibIndex = koinInject()
    val companionDevice: CompanionDevice = koinInject()
    val permissionRequester: PermissionRequester = koinInject()
    val uiContext = rememberUiContext()
    val scope = rememberCoroutineScope()
    val rings by libIndex.rings.collectAsState()
    val scanning by libIndex.isScanning.collectAsState()
    val needed = remember { scanPermission() }
    val missing by permissionRequester.missingPermissions.collectAsState()
    val granted = needed == null || needed !in missing
    var failure by remember { mutableStateOf<String?>(null) }
    var paired by remember { mutableStateOf(false) }
    val known = rings.any { it is KnownIndexDevice }
    val finished = paired || known

    LaunchedEffect(granted, finished) {
        if (granted && !finished && !libIndex.isScanning.value) {
            libIndex.startScan()
        }
    }
    DisposableEffect(Unit) {
        onDispose { libIndex.stopScan() }
    }

    val discovered = rings.filterIsInstance<DiscoveredIndexDevice>()
    OnboardingStageContent {
        Text(
            text = if (finished) "Index 01 paired" else "Find your Index 01",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (finished) {
                "Recordings sync to this phone as WAV files. You can manage the ring from Devices."
            } else {
                "Press the button on the ring to wake it, then pair it."
            },
            textAlign = TextAlign.Center,
        )
        if (!granted) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Bluetooth permission is still needed to scan.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            PebbleElevatedButton(
                text = "Allow Bluetooth",
                onClick = {
                    val context = uiContext ?: return@PebbleElevatedButton
                    val permission = needed ?: return@PebbleElevatedButton
                    scope.launch {
                        permissionRequester.requestPermission(permission, context)
                    }
                },
                primaryColor = true,
            )
        }
        if (scanning && !finished) {
            Spacer(modifier = Modifier.height(16.dp))
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(8.dp))
            Text("Searching...")
        }
        discovered.forEach { ring ->
            Spacer(modifier = Modifier.height(16.dp))
            RingChoice(
                ring = ring,
                uiContext = uiContext,
                companionDevice = companionDevice,
                onFailure = { failure = it },
                onPaired = {
                    failure = null
                    paired = true
                    libIndex.stopScan()
                },
            )
        }
        failure?.let { message ->
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
        if (granted && !finished && !scanning && discovered.isEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No ring yet. Wake it with the button, then scan again.",
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(12.dp))
            PebbleElevatedButton(
                text = "Scan again",
                onClick = {
                    failure = null
                    libIndex.startScan()
                },
                primaryColor = true,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        PebbleElevatedButton(
            text = if (finished) "Continue" else "Skip for now",
            onClick = onDone,
            primaryColor = true,
        )
    }
}

@Composable
private fun RingChoice(
    ring: DiscoveredIndexDevice,
    uiContext: PlatformUiContext?,
    companionDevice: CompanionDevice,
    onFailure: (String) -> Unit,
    onPaired: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val pairing = ring.pairingState is IndexPairingState.Pairing
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(ring.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = when {
                ring.isFailsafe -> "Recovery mode"
                ring.currentImage == IndexImage.ProductionTest -> "Factory test mode"
                pairing -> "Pairing..."
                else -> "Available to pair"
            },
            textAlign = TextAlign.Center,
        )
        if (!ring.isFailsafe && ring.currentImage == IndexImage.Primary && !pairing) {
            Spacer(modifier = Modifier.height(8.dp))
            PebbleElevatedButton(
                text = "Pair",
                onClick = {
                    scope.launch {
                        val result = try {
                            uiContext?.let { companionDevice.registerDevice(ring.identifier, it) }
                            ring.pair()
                        } catch (_: Exception) {
                            null
                        }
                        when (result) {
                            is IndexPairingResult.Success -> onPaired()
                            is IndexPairingResult.EraseFailed ->
                                onFailure("The ring could not be prepared. Wake it and try again.")
                            else -> onFailure("Pairing didn't finish. Wake the ring and try again.")
                        }
                    }
                },
                primaryColor = true,
            )
        }
    }
}
