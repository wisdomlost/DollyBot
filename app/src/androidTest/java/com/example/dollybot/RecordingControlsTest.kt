package com.example.dollybot

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.dollybot.ui.*
import com.example.dollybot.ui.theme.DollybotTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val snapshot = mutableStateOf(DollyBotUiState(
        device = DeviceUiState(connection = ConnectionStatus.Connected, calibrated = true)
    ))
    private val spy = object : DollyBotActions {
        var selected: TrajectoryKind? = null
        var started: TrajectoryKind? = null
        var captures = 0
        var finishes = 0
        var stops = 0
        override fun selectRecordingMode(kind: TrajectoryKind) { selected = kind }
        override fun startRecording(kind: TrajectoryKind) { started = kind }
        override fun captureWaypoint() { captures++ }
        override fun finishRecording() { finishes++ }
        override fun stopMotion() { stops++ }
    }

    private fun show() {
        compose.runOnUiThread {
            compose.activity.setContent {
                DollybotTheme { DollyBotApp(snapshot.value, spy) }
            }
        }
    }

    @Test
    fun pointRecordingRequiresExplicitStartAndStopsBeforeFinishing() {
        show()
        compose.onNodeWithText("录制").performClick()
        compose.onNodeWithText("取点").performClick()
        compose.runOnIdle {
            assertEquals(TrajectoryKind.Waypoints, spy.selected)
            assertNull(spy.started)
            snapshot.value = snapshot.value.copy(recording = RecordingUiState(
                mode = spy.selected, stage = RecordingStage.Ready
            ))
        }
        compose.onNodeWithText("取点").assertIsNotEnabled()
        compose.onNodeWithText("停止").assertIsNotEnabled()
        compose.onNodeWithText("开始").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(TrajectoryKind.Waypoints, spy.started)
            snapshot.value = snapshot.value.copy(recording = snapshot.value.recording.copy(
                stage = RecordingStage.Recording
            ))
        }
        compose.onNodeWithText("开始").assertIsNotEnabled()
        compose.onNodeWithText("取点").assertIsEnabled().performClick()
        compose.onNodeWithText("停止").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, spy.captures)
            assertEquals(1, spy.finishes)
            assertTrue(spy.stops >= 1)
        }
    }

    @Test
    fun continuousModeHasOnlyStartAndStopAfterSelection() {
        show()
        compose.onNodeWithText("录制").performClick()
        compose.onNodeWithText("连续录制").performClick()
        compose.runOnIdle {
            snapshot.value = snapshot.value.copy(recording = RecordingUiState(
                mode = spy.selected, stage = RecordingStage.Ready
            ))
        }
        compose.onNodeWithText("开始").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("停止").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("取点").assertDoesNotExist()
    }
}
