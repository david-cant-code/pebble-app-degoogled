package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.capture.CaptureFailureReason
import com.anopticlabs.gravel.notebook.capture.FailedCapture
import com.anopticlabs.gravel.notebook.capture.MAX_QUEUE_ATTEMPTS
import com.anopticlabs.gravel.notebook.pipeline.MAX_CAPTURE_MILLIS
import kotlinx.coroutines.launch

/** The failed list: each flagged recording with why it failed, Try again (not for one without its recording) and Delete. */
@Composable
internal fun FailedCapturesSheet(failed: List<FailedCapture>, viewModel: NotebookViewModel) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val times = rememberReminderTimes()
    val now = System.currentTimeMillis()
    ModalBottomSheet(onDismissRequest = viewModel::closeFailed, sheetState = sheet, properties = notebookSheetProperties()) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.notebook_failed_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { scope.launch { sheet.hide() }.invokeOnCompletion { viewModel.closeFailed() } }) {
                    Text(stringResource(R.string.notebook_close))
                }
            }
            viewModel.failedNotice?.let {
                Text(
                    stringResource(if (it == FailedNotice.FAILED) R.string.notebook_failed_not_done else R.string.notebook_failed_not_deleted),
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (failed.any { it.retryable }) {
                TextButton(onClick = viewModel::retryAllFailed) { Text(stringResource(R.string.notebook_failed_try_all)) }
            }
            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(failed, key = { it.id }) { capture ->
                    RowCard {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(failureReasonText(capture.reason), style = MaterialTheme.typography.bodyLarge)
                            val recorded = times.format(capture.recordedAtMillis, now)
                            Text(
                                capture.durationMillis?.let { stringResource(R.string.notebook_failed_recorded_length, recorded, clockText(it / 1000)) }
                                    ?: stringResource(R.string.notebook_failed_recorded, recorded),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                                if (capture.retryable) {
                                    TextButton(onClick = { viewModel.retryFailed(capture.id) }) { Text(stringResource(R.string.notebook_try_again)) }
                                }
                                TextButton(onClick = { viewModel.askDelete(capture) }) {
                                    Text(stringResource(R.string.notebook_delete), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    viewModel.deleteAsk?.let {
        AlertDialog(
            onDismissRequest = viewModel::cancelDelete,
            properties = notebookDialogProperties(),
            title = { Text(stringResource(R.string.notebook_failed_delete_title)) },
            text = { Text(stringResource(R.string.notebook_failed_delete_body)) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDelete) { Text(stringResource(R.string.notebook_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = viewModel::cancelDelete) { Text(stringResource(R.string.notebook_cancel)) } },
        )
    }
}

@Composable
private fun failureReasonText(reason: CaptureFailureReason): String = when (reason) {
    CaptureFailureReason.DAMAGED -> stringResource(R.string.notebook_failed_damaged)
    CaptureFailureReason.UNREADABLE_AUDIO -> stringResource(R.string.notebook_failed_unreadable_audio)
    CaptureFailureReason.TOO_LONG -> stringResource(R.string.notebook_failed_too_long, MAX_CAPTURE_MILLIS / 60_000)
    CaptureFailureReason.NO_SPEECH -> stringResource(R.string.notebook_failed_no_speech)
    CaptureFailureReason.SPEECH_TIMEOUT -> stringResource(R.string.notebook_failed_speech_timeout)
    CaptureFailureReason.SPEECH_REFUSED -> stringResource(R.string.notebook_failed_speech_refused)
    CaptureFailureReason.TEXT_TOO_LONG -> stringResource(R.string.notebook_failed_text_too_long)
    CaptureFailureReason.TEXT_TIMEOUT -> stringResource(R.string.notebook_failed_text_timeout)
    CaptureFailureReason.TEXT_REFUSED -> stringResource(R.string.notebook_failed_text_refused)
    CaptureFailureReason.NOT_SAVED -> stringResource(R.string.notebook_failed_not_saved)
    CaptureFailureReason.SERVER_ERROR -> stringResource(R.string.notebook_failed_server_error)
    CaptureFailureReason.TRIES_RAN_OUT -> stringResource(R.string.notebook_failed_tries_ran_out, MAX_QUEUE_ATTEMPTS)
    CaptureFailureReason.UNKNOWN -> stringResource(R.string.notebook_failed_unknown)
}
