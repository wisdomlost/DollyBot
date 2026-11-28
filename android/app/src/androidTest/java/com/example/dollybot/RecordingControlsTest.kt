package com.example.dollybot

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
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
        var selected: RecordingMode? = null
        var started: RecordingMode? = null
        var captures = 0
        var finishes = 0
        var stops = 0
        val controlPageEvents = mutableListOf<Boolean>()
        override fun setControlPageActive(active: Boolean) { controlPageEvents += active }
        override fun selectRecordingMode(mode: RecordingMode) { selected = mode }
        override fun startRecording(mode: RecordingMode) { started = mode }
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
    fun switchingTabsOnlyEnablesControlWhileItsPageIsVisible() {
        show()
        compose.onNodeWithText("设备").performClick()
        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithText("控制").performClick()
        compose.runOnIdle {
            assertEquals(listOf(true, false, true), spy.controlPageEvents)
        }
    }

    @Test
    fun pointRecordingRequiresExplicitStartAndStopsBeforeFinishing() {
        show()
        compose.onNodeWithText("录制").performClick()
        compose.onNodeWithText("取点").performClick()
        compose.runOnIdle {
            assertEquals(RecordingMode.Waypoints, spy.selected)
            assertNull(spy.started)
            snapshot.value = snapshot.value.copy(recording = RecordingUiState(
                mode = spy.selected, stage = RecordingStage.Ready
            ))
        }
        compose.onNodeWithText("取点").assertIsNotEnabled()
        compose.onNodeWithText("停止").assertIsNotEnabled()
        compose.onNodeWithText("开始").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(RecordingMode.Waypoints, spy.started)
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

    @Test
    fun pushSelectionAndWaitingStateDisableDrivingButKeepGimbalAvailable() {
        show()
        compose.onNodeWithText("录制").performClick()
        compose.onNodeWithText("推车").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(RecordingMode.Push, spy.selected)
            snapshot.value = snapshot.value.copy(recording = RecordingUiState(
                mode = RecordingMode.Push, stage = RecordingStage.Starting
            ))
        }
        compose.onNodeWithText("等待车端确认").assertIsDisplayed()
        compose.onNodeWithTag("drive_joystick").assertIsNotEnabled()
        compose.onNodeWithContentDescription("云台摇杆").assertIsEnabled()
        compose.onNodeWithText("停止").assertIsNotEnabled()
        compose.runOnIdle {
            snapshot.value = snapshot.value.copy(recording = snapshot.value.recording.copy(
                stage = RecordingStage.Recording
            ))
        }
        compose.onNodeWithText("停止").assertIsEnabled()
        compose.onNodeWithText("取点").assertDoesNotExist()
    }

    @Test
    fun pushRequiresConnectedCalibratedDevice() {
        snapshot.value = snapshot.value.copy(device = DeviceUiState())
        show()
        compose.onNodeWithText("录制").performClick()
        compose.onNodeWithText("推车").assertIsNotEnabled()
    }
}
