package com.example.dollybot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dollybot.connection.LivePose
import com.example.dollybot.R

@Composable
internal fun TrajectoryListScreen(
    trajectories: List<TrajectoryUiState>,
    onSelect: (TrajectoryUiState) -> Unit
) {
    PageStatus(stringResource(R.string.sample_trajectories))
    for (trajectory in trajectories) {
        Surface(
            onClick = { onSelect(trajectory) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(trajectory.name),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Chevron()
                }
                Text(
                    text = when (trajectory.kind) {
                        TrajectoryKind.Waypoints ->
                            stringResource(R.string.waypoint_summary, trajectory.points.size)
                        TrajectoryKind.Continuous ->
                            stringResource(R.string.continuous_summary, trajectory.durationSeconds)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TrajectoryDiagram(trajectory, compact = true)
            }
        }
    }
}

@Composable
internal fun TeachingScreen(
    trajectory: TrajectoryUiState,
    recordingSeconds: Int?,
    actions: DollyBotActions,
    modifier: Modifier = Modifier,
    live: TeachingUiState? = null,
    device: DeviceUiState = DeviceUiState()
) {
    val isWaypoint = trajectory.kind == TrajectoryKind.Waypoints
    val run = if (isWaypoint) live?.waypoints else live?.continuous
    val status = when {
        live?.busy == true -> R.string.teaching_pending
        run?.phase == TeachingPhase.Interrupted -> R.string.teaching_interrupted
        run?.phase == TeachingPhase.Finished -> R.string.teaching_finished
        isWaypoint -> R.string.waiting_for_waypoint
        run?.phase == TeachingPhase.Recording -> R.string.recording
        recordingSeconds != null -> R.string.recording
        else -> R.string.waiting_for_recording
    }
    val examples = trajectory.points.mapIndexed { index, point ->
        LivePose(
            index * trajectory.durationSeconds * 1000L / (trajectory.points.size - 1).coerceAtLeast(1),
            0, (point.x * 500).toInt(), (point.y * -500).toInt(),
            point.heading + 90f, 0f, 0f
        )
    }
    val ready = device.connection == ConnectionStatus.Connected && device.calibrated && live?.busy != true
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageStatus(
            stringResource(if (live == null) R.string.interface_preview else device.connection.label),
            stringResource(status),
            isError = run?.phase == TeachingPhase.Interrupted
        )
        if (live?.message != null) {
            Text(
                live.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    stringResource(
                        if (live == null) {
                            if (isWaypoint) R.string.sample_waypoints else R.string.sample_path
                        } else if (isWaypoint) R.string.live_waypoints else R.string.live_path
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
                Text(stringResource(R.string.heading_legend), style = MaterialTheme.typography.bodySmall)
                LiveTrajectoryDiagram(
                    poses = if (live == null) examples else run!!.poses,
                    current = live?.pose,
                    waypoints = isWaypoint,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                if (isWaypoint) {
                    if (live != null) {
                        Text(
                            stringResource(R.string.waypoint_count, run!!.poses.size),
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (label in listOf(R.string.waypoint_start, R.string.waypoint_middle, R.string.waypoint_end)) {
                            Text(
                                stringResource(label),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    val seconds = run?.seconds ?: recordingSeconds ?: 0
                    InfoRow(
                        stringResource(R.string.recording_duration),
                        stringResource(R.string.recording_time, seconds / 60, seconds % 60)
                    )
                }
            }
        }
        if (isWaypoint) {
            PlaceholderButton(
                stringResource(R.string.capture_waypoint),
                actions::captureWaypoint,
                Modifier.fillMaxWidth(),
                enabled = ready
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PlaceholderButton(
                    stringResource(R.string.start_recording),
                    actions::startRecording,
                    Modifier.weight(1f),
                    enabled = ready && live?.activeMode != 1
                )
                PlaceholderButton(
                    stringResource(R.string.finish_recording),
                    actions::finishRecording,
                    Modifier.weight(1f),
                    enabled = ready && live?.activeMode == 1
                )
            }
        }
    }
}

@Composable
internal fun TrajectoryEditorScreen(
    trajectory: TrajectoryUiState,
    actions: DollyBotActions,
    onPlayback: () -> Unit
) {
    val isWaypoint = trajectory.kind == TrajectoryKind.Waypoints
    PageStatus(stringResource(R.string.sample_trajectories), stringResource(trajectory.kind.label))
    Panel {
        TrajectoryDiagram(trajectory)
        Text(stringResource(R.string.heading_legend), style = MaterialTheme.typography.bodySmall)
    }
    Panel {
        OutlinedTextField(
            value = stringResource(trajectory.name),
            onValueChange = {},
            label = { Text(stringResource(R.string.trajectory_name)) },
            enabled = false,
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (isWaypoint) {
            Text(stringResource(R.string.connection_mode), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = true,
                    onClick = {},
                    label = { Text(stringResource(R.string.straight)) },
                    enabled = false
                )
                FilterChip(
                    selected = false,
                    onClick = {},
                    label = { Text(stringResource(R.string.curve)) },
                    enabled = false
                )
            }
            InfoRow(
                stringResource(R.string.point_order),
                trajectory.points.indices.joinToString(" · ") { (it + 1).toString() }
            )
        } else {
            InfoRow(
                stringResource(R.string.original_duration),
                stringResource(R.string.seconds_value, trajectory.durationSeconds)
            )
        }
        InfoRow(stringResource(R.string.speed_multiplier), speedLabel(trajectory.speed))
        InfoRow(
            stringResource(if (isWaypoint) R.string.waypoint_dwell else R.string.dwell_time),
            stringResource(R.string.seconds_value, trajectory.dwellSeconds)
        )
        InfoRow(
            stringResource(R.string.repeat_count),
            stringResource(R.string.repeats_value, trajectory.repetitions)
        )
    }
    Panel {
        Text(stringResource(R.string.gimbal_keyframes), style = MaterialTheme.typography.titleMedium)
        for (frame in trajectory.keyframes) {
            InfoRow(
                stringResource(
                    if (isWaypoint) R.string.waypoint_number else R.string.seconds_value,
                    frame.position
                ),
                stringResource(R.string.keyframe_angles, frame.yawDegrees, frame.pitchDegrees)
            )
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaceholderButton(
            stringResource(R.string.save),
            { actions.save(trajectory) },
            Modifier.weight(1f)
        )
        NavigationRow(stringResource(R.string.view_playback), onPlayback, Modifier.weight(1f))
    }
}

@Composable
internal fun PlaybackScreen(
    trajectory: TrajectoryUiState,
    state: PlaybackUiState,
    actions: DollyBotActions
) {
    val isFault = state.phase == PlaybackPhase.Fault
    PageStatus(
        stringResource(R.string.sample_trajectories),
        stringResource(state.phase.label),
        isError = isFault
    )
    Panel {
        Text(
            stringResource(
                R.string.playback_route_title,
                stringResource(trajectory.name),
                stringResource(
                    if (trajectory.kind == TrajectoryKind.Waypoints) R.string.straight else trajectory.kind.label
                )
            ),
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            stringResource(
                R.string.playback_settings,
                speedLabel(trajectory.speed),
                stringResource(R.string.seconds_value, trajectory.dwellSeconds),
                stringResource(R.string.repeats_value, trajectory.repetitions)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        TrajectoryDiagram(trajectory)
    }
    Panel {
        StatusActionRow(
            label = stringResource(R.string.task_status),
            status = stringResource(if (state.uploaded) R.string.uploaded else R.string.not_uploaded),
            actionLabel = stringResource(R.string.upload),
            onClick = { actions.upload(trajectory) }
        )
        HorizontalDivider()
        StatusActionRow(
            label = stringResource(R.string.start_status),
            status = stringResource(if (state.startConfirmed) R.string.confirmed else R.string.unconfirmed),
            actionLabel = stringResource(R.string.confirm_start),
            onClick = actions::confirmStart
        )
    }
    Panel {
        val progress = state.progressPercent.coerceIn(0, 100)
        InfoRow(stringResource(R.string.task_progress), stringResource(R.string.progress_value, progress))
        LinearProgressIndicator(
            progress = { progress / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = if (isFault) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        if (isFault) {
            Text(
                stringResource(R.string.playback_fault_feedback),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun StatusActionRow(label: String, status: String, actionLabel: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        PlaceholderButton(actionLabel, onClick)
    }
}

@Composable
private fun speedLabel(speed: Float): String =
    stringResource(R.string.speed_value, speed.toString().removeSuffix(".0"))
