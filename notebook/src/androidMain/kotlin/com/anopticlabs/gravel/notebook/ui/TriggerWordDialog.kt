package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.reminders.triggerWordToStore

/**
 * Turns quick reminder completion on with the typed word, explaining it first, or changes the word while it is on
 * ([currentWord] set). The field starts from [startingWord]. [onConfirm] gets the word as it is stored.
 */
@Composable
fun TriggerWordDialog(currentWord: String?, startingWord: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var word by rememberSaveable { mutableStateOf(startingWord) }
    val stored = triggerWordToStore(word)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(if (currentWord == null) R.string.notebook_trigger_title_on else R.string.notebook_trigger_title_change))
        },
        text = {
            NoKeyboardLearning {
                // The explanation is long and the dialog's text slot is bounded, so it scrolls and the field keeps its size.
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (currentWord == null) {
                        Text(stringResource(R.string.notebook_trigger_what))
                        Text(stringResource(R.string.notebook_trigger_never_alone))
                    }
                    Text(stringResource(R.string.notebook_trigger_pick))
                    OutlinedTextField(
                        value = word,
                        onValueChange = { word = it },
                        label = { Text(stringResource(R.string.notebook_trigger_field)) },
                        placeholder = { Text(stringResource(R.string.notebook_trigger_placeholder)) },
                        singleLine = true,
                        isError = word.isNotBlank() && stored == null,
                        supportingText = {
                            Text(stringResource(if (word.isNotBlank() && stored == null) R.string.notebook_trigger_invalid else R.string.notebook_trigger_hint))
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { stored?.let(onConfirm) }, enabled = stored != null && stored != currentWord) {
                Text(stringResource(if (currentWord == null) R.string.notebook_trigger_turn_on else R.string.notebook_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) }
        },
    )
}
