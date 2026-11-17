package com.example.dollybot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.dollybot.R
import com.example.dollybot.trajectory.PreviewTimeline
import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.TrajectoryEditorState
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrajectoryListScreen(
    state: DollyBotUiState,
    examples: Boolean,
    kind: TrajectoryKind,
    onSourceChange: (Boolean) -> Unit,
    onKindChange: (TrajectoryKind) -> Unit,
    onSelect: (SavedTrajectory) -> Unit,
    modifier: Modifier = Modifier
) {
    val trajectories = if (examples) emptyList() else state.trajectories.filter { it.kind == kind }
    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = if (examples) 0 else 1, containerColor = MaterialTheme.colorScheme.surface) {
            Tab(selected = examples, onClick = { onSourceChange(true) },
                text = { Text(stringResource(R.string.example_library)) })
            Tab(selected = !examples, onClick = { onSourceChange(false) },
                text = { Text(stringResource(R.string.my_trajectories)) })
        }
        SecondaryTabRow(
            selectedTabIndex = if (kind == TrajectoryKind.Continuous) 0 else 1,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            for (type in listOf(TrajectoryKind.Continuous, TrajectoryKind.Waypoints)) {
                Tab(selected = kind == type, onClick = { onKindChange(type) },
                    text = { Text(stringResource(type.label)) })
            }
        }
        if (state.libraryLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else if (trajectories.isEmpty()) {
            Text(
                stringResource(if (examples) R.string.no_example_trajectories else R.string.no_saved_trajectories),
                modifier = Modifier.padding(24.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            items(trajectories, key = { it.id }) { trajectory ->
                Surface(
                    onClick = { onSelect(trajectory) },
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(trajectory.name, style = MaterialTheme.typography.titleMedium,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val details = if (kind == TrajectoryKind.Continuous) {
                                val timeline = remember(trajectory) { PreviewTimeline(trajectory) }
                                formatPreviewTime(timeline.durationMs)
                            } else stringResource(R.string.waypoint_count, trajectory.points.size)
                            Text(
                                details + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(trajectory.updatedAt)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (trajectory.incomplete) {
                                Text(stringResource(R.string.incomplete_trajectory),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Chevron()
                    }
                }
            }
        }
    }
}

@Composable
internal fun TeachingScreen(
    kind: TrajectoryKind,
    teaching: TeachingUiState,
    device: DeviceUiState,
    modifier: Modifier = Modifier
) {
    val waypoint = kind == TrajectoryKind.Waypoints
    val run = if (waypoint) teaching.waypoints else teaching.continuous
    val status = when {
        run.phase == TeachingPhase.Empty && waypoint -> R.string.waiting_for_waypoint
        else -> run.phase.label
    }
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        PageStatus(stringResource(device.connection.label), stringResource(status),
            isError = run.phase == TeachingPhase.Interrupted)
        Surface(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                Modifier.fillMaxSize().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(if (waypoint) R.string.live_waypoints else R.string.live_path),
                    style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.heading_legend), style = MaterialTheme.typography.bodySmall)
                LiveTrajectoryDiagram(
                    poses = run.poses, current = teaching.pose, waypoints = waypoint,
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                if (waypoint) {
                    Text(stringResource(R.string.waypoint_count, run.poses.size))
                } else {
                    InfoRow(stringResource(R.string.recording_duration), formatPreviewTime(run.seconds * 1000L))
                }
            }
        }
    }
}

@Composable
internal fun TrajectoryEditorScreen(editor: TrajectoryEditorState, actions: DollyBotActions) {
    val trajectory = editor.edited
    val waypoint = trajectory.kind == TrajectoryKind.Waypoints
    val timeline = remember(trajectory.kind, trajectory.points) { PreviewTimeline(trajectory) }
    val pose = remember(timeline, editor.elapsedMs) { timeline.poseAt(editor.elapsedMs) }
    val selectedIndex = trajectory.points.indexOfFirst { it.id == editor.selectedPointId }
    OutlinedTextField(
        value = trajectory.name,
        onValueChange = actions::renameEditedTrajectory,
        label = { Text(stringResource(R.string.trajectory_name)) },
        modifier = Modifier.fillMaxWidth().testTag("trajectory_name"),
        singleLine = true,
        enabled = !editor.saving,
        isError = trajectory.name.trim().isEmpty()
    )
    PageStatus(stringResource(trajectory.kind.label),
        if (trajectory.incomplete) stringResource(R.string.incomplete_trajectory) else null)
    LiveTrajectoryDiagram(
        poses = trajectory.points.map { it.pose }, current = pose, waypoints = waypoint,
        selectedIndex = selectedIndex.takeIf { it >= 0 },
        emptyMessage = stringResource(R.string.keep_one_point),
        modifier = Modifier.fillMaxWidth().height(270.dp)
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = actions::togglePreview,
            enabled = !editor.saving && trajectory.points.size > 1 && timeline.durationMs > 0,
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(if (editor.playing) R.string.pause_preview else R.string.play_preview)) }
        Button(
            onClick = actions::resetPreview,
            enabled = !editor.saving && trajectory.points.isNotEmpty(),
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.reset_preview)) }
    }
    LinearProgressIndicator(
        progress = { if (timeline.durationMs == 0L) 0f else editor.elapsedMs.toFloat() / timeline.durationMs },
        modifier = Modifier.fillMaxWidth()
    )
    InfoRow(formatPreviewTime(editor.elapsedMs), formatPreviewTime(timeline.durationMs))
    if (editor.message != null) {
        Text(editor.message, color = MaterialTheme.colorScheme.error)
    }
    if (waypoint) {
        Text(stringResource(R.string.point_order), style = MaterialTheme.typography.titleMedium)
        trajectory.points.forEachIndexed { index, point ->
            Surface(
                onClick = { actions.selectEditedPoint(point.id) },
                enabled = !editor.saving,
                shape = MaterialTheme.shapes.medium,
                color = if (index == selectedIndex) MaterialTheme.colorScheme.secondaryContainer
                    else MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().testTag("point_" + point.id)
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.point_position, index + 1, point.pose.xMm, point.pose.yMm))
                    Text(
                        stringResource(R.string.point_angles, point.pose.yawDegrees,
                            point.pose.gimbalYaw, point.pose.gimbalPitch),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (trajectory.points.isEmpty()) {
            Text(stringResource(R.string.keep_one_point), color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PlaceholderButton(stringResource(R.string.move_point_up), { actions.moveEditedPoint(-1) },
                Modifier.weight(1f), enabled = !editor.saving && selectedIndex > 0)
            PlaceholderButton(stringResource(R.string.move_point_down), { actions.moveEditedPoint(1) },
                Modifier.weight(1f), enabled = !editor.saving && selectedIndex >= 0 &&
                    selectedIndex < trajectory.points.lastIndex)
            PlaceholderButton(stringResource(R.string.delete_point), actions::deleteEditedPoint,
                Modifier.weight(1f), isStop = true, enabled = !editor.saving && selectedIndex >= 0)
        }
    }
}

internal fun formatPreviewTime(millis: Long): String =
    "%02d:%02d".format(millis.coerceAtLeast(0) / 60000, millis.coerceAtLeast(0) / 1000 % 60)
