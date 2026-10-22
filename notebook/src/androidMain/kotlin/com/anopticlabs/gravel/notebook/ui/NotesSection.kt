package com.anopticlabs.gravel.notebook.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.notes.ARCHIVE_FOLDER
import com.anopticlabs.gravel.notebook.notes.RESERVED_FOLDERS
import com.anopticlabs.gravel.notebook.notes.routingHasNoTags
import com.anopticlabs.gravel.notebook.routing.UNCLASSIFIED_FOLDER
import com.anopticlabs.gravel.notebook.storage.FolderItem
import com.anopticlabs.gravel.notebook.storage.NoteItem
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** The Notes section: the page [state] is on, with its sheet, dialog and editor over it; its reads run while it is shown. */
@Composable
internal fun NotesSection(
    viewModel: NotesViewModel,
    state: NotesState,
    onNewNote: (folder: String) -> Unit,
    showMessage: (String) -> Unit,
) {
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.watch() }
    }
    LaunchedEffect(state.messages) {
        state.messages.firstOrNull()?.let {
            showMessage(messageText(resources, it))
            viewModel.messageShown(it)
        }
    }
    // The most recently added enabled Back handler runs first (activity 1.13.0,
    // OnBackPressedDispatcher.addCallback; navigationevent, NavigationEventProcessor), and the home
    // screen has its own; keyed so this one is added again each time it becomes enabled.
    key(state.canGoBack) {
        BackHandler(enabled = state.canGoBack) { viewModel.back() }
    }
    when (val page = state.page) {
        NotesPage.Folders -> FolderGridPage(state.grid, viewModel)
        is NotesPage.Folder -> FolderPage(page.name, state, viewModel, onNewNote)
        is NotesPage.Note -> NotePage(state.note, viewModel)
    }
    state.sheet?.let { NotesSheetView(it, state.busy, viewModel) }
    state.dialog?.let { NotesDialogView(it, state.busy, viewModel) }
    state.link?.let { link -> LinkDialog(link, viewModel) }
    state.editing?.let { draft ->
        NoteEditor(
            draft = draft,
            loadFolders = { emptyList() },
            save = viewModel::saveEdit,
            onSaved = {},
            onDiscard = viewModel::closeEdit,
        )
    }
}

internal fun messageText(resources: Resources, message: NotesMessage): String = when (message) {
    is NotesMessage.FolderDeleted ->
        if (message.moved == 0) resources.getString(R.string.notebook_folder_deleted_empty)
        else resources.getQuantityString(R.plurals.notebook_folder_deleted, message.moved, message.moved)
    is NotesMessage.FolderKept -> resources.getQuantityString(R.plurals.notebook_folder_kept, message.remaining, message.remaining)
    NotesMessage.CreatedWithoutTags -> resources.getString(R.string.notebook_folder_created_without_tags)
    is NotesMessage.NotesLeft -> resources.getQuantityString(R.plurals.notebook_notes_left, message.count, message.count)
    NotesMessage.NotChanged -> resources.getString(R.string.notebook_not_changed)
    NotesMessage.NoteGone -> resources.getString(R.string.notebook_note_gone)
    NotesMessage.NoteSaved -> resources.getString(R.string.notebook_note_saved)
    NotesMessage.LinkRefused -> resources.getString(R.string.notebook_link_refused)
    NotesMessage.NoAppForLink -> resources.getString(R.string.notebook_link_no_app)
}

@Composable
internal fun folderLabel(name: String): String = when (name) {
    UNCLASSIFIED_FOLDER -> stringResource(R.string.notebook_folder_unclassified)
    ARCHIVE_FOLDER -> stringResource(R.string.notebook_folder_archive)
    else -> name.replaceFirstChar { it.uppercaseChar() }
}

@Composable
private fun PageHeader(
    title: String,
    onBack: () -> Unit,
    backIcon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack,
    backLabel: String = stringResource(R.string.notebook_back),
    actions: @Composable () -> Unit = {},
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(backIcon, contentDescription = backLabel) }
        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) { content() }
}

@Composable
internal fun Problem(problem: StorageProblem, onRetry: () -> Unit) {
    Centered {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    when (problem) {
                        StorageProblem.KEY_MISSING -> R.string.notebook_problem_key_missing
                        StorageProblem.DAMAGED -> R.string.notebook_problem_damaged
                        StorageProblem.UNREADABLE -> R.string.notebook_problem_unreadable
                    }
                ),
                color = MaterialTheme.colorScheme.error,
            )
            if (problem == StorageProblem.UNREADABLE) TextButton(onClick = onRetry) { Text(stringResource(R.string.notebook_try_again)) }
        }
    }
}

// Folder grid

@Composable
private fun FolderGridPage(grid: Loadable<FolderGrid>, viewModel: NotesViewModel) {
    when (grid) {
        Loadable.Loading -> Centered { CircularProgressIndicator() }
        is Loadable.Failed -> Problem(grid.problem, viewModel::retry)
        is Loadable.Ready -> LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            val listed = grid.value.listed
            val tags = grid.value.tags
            grid.value.problem?.let { problem ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PartProblem(if (listed == null) R.string.notebook_folder_list_unreadable else R.string.notebook_folder_tags_unreadable, problem, viewModel::retry)
                }
            }
            if (listed != null && tags != null && routingHasNoTags(listed.toList(), tags)) {
                item(span = { GridItemSpan(maxLineSpan) }) { RoutingHint() }
            }
            items(grid.value.folders, key = { it.name }) { folder ->
                FolderCard(
                    folder,
                    onOpen = { viewModel.openFolder(folder.name) },
                    onLongPress = if (folder.name in RESERVED_FOLDERS) null else { { viewModel.showFolderActions(folder) } },
                )
            }
            item { FolderTile(Icons.Filled.CreateNewFolder, stringResource(R.string.notebook_new_folder), null, onOpen = viewModel::askCreateFolder) }
        }
    }
}

@Composable
private fun PartProblem(text: Int, problem: StorageProblem, onRetry: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.small) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(stringResource(text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (problem == StorageProblem.UNREADABLE) {
                TextButton(onClick = onRetry, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.notebook_try_again)) }
            }
        }
    }
}

@Composable
private fun RoutingHint() {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.notebook_routing_hint), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FolderCard(folder: FolderItem, onOpen: () -> Unit, onLongPress: (() -> Unit)?) {
    FolderTile(
        icon = if (folder.name == ARCHIVE_FOLDER) Icons.Filled.Archive else Icons.Filled.Folder,
        label = folderLabel(folder.name),
        count = pluralStringResource(R.plurals.notebook_folder_count, folder.count, folder.count),
        onOpen = onOpen,
        onLongPress = onLongPress,
    )
}

@Composable
private fun FolderTile(icon: ImageVector, label: String, count: String?, onOpen: () -> Unit, onLongPress: (() -> Unit)? = null) {
    Card(
        modifier = Modifier.fillMaxWidth().height(96.dp).combinedClickable(onClick = onOpen, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column {
                Text(label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                count?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

// A folder's notes

@Composable
private fun FolderPage(folder: String, state: NotesState, viewModel: NotesViewModel, onNewNote: (String) -> Unit) {
    val notes = if (state.notesFolder == folder) state.notes else Loadable.Loading
    val shown = (notes as? Loadable.Ready)?.value
    val selection = state.selection
    Column(Modifier.fillMaxSize()) {
        if (selection != null) {
            PageHeader(
                title = pluralStringResource(R.plurals.notebook_selected, selection.size, selection.size),
                onBack = viewModel::back,
                backIcon = Icons.Filled.Close,
                backLabel = stringResource(R.string.notebook_exit_selection),
            ) {
                SelectionMenu(folder, selection, enabled = selection.isNotEmpty() && !state.busy, viewModel)
            }
        } else {
            val label = folderLabel(folder)
            PageHeader(title = label, onBack = viewModel::back) {
                if (state.takesNewNotes(folder)) {
                    IconButton(onClick = { onNewNote(folder) }) {
                        Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = stringResource(R.string.notebook_new_note_in, label))
                    }
                }
                if (folder == ARCHIVE_FOLDER && !shown.isNullOrEmpty()) {
                    IconButton(enabled = !state.busy, onClick = viewModel::askEmptyArchive) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = stringResource(R.string.notebook_empty_archive))
                    }
                }
            }
        }
        when (notes) {
            Loadable.Loading -> Centered { CircularProgressIndicator() }
            is Loadable.Failed -> Problem(notes.problem, viewModel::retry)
            is Loadable.Ready -> if (notes.value.isEmpty()) {
                Centered { Text(stringResource(R.string.notebook_no_notes, folderLabel(folder)), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                val zone = remember { TimeZone.currentSystemDefault() }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                    if (selection != null) {
                        item {
                            val all = selection.size == notes.value.size
                            Row(
                                Modifier.fillMaxWidth().combinedClickable(onClick = if (all) viewModel::selectNone else viewModel::selectAll),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(checked = all, onCheckedChange = { if (all) viewModel.selectNone() else viewModel.selectAll() })
                                Text(stringResource(R.string.notebook_select_all))
                            }
                        }
                    }
                    items(notes.value, key = { it.id }) { note ->
                        NoteRow(
                            note,
                            date = noteRowDate(note, zone),
                            selected = selection?.let { note.id in it },
                            onClick = { if (selection != null) viewModel.toggle(note.id) else viewModel.openNote(note.id) },
                            onLongPress = if (selection == null) { { viewModel.showNoteActions(note) } } else null,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionMenu(folder: String, selection: Set<String>, enabled: Boolean, viewModel: NotesViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(enabled = enabled, onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.notebook_more_actions))
        }
        DropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }, properties = notebookMenuProperties()) {
            if (folder == ARCHIVE_FOLDER) {
                MenuEntry(Icons.Filled.Restore, R.string.notebook_restore_to) { open = false; viewModel.askMove(selection, restore = true) }
                MenuEntry(Icons.Filled.DeleteForever, R.string.notebook_delete_permanently, danger = true) { open = false; viewModel.askDeleteNotes(selection) }
            } else {
                MenuEntry(Icons.AutoMirrored.Filled.DriveFileMove, R.string.notebook_move_to) { open = false; viewModel.askMove(selection, restore = false) }
                MenuEntry(Icons.Filled.Archive, R.string.notebook_archive) { open = false; viewModel.archive(selection) }
            }
        }
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: Int, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    DropdownMenuItem(
        text = { Text(stringResource(label), color = color) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = color) },
        onClick = onClick,
    )
}

@Composable
private fun NoteRow(note: NoteItem, date: String, selected: Boolean?, onClick: () -> Unit, onLongPress: (() -> Unit)?) {
    Card(
        modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress),
        colors = CardDefaults.cardColors(
            containerColor = if (selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) Checkbox(checked = selected, onCheckedChange = { onClick() })
            Column(Modifier.weight(1f)) {
                Text(note.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// One note

@Composable
private fun NotePage(view: NoteView, viewModel: NotesViewModel) {
    val shown = (view as? NoteView.Shown)?.note
    val archived = shown?.folder == ARCHIVE_FOLDER
    Column(Modifier.fillMaxSize()) {
        PageHeader(title = shown?.displayTitle.orEmpty(), onBack = viewModel::back) {
            if (shown?.editable == true) {
                IconButton(onClick = viewModel::startEdit) { Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.notebook_edit)) }
            }
        }
        when (view) {
            NoteView.Loading -> Centered { CircularProgressIndicator() }
            NoteView.Gone -> Centered { Text(stringResource(R.string.notebook_note_gone_here), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            is NoteView.Failed -> Problem(view.problem, viewModel::retry)
            is NoteView.Shown -> Column(Modifier.fillMaxSize()) {
                if (view.note.tooLongToEdit && !archived) {
                    Text(
                        stringResource(R.string.notebook_too_long_to_edit),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
                NoteBody(view.note.body, onLink = viewModel::linkTapped, modifier = Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

// Sheets and dialogs

@Composable
private fun NotesSheetView(sheet: NotesSheet, busy: Boolean, viewModel: NotesViewModel) {
    ModalBottomSheet(onDismissRequest = viewModel::dismissSheet, properties = notebookSheetProperties()) {
        when (sheet) {
            is NotesSheet.FolderActions -> {
                val folder = sheet.folder
                SheetTitle(folderLabel(folder.name))
                SheetEntry(Icons.Filled.Edit, stringResource(R.string.notebook_edit_tags), stringResource(R.string.notebook_edit_tags_hint), enabled = sheet.canEditTags && !busy) {
                    viewModel.askEditTags(folder.name)
                }
                SheetEntry(
                    Icons.Filled.DeleteForever,
                    stringResource(R.string.notebook_delete_folder),
                    if (folder.count == 0) stringResource(R.string.notebook_delete_folder_empty)
                    else pluralStringResource(R.plurals.notebook_delete_folder_moves, folder.count, folder.count),
                    enabled = !busy,
                    danger = true,
                ) { viewModel.askDeleteFolder(folder) }
            }
            is NotesSheet.NoteActions -> {
                val id = sheet.note.id
                SheetTitle(sheet.note.title)
                if (sheet.note.folder == ARCHIVE_FOLDER) {
                    SheetEntry(Icons.Filled.Restore, stringResource(R.string.notebook_restore_to), enabled = !busy) { viewModel.askMove(setOf(id), restore = true) }
                    SheetEntry(Icons.Filled.DeleteForever, stringResource(R.string.notebook_delete_permanently), enabled = !busy, danger = true) {
                        viewModel.askDeleteNotes(setOf(id))
                    }
                } else {
                    SheetEntry(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.notebook_move_to), enabled = !busy) {
                        viewModel.askMove(setOf(id), restore = false)
                    }
                    SheetEntry(Icons.Filled.Archive, stringResource(R.string.notebook_archive), enabled = !busy) { viewModel.archive(setOf(id)) }
                    SheetEntry(Icons.Filled.Edit, stringResource(R.string.notebook_edit)) { viewModel.editNote(id) }
                }
                SheetEntry(Icons.Filled.CheckBox, stringResource(R.string.notebook_select)) { viewModel.select(id) }
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    HorizontalDivider()
}

@Composable
private fun SheetEntry(
    icon: ImageVector,
    label: String,
    supporting: String? = null,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    ListItem(
        headlineContent = { Text(label, color = color) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null, tint = color) },
        modifier = Modifier.combinedClickable(enabled = enabled, onClick = onClick),
    )
}

@Composable
private fun NotesDialogView(dialog: NotesDialog, busy: Boolean, viewModel: NotesViewModel) {
    when (dialog) {
        is NotesDialog.CreateFolder -> CreateFolderDialog(dialog.error, busy, viewModel)
        is NotesDialog.EditTags -> EditTagsDialog(dialog, busy, viewModel)
        is NotesDialog.DeleteFolder -> ConfirmDialog(
            title = stringResource(R.string.notebook_delete_folder_title, folderLabel(dialog.folder.name)),
            body = if (dialog.folder.count == 0) stringResource(R.string.notebook_delete_folder_empty)
            else pluralStringResource(R.plurals.notebook_delete_folder_moves, dialog.folder.count, dialog.folder.count),
            busy = busy,
            onConfirm = { viewModel.deleteFolder(dialog.folder.name) },
            onDismiss = viewModel::dismissDialog,
        )
        is NotesDialog.MoveNotes -> MoveDialog(dialog, busy, viewModel)
        is NotesDialog.DeleteNotes -> ConfirmDialog(
            title = pluralStringResource(R.plurals.notebook_delete_notes_title, dialog.ids.size, dialog.ids.size),
            body = stringResource(R.string.notebook_cannot_undo),
            busy = busy,
            onConfirm = { viewModel.deleteForGood(dialog.ids) },
            onDismiss = viewModel::dismissDialog,
        )
        is NotesDialog.EmptyArchive -> ConfirmDialog(
            title = stringResource(R.string.notebook_empty_archive_title),
            body = pluralStringResource(R.plurals.notebook_empty_archive_body, dialog.ids.size, dialog.ids.size),
            busy = busy,
            onConfirm = { viewModel.deleteForGood(dialog.ids) },
            onDismiss = viewModel::dismissDialog,
        )
    }
}

@Composable
private fun LinkDialog(link: String, viewModel: NotesViewModel) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = viewModel::dismissLink,
        properties = notebookDialogProperties(),
        title = { Text(stringResource(R.string.notebook_link_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.notebook_link_body))
                NoteLinkSafety.host(link)?.let { host ->
                    Text(stringResource(R.string.notebook_link_host, host), style = MaterialTheme.typography.titleSmall)
                }
                Box(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    Text(link, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (openLink(context, link)) viewModel.dismissLink() else viewModel.linkNotOpened()
            }) { Text(stringResource(R.string.notebook_link_open)) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissLink) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

/**
 * The intent that opens an allowed [link]: only activities that declare they are safe to start
 * from a link take it (android16-release, Intent.CATEGORY_BROWSABLE), and the scheme is lowercased
 * (Intent.setDataAndNormalize).
 */
fun linkIntent(link: String): Intent = Intent(Intent.ACTION_VIEW).setDataAndNormalize(Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE)

private fun openLink(context: Context, link: String): Boolean = try {
    context.startActivity(linkIntent(link))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

@Composable
private fun ConfirmDialog(title: String, body: String, busy: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = notebookDialogProperties(),
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onConfirm) { Text(stringResource(R.string.notebook_delete), color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@Composable
private fun CreateFolderDialog(error: CreateFolderError?, busy: Boolean, viewModel: NotesViewModel) {
    var name by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        properties = notebookDialogProperties(),
        title = { Text(stringResource(R.string.notebook_new_folder)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = error != null && error != CreateFolderError.NOT_SAVED,
                    label = { Text(stringResource(R.string.notebook_folder_name)) },
                    placeholder = { Text(stringResource(R.string.notebook_folder_name_example)) },
                    supportingText = { Text(stringResource(R.string.notebook_folder_name_rule)) },
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notebook_folder_tags)) },
                    placeholder = { Text(stringResource(R.string.notebook_folder_tags_example)) },
                    supportingText = { Text(stringResource(R.string.notebook_folder_tags_rule)) },
                )
                error?.let {
                    Text(
                        stringResource(
                            when (it) {
                                CreateFolderError.INVALID -> R.string.notebook_folder_invalid
                                CreateFolderError.RESERVED -> R.string.notebook_folder_reserved
                                CreateFolderError.EXISTS -> R.string.notebook_folder_exists
                                CreateFolderError.NOT_SAVED -> R.string.notebook_folder_not_saved
                            }
                        ),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && !busy, onClick = { viewModel.createFolder(name, tags) }) {
                Text(stringResource(R.string.notebook_create))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@Composable
private fun EditTagsDialog(dialog: NotesDialog.EditTags, busy: Boolean, viewModel: NotesViewModel) {
    var csv by remember(dialog.folder) { mutableStateOf(dialog.csv) }
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        properties = notebookDialogProperties(),
        title = { Text(stringResource(R.string.notebook_tags_for, folderLabel(dialog.folder))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = csv,
                    onValueChange = { csv = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.notebook_tags)) },
                    placeholder = { Text(stringResource(R.string.notebook_folder_tags_example)) },
                    supportingText = { Text(stringResource(R.string.notebook_tags_rule)) },
                )
                if (dialog.failed) Text(stringResource(R.string.notebook_tags_not_saved), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = { viewModel.saveTags(dialog.folder, csv) }) { Text(stringResource(R.string.notebook_save)) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

@Composable
private fun MoveDialog(dialog: NotesDialog.MoveNotes, busy: Boolean, viewModel: NotesViewModel) {
    AlertDialog(
        onDismissRequest = viewModel::dismissDialog,
        properties = notebookDialogProperties(),
        title = { Text(stringResource(R.string.notebook_choose_folder)) },
        text = {
            if (dialog.targets.isEmpty()) {
                Text(stringResource(R.string.notebook_no_other_folder))
            } else {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    for (target in dialog.targets) {
                        ListItem(
                            headlineContent = { Text(folderLabel(target)) },
                            leadingContent = { Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                            modifier = Modifier.combinedClickable(enabled = !busy, onClick = { viewModel.move(dialog.ids, target) }),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = viewModel::dismissDialog) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}

/** A note row's date: the note's own date, else when it was last changed, in [zone]. */
internal fun noteRowDate(note: NoteItem, zone: TimeZone): String {
    note.date?.let { return it }
    val t = Instant.fromEpochMilliseconds(note.modifiedAtMillis).toLocalDateTime(zone)
    return "${t.year}-${t.month.number.pad()}-${t.day.pad()} ${t.hour.pad()}:${t.minute.pad()}"
}

private fun Int.pad() = toString().padStart(2, '0')
