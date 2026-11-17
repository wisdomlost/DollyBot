package com.example.dollybot.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.dollybot.R
import com.example.dollybot.ui.theme.DollybotTheme

private enum class Page(@param:StringRes val title: Int, val parent: Page? = null) {
    Control(R.string.control),
    Trajectories(R.string.trajectories),
    Device(R.string.device),
    ContinuousTeaching(R.string.continuous_teaching, Control),
    WaypointTeaching(R.string.waypoint_teaching, Control),
    Editor(R.string.trajectory_editor, Trajectories)
}

private val tabs = listOf(Page.Control, Page.Trajectories, Page.Device)

@Composable
internal fun DollyBotApp(
    state: DollyBotUiState = DollyBotUiState(),
    actions: DollyBotActions = NoOpDollyBotActions
) {
    DollyBotNavigation(state, actions, Page.Control)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DollyBotNavigation(state: DollyBotUiState, actions: DollyBotActions, initialPage: Page) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var examples by rememberSaveable { mutableStateOf(false) }
    var libraryKind by rememberSaveable { mutableStateOf(TrajectoryKind.Continuous) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var showUnsaved by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var returnAfterSave by rememberSaveable { mutableStateOf(false) }
    var yawDegrees by rememberSaveable { mutableFloatStateOf(state.yawDegrees) }
    var pitchDegrees by rememberSaveable { mutableFloatStateOf(state.pitchDegrees) }
    var strafe by remember { mutableFloatStateOf(0f) }
    var forward by remember { mutableFloatStateOf(0f) }
    var turningLeft by remember { mutableStateOf(false) }
    var turningRight by remember { mutableStateOf(false) }
    var controlGestureSession by remember { mutableIntStateOf(0) }
    val rotation = (if (turningRight) 1f else 0f) - (if (turningLeft) 1f else 0f)
    val motion = MotionInput(strafe, forward, rotation)

    fun publishMotion() {
        actions.move(MotionInput(
            strafe, forward,
            (if (turningRight) 1f else 0f) - (if (turningLeft) 1f else 0f)
        ))
    }

    fun stopControlMotion() {
        strafe = 0f
        forward = 0f
        turningLeft = false
        turningRight = false
        controlGestureSession++
        publishMotion()
        actions.stopMotion()
    }

    fun back() {
        if (page == Page.Editor) {
            if (state.editor?.saving == true) return
            if (state.editor?.dirty == true) {
                showUnsaved = true
                return
            }
            actions.closeEditor()
        }
        page.parent?.let { page = it }
    }

    LaunchedEffect(page, selectedId, state.libraryLoading, state.trajectories.map { it.id }) {
        if (page == Page.Editor && !state.libraryLoading && selectedId != null) {
            if (state.trajectories.none { it.id == selectedId }) {
                actions.closeEditor()
                page = Page.Trajectories
            } else if (state.editor?.edited?.id != selectedId && !returnAfterSave) {
                actions.openTrajectory(selectedId!!)
            }
        }
    }
    LaunchedEffect(returnAfterSave, state.editor?.saving, state.editor == null) {
        if (returnAfterSave && state.editor == null) {
            returnAfterSave = false
            page = Page.Trajectories
        } else if (returnAfterSave && state.editor?.saving == false && state.editor.message != null) {
            returnAfterSave = false
        }
    }

    val parent = page.parent
    val title = stringResource(page.title)
    val colors = MaterialTheme.colorScheme
    BackHandler(enabled = parent != null) { back() }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (parent == null) stringResource(R.string.main_page_title, title) else title,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (parent != null) {
                        val label = stringResource(R.string.back)
                        IconButton(onClick = ::back) {
                            Chevron(forward = false, modifier = Modifier.semantics { contentDescription = label })
                        }
                    }
                },
                actions = {
                    if (page == Page.Editor && state.editor != null) {
                        TextButton(onClick = { showDelete = true }, enabled = !state.editor.saving) {
                            Text(stringResource(R.string.delete_trajectory), color = colors.error)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.background)
            )
        },
        bottomBar = {
            if (parent == null) {
                Column {
                    if (page == Page.Control) {
                        RecordingFooter(state, actions, onStop = {
                            stopControlMotion()
                            actions.finishRecording()
                        })
                    }
                    NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                        for (tab in tabs) {
                            NavigationBarItem(
                                selected = page == tab, onClick = { page = tab },
                                icon = { TabIcon(tab, selected = page == tab) },
                                label = { Text(stringResource(tab.title)) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = colors.secondaryContainer,
                                    selectedTextColor = colors.primary
                                )
                            )
                        }
                    }
                }
            } else if (page == Page.Editor && state.editor != null) {
                val editor = state.editor
                Surface(color = colors.surface) {
                    PlaceholderButton(
                        stringResource(R.string.save_changes),
                        { actions.saveEditedTrajectory() },
                        Modifier.navigationBarsPadding().fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        enabled = editor.dirty && !editor.saving &&
                            editor.edited.name.trim().isNotEmpty() && editor.edited.points.isNotEmpty()
                    )
                }
            }
        }
    ) { insets ->
        key(page, selectedId) {
            val contentModifier = Modifier.padding(insets).consumeWindowInsets(insets)
            when (page) {
                Page.ContinuousTeaching, Page.WaypointTeaching -> TeachingScreen(
                    kind = if (page == Page.ContinuousTeaching) TrajectoryKind.Continuous else TrajectoryKind.Waypoints,
                    teaching = state.teaching, device = state.device, modifier = contentModifier
                )
                Page.Trajectories -> TrajectoryListScreen(
                    state = state, examples = examples, kind = libraryKind,
                    onSourceChange = { examples = it }, onKindChange = { libraryKind = it },
                    onSelect = {
                        selectedId = it.id
                        actions.openTrajectory(it.id)
                        page = Page.Editor
                    },
                    modifier = contentModifier
                )
                else -> ScreenBody(contentModifier) {
                    when (page) {
                        Page.Control -> ControlScreen(
                            state = if (state.device.live) state else state.copy(
                                yawDegrees = yawDegrees, pitchDegrees = pitchDegrees
                            ),
                            motion = motion, gestureSession = controlGestureSession,
                            onMove = { x, y -> strafe = x; forward = y; publishMotion() },
                            onRotate = { direction, pressed ->
                                when (direction) {
                                    DriveDirection.TurnLeft -> turningLeft = pressed
                                    DriveDirection.TurnRight -> turningRight = pressed
                                    else -> Unit
                                }
                                publishMotion()
                            },
                            onGimbalChange = { yaw, pitch ->
                                yawDegrees = yaw
                                pitchDegrees = pitch
                                actions.adjustGimbal(GimbalAxis.Yaw, yaw)
                                actions.adjustGimbal(GimbalAxis.Pitch, pitch)
                            },
                            onStopMotion = ::stopControlMotion,
                            onContinuousTeaching = { page = Page.ContinuousTeaching },
                            onWaypointTeaching = { page = Page.WaypointTeaching }
                        )
                        Page.Device -> DeviceScreen(state.device, actions)
                        Page.Editor -> {
                            val editor = state.editor
                            if (editor != null) TrajectoryEditorScreen(editor, actions)
                            else Text(stringResource(R.string.loading_trajectory))
                        }
                        else -> Unit
                    }
                }
            }
        }
    }
    RecordingSaveDialog(state.recording, actions)
    if (showUnsaved) {
        val editor = state.editor
        AlertDialog(
            onDismissRequest = { showUnsaved = false },
            title = { Text(stringResource(R.string.unsaved_changes)) },
            text = { Text(stringResource(R.string.unsaved_changes_body)) },
            confirmButton = {
                Column {
                    TextButton(
                        onClick = {
                            showUnsaved = false
                            returnAfterSave = true
                            actions.saveEditedTrajectory(closeAfter = true)
                        },
                        enabled = editor != null && !editor.saving &&
                            editor.edited.name.trim().isNotEmpty() && editor.edited.points.isNotEmpty()
                    ) { Text(stringResource(R.string.save_and_return)) }
                    TextButton(onClick = {
                        showUnsaved = false
                        actions.closeEditor()
                        page = Page.Trajectories
                    }) { Text(stringResource(R.string.discard_changes)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUnsaved = false }) { Text(stringResource(R.string.continue_editing)) }
            }
        )
    }
    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text(stringResource(R.string.delete_trajectory)) },
            text = { Text(stringResource(R.string.delete_trajectory_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDelete = false
                    state.editor?.edited?.id?.let(actions::deleteTrajectory)
                }) { Text(stringResource(R.string.delete), color = colors.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun TabIcon(page: Page, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    val color = if (selected) colors.primary else colors.onSurfaceVariant
    Canvas(Modifier.size(24.dp)) {
        val scale = size.width / 24f
        val stroke = 1.75.dp.toPx()
        fun point(x: Int, y: Int) = Offset(x * scale, y * scale)
        fun line(x1: Int, y1: Int, x2: Int, y2: Int) {
            drawLine(color, point(x1, y1), point(x2, y2), stroke, StrokeCap.Round)
        }
        when (page) {
            Page.Control -> {
                drawCircle(color, 9 * scale, style = Stroke(stroke))
                drawCircle(color, 2 * scale)
                line(12, 6, 12, 8)
                line(12, 16, 12, 18)
                line(6, 12, 8, 12)
                line(16, 12, 18, 12)
            }
            Page.Trajectories -> {
                line(6, 17, 12, 7)
                line(12, 7, 19, 13)
                drawCircle(color, 2.5f * scale, point(5, 18))
                drawCircle(color, 2.5f * scale, point(12, 6))
                drawCircle(color, 2.5f * scale, point(19, 13))
            }
            Page.Device -> {
                line(4, 8, 20, 8)
                line(4, 8, 4, 17)
                line(20, 8, 20, 17)
                line(4, 17, 20, 17)
                line(8, 17, 8, 20)
                line(16, 17, 16, 20)
                line(12, 4, 12, 8)
            }
            else -> Unit
        }
    }
}

@Preview(name = "控制 · 浅色", widthDp = 360, heightDp = 800)
@Preview(name = "控制 · 深色大字", widthDp = 320, heightDp = 700,
    fontScale = 1.4f, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ControlPreview() {
    DollybotTheme { DollyBotApp() }
}
