package com.anopticlabs.gravel.notebook.ui

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.backup.dateTime
import com.anopticlabs.gravel.notebook.storage.KeyPlacement
import com.anopticlabs.gravel.notebook.storage.NotebookPart
import com.anopticlabs.gravel.notebook.storage.RecoveryPlan
import com.anopticlabs.gravel.notebook.storage.StorageTrouble
import java.time.ZoneId

/** The damaged parts a Start fresh restarts, and the ones it rebuilds from what remains. */
internal fun lostAndRebuilt(parts: Set<NotebookPart>): Pair<List<NotebookPart>, List<NotebookPart>> =
    NotebookPart.entries.filter { it in parts }.partition { !it.rebuilt }

/** [parts] in plain words, joined as an English list. */
internal fun partsText(r: Resources, parts: List<NotebookPart>): String {
    val names = parts.map { r.getString(partName(it)) }
    return when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> r.getString(R.string.notebook_trouble_list, names.dropLast(1).joinToString(", "), names.last())
    }
}

/** Where the Notebook key is kept, as Settings shows it. */
fun keyLocationText(r: Resources, placement: KeyPlacement): String = r.getString(
    when (placement) {
        KeyPlacement.STRONGBOX -> R.string.notebook_key_strongbox
        KeyPlacement.TEE -> R.string.notebook_key_tee
        KeyPlacement.SECURE_HARDWARE -> R.string.notebook_key_secure_hardware
        KeyPlacement.SOFTWARE -> R.string.notebook_key_software
        KeyPlacement.UNKNOWN -> R.string.notebook_key_unknown
    },
)

private fun partName(part: NotebookPart): Int = when (part) {
    NotebookPart.NOTE_LIST -> R.string.notebook_part_note_list
    NotebookPart.FOLDERS -> R.string.notebook_part_folders
    NotebookPart.FOLDER_TAGS -> R.string.notebook_part_folder_tags
    NotebookPart.RECORDINGS -> R.string.notebook_part_recordings
    NotebookPart.REMINDERS -> R.string.notebook_part_reminders
    NotebookPart.REMINDER_HISTORY -> R.string.notebook_part_reminder_history
    NotebookPart.COMPLETIONS -> R.string.notebook_part_completions
}

/** What becomes of [part] when it starts again. */
private fun partOutcome(part: NotebookPart): Int = when (part) {
    NotebookPart.NOTE_LIST -> R.string.notebook_fresh_note_list
    NotebookPart.FOLDERS -> R.string.notebook_fresh_folders
    NotebookPart.FOLDER_TAGS -> R.string.notebook_fresh_folder_tags
    NotebookPart.RECORDINGS -> R.string.notebook_fresh_recordings
    NotebookPart.REMINDERS -> R.string.notebook_fresh_reminders
    NotebookPart.REMINDER_HISTORY -> R.string.notebook_fresh_reminder_history
    NotebookPart.COMPLETIONS -> R.string.notebook_fresh_completions
}

/** The parts of [parts] a start fresh, or a restore with that plan, has a sentence for; the reminders' covers their history. */
internal fun outcomeParts(parts: Set<NotebookPart>): List<NotebookPart> =
    NotebookPart.entries.filter { it in parts && !(it == NotebookPart.REMINDER_HISTORY && NotebookPart.REMINDERS in parts) }

/** What a start fresh of [parts], or a restore with that plan, does to each of them, one sentence a part. */
internal fun outcomesText(r: Resources, parts: Set<NotebookPart>): String = outcomeParts(parts).joinToString(" ") { r.getString(partOutcome(it)) }

/** A sentence the storage card can show. */
internal enum class TroubleText {
    KEY_GONE, KEY_GONE_CHOICES, UNREADABLE, DAMAGED, DAMAGED_AUTOMATIC, DAMAGED_FILE, NOT_IN_BACKUPS,
    ALSO_REBUILT, REBUILD, REBUILD_MANY, FAILED, KEY_STORE,
}

/** One line of the card: [text], filled with [parts] in plain words or the date [at] where it takes one. */
internal data class TroubleLine(val text: TroubleText, val parts: List<NotebookPart> = emptyList(), val at: Long? = null)

/** A button the storage card shows. */
internal enum class TroubleAction { TRY_AGAIN, START_OVER, START_OVER_ANYWAY, RESTORE, REBUILD, START_FRESH }

private fun keyStoreFailed(trouble: StorageTrouble, run: RecoveryRunState) = run.failedOn == trouble && run.failed == RecoveryFailure.KEY_STORE

/** The card's lines for [trouble], with the last action's failure when it was for this kind of trouble. */
internal fun troubleLines(trouble: StorageTrouble, run: RecoveryRunState, automaticBackupAt: Long?): List<TroubleLine> = buildList {
    when (trouble) {
        StorageTrouble.KeyGone -> {
            add(TroubleLine(TroubleText.KEY_GONE))
            if (!keyStoreFailed(trouble, run)) add(TroubleLine(TroubleText.KEY_GONE_CHOICES))
        }
        StorageTrouble.Unreadable -> add(TroubleLine(TroubleText.UNREADABLE))
        is StorageTrouble.Damaged -> addAll(damagedLines(trouble.parts, automaticBackupAt))
    }
    // A part that was put right before the failure changes the trouble, so the kind is compared.
    if (run.failedOn != null && run.failedOn::class == trouble::class) {
        add(TroubleLine(if (run.failed == RecoveryFailure.KEY_STORE) TroubleText.KEY_STORE else TroubleText.FAILED))
    }
}

/** The card's buttons for [trouble]; Start over anyway only after a start over Android's key store refused. */
internal fun troubleActions(trouble: StorageTrouble, run: RecoveryRunState): List<TroubleAction> = when (trouble) {
    StorageTrouble.Unreadable -> listOf(TroubleAction.TRY_AGAIN)
    StorageTrouble.KeyGone ->
        if (keyStoreFailed(trouble, run)) listOf(TroubleAction.START_OVER_ANYWAY) else listOf(TroubleAction.START_OVER, TroubleAction.RESTORE)
    is StorageTrouble.Damaged -> {
        val lost = lostAndRebuilt(trouble.parts).first
        when {
            lost.isEmpty() -> listOf(TroubleAction.REBUILD)
            lost.any { it.inBackups } -> listOf(TroubleAction.START_FRESH, TroubleAction.RESTORE)
            else -> listOf(TroubleAction.START_FRESH)
        }
    }
}

private fun damagedLines(parts: Set<NotebookPart>, automaticBackupAt: Long?): List<TroubleLine> {
    val (lost, rebuilt) = lostAndRebuilt(parts)
    return buildList {
        add(TroubleLine(TroubleText.DAMAGED, lost + rebuilt))
        when {
            lost.isEmpty() -> add(TroubleLine(if (rebuilt.size == 1) TroubleText.REBUILD else TroubleText.REBUILD_MANY))
            lost.none { it.inBackups } -> add(TroubleLine(TroubleText.NOT_IN_BACKUPS))
            automaticBackupAt != null -> add(TroubleLine(TroubleText.DAMAGED_AUTOMATIC, at = automaticBackupAt))
            else -> add(TroubleLine(TroubleText.DAMAGED_FILE))
        }
        if (lost.isNotEmpty() && rebuilt.isNotEmpty()) add(TroubleLine(TroubleText.ALSO_REBUILT, rebuilt))
    }
}

private fun TroubleText.res(): Int = when (this) {
    TroubleText.KEY_GONE -> R.string.notebook_trouble_key_gone
    TroubleText.KEY_GONE_CHOICES -> R.string.notebook_trouble_key_gone_choices
    TroubleText.UNREADABLE -> R.string.notebook_problem_unreadable
    TroubleText.DAMAGED -> R.string.notebook_trouble_damaged
    TroubleText.DAMAGED_AUTOMATIC -> R.string.notebook_trouble_damaged_automatic
    TroubleText.DAMAGED_FILE -> R.string.notebook_trouble_damaged_file
    TroubleText.NOT_IN_BACKUPS -> R.string.notebook_trouble_not_in_backups
    TroubleText.ALSO_REBUILT -> R.string.notebook_trouble_also_rebuilt
    TroubleText.REBUILD -> R.string.notebook_trouble_rebuild_body
    TroubleText.REBUILD_MANY -> R.string.notebook_trouble_rebuild_body_many
    TroubleText.FAILED -> R.string.notebook_trouble_failed
    TroubleText.KEY_STORE -> R.string.notebook_trouble_key_store
}

private fun TroubleLine.text(r: Resources): String = when {
    at != null -> r.getString(text.res(), dateTime(at, ZoneId.systemDefault()))
    parts.isNotEmpty() -> r.getString(text.res(), partsText(r, parts))
    else -> r.getString(text.res())
}

/** A restore's plan as its confirmation states it. */
internal fun planText(r: Resources, plan: RecoveryPlan): String {
    val fromBackup = " " + r.getString(R.string.notebook_restore_plan_reminders)
    return when (plan) {
        RecoveryPlan.StartOver -> r.getString(R.string.notebook_restore_plan_over) + fromBackup
        is RecoveryPlan.StartFresh -> r.getString(R.string.notebook_restore_plan_fresh) + " " +
            outcomeParts(plan.parts).joinToString(" ") { r.getString(partOutcome(it)) + if (it == NotebookPart.REMINDERS) fromBackup else "" }
    }
}

private enum class Confirming { START_FRESH, START_OVER, START_OVER_ANYWAY }

/** What the Notebook tab shows while its storage cannot be used, with the actions that put it right. */
@Composable
internal fun StorageTroubleCard(status: NotebookStatus, run: RecoveryRunState, viewModel: NotebookViewModel) {
    val trouble = status.trouble ?: return
    val r = LocalContext.current.resources
    // Keyed on the kind of trouble, so a dialog opened for one kind is dropped when another kind shows.
    var confirming by rememberSaveable(trouble::class) { mutableStateOf<Confirming?>(null) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            troubleLines(trouble, run, status.automaticBackupAt).forEach { Text(it.text(r), color = MaterialTheme.colorScheme.onErrorContainer) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                if (run.busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                for (action in troubleActions(trouble, run)) {
                    when (action) {
                        TroubleAction.TRY_AGAIN -> Action(R.string.notebook_try_again, run.busy, viewModel::retry)
                        TroubleAction.START_OVER -> Action(R.string.notebook_trouble_start_over, run.busy) { confirming = Confirming.START_OVER }
                        TroubleAction.START_OVER_ANYWAY ->
                            Action(R.string.notebook_trouble_start_over_anyway, run.busy) { confirming = Confirming.START_OVER_ANYWAY }
                        TroubleAction.RESTORE -> Action(R.string.notebook_restore, run.busy) { viewModel.restore(status) }
                        TroubleAction.REBUILD -> Action(R.string.notebook_trouble_rebuild, run.busy) { (trouble as? StorageTrouble.Damaged)?.let(viewModel::startFresh) }
                        TroubleAction.START_FRESH -> Action(R.string.notebook_trouble_start_fresh, run.busy) { confirming = Confirming.START_FRESH }
                    }
                }
            }
        }
    }
    when (confirming) {
        Confirming.START_FRESH -> (trouble as? StorageTrouble.Damaged)?.let { damaged ->
            val body = r.getString(R.string.notebook_trouble_start_fresh_body) + "\n\n" + outcomesText(r, damaged.parts)
            ConfirmReset(R.string.notebook_trouble_start_fresh_title, body, R.string.notebook_trouble_start_fresh, { confirming = null }) {
                confirming = null
                viewModel.startFresh(damaged)
            }
        }
        Confirming.START_OVER -> ConfirmReset(
            R.string.notebook_trouble_start_over_title, stringResource(R.string.notebook_trouble_start_over_body), R.string.notebook_trouble_start_over,
            { confirming = null },
        ) {
            confirming = null
            viewModel.startOver()
        }
        Confirming.START_OVER_ANYWAY -> if (keyStoreFailed(trouble, run)) ConfirmReset(
            R.string.notebook_trouble_start_over_anyway_title, stringResource(R.string.notebook_trouble_start_over_anyway_body),
            R.string.notebook_trouble_start_over_anyway, { confirming = null },
        ) {
            confirming = null
            viewModel.startOver(force = true)
        }
        null -> Unit
    }
}

@Composable
private fun Action(label: Int, busy: Boolean, onClick: () -> Unit) {
    TextButton(enabled = !busy, onClick = onClick) { Text(stringResource(label)) }
}

@Composable
private fun ConfirmReset(title: Int, body: String, confirm: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = notebookDialogProperties(),
        title = { Text(stringResource(title)) },
        text = { Text(body) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(confirm), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.notebook_cancel)) } },
    )
}
