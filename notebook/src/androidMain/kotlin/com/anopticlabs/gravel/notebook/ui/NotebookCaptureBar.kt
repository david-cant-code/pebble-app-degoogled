package com.anopticlabs.gravel.notebook.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.anopticlabs.gravel.notebook.R
import com.anopticlabs.gravel.notebook.pipeline.MAX_CAPTURE_MILLIS
import com.anopticlabs.gravel.notebook.sources.RecorderNotice
import com.anopticlabs.gravel.notebook.sources.RecorderState

internal fun clockText(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** The record control, the new-note button and what the last recording or record tap has to say. */
@Composable
internal fun CaptureBar(
    recordingSupported: Boolean?,
    state: RecorderState,
    notice: RecorderNotice?,
    micProblem: MicProblem?,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    onNewNote: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val recording = state is RecorderState.Recording
    val view = LocalView.current
    // The recording stops when no activity is resumed, so a screen timeout must not end it.
    DisposableEffect(view, recording) {
        view.keepScreenOn = recording
        onDispose { view.keepScreenOn = false }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                recordingSupported != true -> Unit
                state == RecorderState.Idle -> FilledIconButton(onClick = onRecord, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.notebook_record))
                }
                state is RecorderState.Recording -> FilledIconButton(
                    onClick = onStop,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.notebook_stop))
                }
                state == RecorderState.Saving -> CircularProgressIndicator(Modifier.size(56.dp))
            }
            Text(
                when (recordingSupported) {
                    null -> ""
                    false -> stringResource(R.string.notebook_typed_only)
                    else -> when {
                        state is RecorderState.Recording ->
                            stringResource(R.string.notebook_recording_time, clockText(state.seconds), clockText(MAX_CAPTURE_MILLIS / 1000L))
                        state == RecorderState.Saving -> stringResource(R.string.notebook_saving_recording)
                        else -> stringResource(R.string.notebook_record_hint)
                    }
                },
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onNewNote, enabled = !recording) {
                Icon(Icons.Filled.EditNote, contentDescription = null)
                Text(stringResource(R.string.notebook_new_note), Modifier.padding(start = 4.dp))
            }
        }
        micProblem?.let { problem ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(
                        when (problem) {
                            MicProblem.DENIED -> R.string.notebook_mic_denied
                            MicProblem.DENIED_FOR_GOOD -> R.string.notebook_mic_denied_for_good
                            MicProblem.NO_WINDOW -> R.string.notebook_mic_not_asked
                        }
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                if (problem == MicProblem.DENIED_FOR_GOOD) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.notebook_open_settings)) }
                }
            }
        }
        if (micProblem == null && notice != null) {
            Text(
                stringResource(
                    when (notice) {
                        RecorderNotice.TOO_SHORT -> R.string.notebook_recording_too_short
                        RecorderNotice.INTERRUPTED -> R.string.notebook_recording_interrupted
                        RecorderNotice.LIMIT_REACHED -> R.string.notebook_recording_limit
                        RecorderNotice.MIC_LOST -> R.string.notebook_recording_mic_lost
                        RecorderNotice.NOT_STARTED -> R.string.notebook_recording_not_started
                        RecorderNotice.NOT_SAVED -> R.string.notebook_recording_not_saved
                    }
                ),
                color = if (notice == RecorderNotice.LIMIT_REACHED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}
