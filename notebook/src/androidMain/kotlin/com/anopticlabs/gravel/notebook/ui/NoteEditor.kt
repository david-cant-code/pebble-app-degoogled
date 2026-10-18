package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.sources.TypedNotes
import kotlinx.coroutines.launch

enum class EditorError { TITLE_MISSING, TOO_LARGE, NOT_SAVED, CHANGED, GONE, ARCHIVED }

sealed interface EditorOutcome {
    data class Saved(val folder: String) : EditorOutcome

    data class Refused(val error: EditorError) : EditorOutcome
}

/**
 * A full-screen editor for [draft], a new note with a folder picker or an edit of [NoteDraft.edit]
 * without one. Once changed, it closes on a save or a confirmed discard, so a refused save leaves
 * what was typed in place.
 */
@Composable
internal fun NoteEditor(
    draft: NoteDraft,
    loadFolders: suspend () -> List<String>,
    save: suspend (NoteDraft) -> EditorOutcome,
    onSaved: (folder: String) -> Unit,
    onDiscard: () -> Unit,
) {
    var folders by remember { mutableStateOf(listOf(draft.folder)) }
    var error by remember { mutableStateOf<EditorError?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var atLimit by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (draft.edit == null) {
        LaunchedEffect(Unit) {
            folders = (listOf(draft.folder) + loadFolders()).distinct()
        }
    }
    val close = {
        if (!saving) {
            if (draft.changed) confirmDiscard = true else onDiscard()
        }
    }
    Dialog(onDismissRequest = close, properties = notebookDialogProperties(fullScreen = true)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().imePadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(enabled = !saving, onClick = close) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.notebook_editor_close))
                    }
                    Text(
                        stringResource(if (draft.edit == null) R.string.notebook_new_note else R.string.notebook_edit_note),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = !saving,
                        onClick = {
                            saving = true
                            scope.launch {
                                when (val result = save(draft)) {
                                    is EditorOutcome.Saved -> onSaved(result.folder)
                                    is EditorOutcome.Refused -> error = result.error
                                }
                                saving = false
                            }
                        },
                    ) { Text(stringResource(R.string.notebook_editor_save)) }
                }
                OutlinedTextField(
                    value = draft.title,
                    onValueChange = { draft.title = TypedNotes.cutTitle(it) },
                    label = { Text(stringResource(R.string.notebook_editor_title)) },
                    singleLine = true,
                    isError = error == EditorError.TITLE_MISSING,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (draft.edit == null) FolderPicker(folders, draft.folder) { draft.folder = it }
                OutlinedTextField(
                    value = draft.body,
                    onValueChange = { atLimit = !draft.offerBody(it) },
                    label = { Text(stringResource(R.string.notebook_editor_body)) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (atLimit) Text(stringResource(R.string.notebook_editor_at_limit), color = MaterialTheme.colorScheme.error)
                error?.let {
                    Text(
                        stringResource(
                            when (it) {
                                EditorError.TITLE_MISSING -> R.string.notebook_editor_title_missing
                                EditorError.TOO_LARGE -> R.string.notebook_editor_too_large
                                EditorError.NOT_SAVED -> R.string.notebook_editor_not_saved
                                EditorError.CHANGED -> R.string.notebook_editor_changed
                                EditorError.GONE -> R.string.notebook_editor_gone
                                EditorError.ARCHIVED -> R.string.notebook_editor_archived
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        if (confirmDiscard) {
            AlertDialog(
                onDismissRequest = { confirmDiscard = false },
                properties = notebookDialogProperties(),
                text = {
                    Text(stringResource(if (draft.edit == null) R.string.notebook_editor_discard_question else R.string.notebook_editor_discard_changes))
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDiscard = false
                        onDiscard()
                    }) { Text(stringResource(R.string.notebook_editor_discard)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.notebook_editor_keep_editing)) }
                },
            )
        }
    }
}

@Composable
private fun FolderPicker(folders: List<String>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.notebook_editor_folder)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            folders.forEach { name ->
                DropdownMenuItem(text = { Text(name) }, onClick = {
                    onSelect(name)
                    expanded = false
                })
            }
        }
    }
}
