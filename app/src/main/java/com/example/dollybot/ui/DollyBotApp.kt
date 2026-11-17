package com.example.dollybot.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
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
    Editor(R.string.trajectory_editor, Trajectories),
    Playback(R.string.playback_preview, Editor)
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
private fun DollyBotNavigation(
    state: DollyBotUiState,
    actions: DollyBotActions,
    initialPage: Page
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    var selectedKind by rememberSaveable { mutableStateOf(TrajectoryKind.Waypoints) }
    val parent = page.parent
    val title = stringResource(page.title)
    val trajectory = when (selectedKind) {
        TrajectoryKind.Waypoints -> state.waypointTrajectory
        TrajectoryKind.Continuous -> state.continuousTrajectory
    }
    val colors = MaterialTheme.colorScheme

    BackHandler(enabled = parent != null) {
        page.parent?.let { page = it }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (parent == null) {
                            stringResource(R.string.main_page_title, title)
                        } else {
                            title
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (parent != null) {
                        val back = stringResource(R.string.back)
                        IconButton(onClick = { page = parent }) {
                            Chevron(
                                forward = false,
                                modifier = Modifier.semantics { contentDescription = back }
                            )
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
                        MotionFooter(actions = actions)
                    }
                    NavigationBar(containerColor = colors.surface, tonalElevation = 0.dp) {
                        for (tab in tabs) {
                            NavigationBarItem(
                                selected = page == tab,
                                onClick = { page = tab },
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
            } else if (page == Page.Playback) {
                MotionFooter(
                    actions = actions,
                    showStart = true,
                    modifier = Modifier.navigationBarsPadding()
                )
            }
        }
    ) { insets ->
        key(page, selectedKind) {
            ScreenBody(
                modifier = Modifier
                    .padding(insets)
                    .consumeWindowInsets(insets)
            ) {
                when (page) {
                    Page.Control -> ControlScreen(
                        state = state,
                        actions = actions,
                        onContinuousTeaching = { page = Page.ContinuousTeaching },
                        onWaypointTeaching = { page = Page.WaypointTeaching }
                    )
                    Page.Trajectories -> TrajectoryListScreen(
                        trajectories = listOf(state.waypointTrajectory, state.continuousTrajectory),
                        onSelect = {
                            selectedKind = it.kind
                            page = Page.Editor
                        }
                    )
                    Page.Device -> DeviceScreen(state.device, actions)
                    Page.ContinuousTeaching -> TeachingScreen(
                        trajectory = state.continuousTrajectory,
                        recordingSeconds = state.recordingSeconds,
                        actions = actions
                    )
                    Page.WaypointTeaching -> TeachingScreen(
                        trajectory = state.waypointTrajectory,
                        recordingSeconds = null,
                        actions = actions
                    )
                    Page.Editor -> TrajectoryEditorScreen(
                        trajectory = trajectory,
                        actions = actions,
                        onPlayback = { page = Page.Playback }
                    )
                    Page.Playback -> PlaybackScreen(trajectory, state.playback, actions)
                }
            }
        }
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
@Preview(
    name = "控制 · 窄屏深色大字",
    widthDp = 320,
    heightDp = 700,
    fontScale = 1.4f,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ControlPreview() = PreviewPage(Page.Control)

@Preview(name = "设备 · 已连接", widthDp = 360, heightDp = 740)
@Composable
private fun ConnectedPreview() = PreviewPage(
    Page.Device,
    DollyBotUiState(
        device = DeviceUiState(ConnectionStatus.Connected, "DollyBot", calibrated = true)
    )
)

@Preview(name = "连续示教 · 录制中", widthDp = 360, heightDp = 740)
@Composable
private fun RecordingPreview() = PreviewPage(
    Page.ContinuousTeaching,
    DollyBotUiState(recordingSeconds = 6)
)

@Preview(name = "复现 · 执行中", widthDp = 360, heightDp = 740)
@Composable
private fun RunningPreview() = PreviewPage(
    Page.Playback,
    DollyBotUiState(
        playback = PlaybackUiState(PlaybackPhase.Running, 45, uploaded = true, startConfirmed = true)
    )
)

@Preview(name = "复现 · 故障", widthDp = 360, heightDp = 740)
@Composable
private fun FaultPreview() = PreviewPage(
    Page.Playback,
    DollyBotUiState(playback = PlaybackUiState(PlaybackPhase.Fault, 45, uploaded = true))
)

@Composable
private fun PreviewPage(page: Page, state: DollyBotUiState = DollyBotUiState()) {
    DollybotTheme {
        DollyBotNavigation(state, NoOpDollyBotActions, page)
    }
}
