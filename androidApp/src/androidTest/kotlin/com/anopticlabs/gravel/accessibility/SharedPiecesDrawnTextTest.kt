package com.anopticlabs.gravel.accessibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.unit.dp
import coredevices.ui.ConfirmDialog
import coredevices.ui.M3Dialog
import coredevices.ui.PebbleElevatedButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The Material pieces most screens are built from, drawn in the app's themes, error text included.
 *
 * Run with:
 * adb shell am instrument -w -e class com.anopticlabs.gravel.accessibility.SharedPiecesDrawnTextTest \
 *   com.anopticlabs.gravel.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(Parameterized::class)
class SharedPiecesDrawnTextTest(check: DrawnCheck) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun checks() = DrawnCheck.entries
    }

    @get:Rule
    val drawn = DrawnText(check)

    @Test
    fun popupWithTextButtons() {
        drawn.setContent {
            M3Dialog(
                onDismissRequest = {},
                title = { Text("Remove watch?") },
                buttons = {
                    TextButton(onClick = {}) { Text("Cancel") }
                    TextButton(onClick = {}) { Text("Remove") }
                },
            ) { Text("The watch is forgotten on this phone.") }
        }
        drawn.assertReadable()
    }

    @Test
    fun confirmPopup() {
        drawn.setContent {
            ConfirmDialog(remember { mutableStateOf(true) }, "Reset settings?", "Every setting goes back to its default.", "Reset") {}
        }
        drawn.assertReadable()
    }

    @Test
    fun buttons() {
        drawn.setContent {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {}) { Text("Save") }
                OutlinedButton(onClick = {}) { Text("Test connection") }
                TextButton(onClick = {}) { Text("Learn more") }
                PebbleElevatedButton(text = "Get Started", onClick = {}, primaryColor = true)
                PebbleElevatedButton(text = "Skip", onClick = {}, primaryColor = false)
            }
        }
        drawn.assertReadable()
    }

    @Test
    fun listRow() {
        drawn.setContent {
            ListItem(
                overlineContent = { Text("Watch") },
                headlineContent = { Text("Vibration pattern") },
                supportingContent = { Text("Short pulse") },
                trailingContent = { Text("Default") },
            )
        }
        drawn.assertReadable()
    }

    @Test
    fun cards() {
        drawn.setContent {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("Battery", style = MaterialTheme.typography.titleMedium)
                        Text("Last charged yesterday")
                        Text("Could not be read", color = MaterialTheme.colorScheme.error)
                    }
                }
                Text("Could not be saved", color = MaterialTheme.colorScheme.error)
                ElevatedCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Firmware", style = MaterialTheme.typography.titleMedium)
                        Text("Up to date")
                    }
                }
            }
        }
        drawn.assertReadable()
    }

    @Test
    fun textFields() {
        drawn.setContent {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(value = "Pebble Time 2", onValueChange = {}, label = { Text("Name") }, supportingText = { Text("Shown in the app") })
                OutlinedTextField(value = "", onValueChange = {}, label = { Text("Server address") })
                OutlinedTextField(
                    value = "pebble.local",
                    onValueChange = {},
                    label = { Text("Server address") },
                    isError = true,
                    supportingText = { Text("Include the port") },
                )
            }
        }
        drawn.assertReadable()
    }

    @Test
    fun snackbarWithAction() {
        drawn.setContent {
            val host = remember { SnackbarHostState() }
            LaunchedEffect(Unit) { host.showSnackbar("Watchface removed", actionLabel = "Undo", duration = SnackbarDuration.Indefinite) }
            SnackbarHost(host)
        }
        drawn.compose.waitUntil(5_000) { drawn.compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
        drawn.assertReadable()
    }

    @Test
    fun chips() {
        drawn.setContent {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = true, onClick = {}, label = { Text("Watchfaces") })
                FilterChip(selected = false, onClick = {}, label = { Text("Apps") })
            }
        }
        drawn.assertReadable()
    }
}
