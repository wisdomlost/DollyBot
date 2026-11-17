package com.example.dollybot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.dollybot.R

@Composable
internal fun RecordingFooter(state: DollyBotUiState, actions: DollyBotActions, onStop: () -> Unit) {
    var chooseMode by rememberSaveable { mutableStateOf(false) }
    var discard by rememberSaveable { mutableStateOf(false) }
    val recording = state.recording
    val busy = recording.busy || state.teaching.busy || state.libraryLoading
    val ready = state.device.connection == ConnectionStatus.Connected && state.device.calibrated && !busy
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            if (recording.mode == RecordingMode.Push) {
                Text(
                    stringResource(if (recording.stage == RecordingStage.Starting) {
                        R.string.waiting_for_car_confirmation
                    } else R.string.push_recording),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (recording.message != null) {
                Text(recording.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when (recording.stage) {
                    RecordingStage.Idle -> PlaceholderButton(
                        stringResource(R.string.record), { chooseMode = true },
                        Modifier.weight(1f), enabled = !busy
                    )
                    RecordingStage.PendingSave -> {
                        PlaceholderButton(
                            stringResource(R.string.save_recorded_trajectory),
                            actions::showRecordingSaveDialog, Modifier.weight(1f), enabled = !busy
                        )
                        PlaceholderButton(
                            stringResource(R.string.discard), { discard = true },
                            Modifier.weight(1f), enabled = !busy
                        )
                    }
                    else -> {
                        val running = recording.stage == RecordingStage.Recording
                        PlaceholderButton(
                            stringResource(R.string.start),
                            { recording.mode?.let(actions::startRecording) },
                            Modifier.weight(1f),
                            enabled = ready && recording.stage == RecordingStage.Ready
                        )
                        if (recording.mode == RecordingMode.Waypoints) {
                            PlaceholderButton(
                                stringResource(R.string.capture_waypoint),
                                actions::captureWaypoint, Modifier.weight(1f),
                                enabled = ready && running
                            )
                        }
                        PlaceholderButton(
                            stringResource(
                                if (recording.stage == RecordingStage.Finishing) R.string.finishing_recording else R.string.stop
                            ),
                            onStop, Modifier.weight(1f), isStop = true,
                            enabled = running && !busy
                        )
                    }
                }
            }
        }
    }
    if (chooseMode) {
        AlertDialog(
            onDismissRequest = { chooseMode = false },
            title = { Text(stringResource(R.string.choose_recording_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NavigationRow(stringResource(R.string.continuous_recording), onClick = {
                        actions.selectRecordingMode(RecordingMode.Continuous)
                        chooseMode = false
                    })
                    NavigationRow(stringResource(R.string.capture_waypoint), onClick = {
                        actions.selectRecordingMode(RecordingMode.Waypoints)
                        chooseMode = false
                    })
                    NavigationRow(stringResource(R.string.push_mode), enabled = ready, onClick = {
                        actions.selectRecordingMode(RecordingMode.Push)
                        chooseMode = false
                    })
                    if (!ready) Text(stringResource(R.string.push_requires_connection),
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { chooseMode = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
    if (discard) {
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text(stringResource(R.string.discard_recording_title)) },
            text = { Text(stringResource(R.string.discard_recording_body)) },
            confirmButton = {
                TextButton(onClick = { discard = false; actions.discardRecording() }, enabled = !busy) {
                    Text(stringResource(R.string.discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { discard = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
internal fun RecordingSaveDialog(recording: RecordingUiState, actions: DollyBotActions) {
    val pending = recording.pending ?: return
    if (!recording.showSaveDialog) return
    var name by rememberSaveable(pending.id) { mutableStateOf(pending.name) }
    AlertDialog(
        onDismissRequest = { if (!recording.busy) actions.dismissRecordingSaveDialog() },
        title = { Text(stringResource(R.string.save_recorded_trajectory)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending.incomplete) {
                    Text(stringResource(R.string.incomplete_recording_warning), color = MaterialTheme.colorScheme.error)
                }
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text(stringResource(R.string.trajectory_name)) },
                    singleLine = true, enabled = !recording.busy,
                    modifier = Modifier.testTag("recording_name"),
                    isError = name.trim().isEmpty()
                )
                if (recording.message != null) Text(recording.message, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            Button(
                onClick = { actions.saveRecording(name) },
                enabled = name.trim().isNotEmpty() && !recording.busy
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = actions::dismissRecordingSaveDialog, enabled = !recording.busy) {
                Text(stringResource(R.string.save_later))
            }
        }
    )
}
