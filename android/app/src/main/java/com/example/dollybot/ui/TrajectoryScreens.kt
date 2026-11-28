package com.example.dollybot.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.mutableStateOf
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
    onSend: (String) -> Unit,
    onStart: (Int) -> Unit = {}, onStop: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val trajectories = if (examples) emptyList() else state.trajectories.filter { it.kind == kind && !it.isDraft }
    val connected = state.device.connection == ConnectionStatus.Connected
    val upload = state.trajectoryUpload
    val powerTripped = state.device.power?.tripped == true
    val confirm = remember { mutableStateOf(false) }
    val loops = state.storedRepeats
    if (confirm.value) AlertDialog(onDismissRequest={confirm.value=false},
        title={Text("开始回放车端最近保存的轨迹？")},
        text={Text("请确认小车的坐标参考和车头方向与录制时一致。若已搬动车辆或重新上电，请先在原校准标记处按原朝向重新校准。确认后将低速返回首点，执行 ${loops} 轮。")},
        confirmButton={TextButton(enabled = !powerTripped, onClick={confirm.value=false;onStart(loops)}) {Text("确认并回首点")}},
        dismissButton={TextButton(onClick={confirm.value=false}) {Text("取消")}})
    Column(modifier.fillMaxSize()) {
        if (connected) Row(verticalAlignment=Alignment.CenterVertically) {
            TextButton(enabled=state.storedTask && state.device.calibrated && upload?.sending != true && !powerTripped,
                onClick={confirm.value=true}) {Text("开始车端回放")}
            TextButton(onClick=onStop) {Text("停止")}
            val powerAlert = state.device.powerAlert
            Text(when {
                powerAlert != null -> stringResource(powerAlert)
                state.chassisConfigured -> state.replayState
                else -> "请先设置惯导系数"
            })
        }
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
        if (trajectories.isNotEmpty() && !connected) {
            Text(
                stringResource(R.string.trajectory_send_connect),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
        ) {
            items(trajectories, key = { it.id }) { trajectory ->
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onSelect(trajectory) }.padding(16.dp),
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
                        val transfer = upload?.takeIf {
                            it.trajectoryId == trajectory.id && (it.sending || it.updatedAt == trajectory.updatedAt)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                        ) {
                            val status = when {
                                transfer == null -> null
                                transfer.sending -> stringResource(
                                    R.string.trajectory_sending, transfer.sentPoints, transfer.totalPoints
                                )
                                transfer.error != null -> stringResource(R.string.trajectory_send_failed, transfer.error)
                                else -> stringResource(R.string.trajectory_sent)
                            }
                            if (status != null) {
                                Text(
                                    status, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                    color = if (transfer?.error != null) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(
                                onClick = { onSend(trajectory.id) },
                                enabled = connected && upload?.sending != true && trajectory.points.isNotEmpty()
                            ) { Text(stringResource(R.string.send_trajectory)) }
                        }
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
                    InfoRow(stringResource(R.string.recorded_samples), run.poses.size.toString())
                }
            }
        }
    }
}

@Composable
internal fun TrajectoryEditorScreen(editor: TrajectoryEditorState, actions: DollyBotActions) {
    val trajectory = editor.edited
    val waypoint = trajectory.kind == TrajectoryKind.Waypoints
    val timeline = remember(trajectory) { PreviewTimeline(trajectory) }
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
    Row {
        TextButton(onClick={
            val speed=when(trajectory.speedPercent){50->100;100->200;else->50}
            actions.editPlayback(speed,trajectory.smooth,trajectory.repeats)
        }) { Text("速度 ${trajectory.speedPercent}%") }
        TextButton(onClick={actions.editPlayback(trajectory.speedPercent,trajectory.smooth,trajectory.repeats%5+1)}) {
            Text("循环 ${trajectory.repeats} 次")
        }
        if (waypoint) TextButton(onClick={actions.editPlayback(trajectory.speedPercent,!trajectory.smooth,trajectory.repeats)}) {
            Text(if (trajectory.smooth) "平滑曲线" else "逐段直线")
        }
    }
    val selected=trajectory.points.getOrNull(selectedIndex)
    if (waypoint && selected != null) {
        val p=selected.pose
        Row {
            TextButton(onClick={actions.editKeyframe((selected.dwellMs+500)%5500,p.gimbalYaw,p.gimbalPitch)}) {
                Text("停留 ${selected.dwellMs/1000f} 秒")
            }
            TextButton(onClick={actions.editKeyframe(selected.dwellMs,if(p.gimbalYaw>=60f) -60f else p.gimbalYaw+5,p.gimbalPitch)}) {
                Text("偏航指令 ${p.gimbalYaw.toInt()}°")
            }
            TextButton(onClick={actions.editKeyframe(selected.dwellMs,p.gimbalYaw,if(p.gimbalPitch>=40f) -40f else p.gimbalPitch+5)}) {
                Text("俯仰指令 ${p.gimbalPitch.toInt()}°")
            }
        }
    }
    PageStatus(stringResource(trajectory.kind.label),
        if (trajectory.incomplete) stringResource(R.string.incomplete_trajectory) else null)
    LiveTrajectoryDiagram(
        poses = trajectory.points.map { it.pose }, path = remember(timeline) { timeline.outline() }, current = pose, waypoints = waypoint,
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
