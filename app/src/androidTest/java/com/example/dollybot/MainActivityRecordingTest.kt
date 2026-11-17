package com.example.dollybot

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dollybot.connection.DollyBotViewModel
import com.example.dollybot.connection.Wire
import com.example.dollybot.trajectory.TrajectoryStore
import com.example.dollybot.ui.ConnectionStatus
import com.example.dollybot.ui.RecordingMode
import com.example.dollybot.ui.RecordingStage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Keep MainActivity's real content and action adapter; replacing setContent would miss this regression. */
@RunWith(AndroidJUnit4::class)
class MainActivityRecordingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun selectMode(label: String, expected: RecordingMode) {
        val model = ViewModelProvider(compose.activity)[DollyBotViewModel::class.java]
        compose.waitUntil(5000) { !model.state.value.libraryLoading }
        compose.onNodeWithText("录制").performClick()
        compose.onNodeWithText(label).performClick()
        compose.runOnIdle {
            assertEquals(expected, model.state.value.recording.mode)
            assertEquals(RecordingStage.Ready, model.state.value.recording.stage)
        }
        compose.onNodeWithText("开始").assertIsDisplayed()
    }

    @Test
    fun realActivityContinuousSelectionReachesTheViewModel() {
        selectMode("连续录制", RecordingMode.Continuous)
    }

    @Test
    fun realActivityWaypointSelectionReachesTheViewModel() {
        selectMode("取点", RecordingMode.Waypoints)
    }

    @Test
    fun realActivityAdapterOverridesBothCurrentRecordingSignatures() {
        // A stale adapter can still load because DollyBotActions supplies no-op default methods.
        val adapter = Class.forName("com.example.dollybot.MainActivity\$onCreate\$actions\$1")
        adapter.getDeclaredMethod("selectRecordingMode", RecordingMode::class.java)
        adapter.getDeclaredMethod("startRecording", RecordingMode::class.java)
    }

    @Test
    @SdkSuppress(minSdkVersion = 31)
    fun realActivityRecordsAllThreeModesThroughItsProductionAdapter() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        instrumentation.uiAutomation.grantRuntimePermission(application.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        val database = "test-activity-recording-${UUID.randomUUID()}.db"
        lateinit var model: DollyBotViewModel
        lateinit var car: RecordingSessionTest.PacketCar
        try {
            val initial = ViewModelProvider(compose.activity)[DollyBotViewModel::class.java]
            compose.waitUntil(5000) { !initial.state.value.libraryLoading }
            compose.runOnUiThread {
                model = DollyBotViewModel(application, TrajectoryStore(application, database)) { _, receive, failed ->
                    RecordingSessionTest.PacketCar(receive, failed).also { car = it }
                }
                val key = compose.activity.viewModelStore.keys().single {
                    it.endsWith(DollyBotViewModel::class.java.canonicalName!!)
                }
                compose.activity.viewModelStore.put(key, model)
            }
            // Recreate the Activity with the retained test model, keeping its real setContent and action wrapper.
            compose.activityRule.scenario.recreate()
            compose.waitUntil(5000) { !model.state.value.libraryLoading }
            compose.runOnUiThread { model.selectDevice("00:11:22:33:44:55"); model.connect() }
            compose.waitUntil(5000) { model.state.value.device.connection == ConnectionStatus.Connected &&
                model.state.value.device.calibrated }

            for ((mode, label) in listOf(
                RecordingMode.Continuous to "连续录制", RecordingMode.Waypoints to "取点", RecordingMode.Push to "推车"
            )) {
                compose.runOnUiThread { car.holdBeginAck = mode == RecordingMode.Push }
                compose.onNodeWithText("录制").performClick()
                compose.onNodeWithText(label).performClick()
                if (mode == RecordingMode.Push) {
                    compose.waitUntil(5000) { model.state.value.recording.stage == RecordingStage.Starting &&
                        car.beginRequest?.payload?.first()?.toInt() == 3 }
                    compose.onNodeWithText("等待车端确认").assertIsDisplayed()
                    assertEquals(0, model.state.value.teaching.continuous.poses.size)
                    compose.runOnUiThread { car.releaseBeginAck() }
                } else compose.onNodeWithText("开始").performClick()
                compose.waitUntil(5000) { model.state.value.recording.stage == RecordingStage.Recording &&
                    !model.state.value.teaching.busy }
                if (mode == RecordingMode.Waypoints) {
                    compose.onNodeWithText("取点").performClick()
                    compose.waitUntil(5000) { model.state.value.teaching.waypoints.poses.size == 1 &&
                        !model.state.value.teaching.busy }
                }
                compose.onNodeWithText("停止").performClick()
                compose.waitUntil(5000) { model.state.value.recording.stage == RecordingStage.PendingSave }
                compose.onNodeWithText("稍后保存").performClick()
                compose.onNodeWithText("放弃").performClick()
                compose.onNode(hasText("放弃") and hasAnyAncestor(isDialog())).performClick()
                compose.waitUntil(5000) { model.state.value.recording.stage == RecordingStage.Idle }
            }
            assertEquals(listOf(1, 2, 3), car.sent.filter { it.type == Wire.TEACH_BEGIN }.map { it.payload[0].toInt() })
        } finally {
            compose.runOnUiThread { compose.activity.viewModelStore.clear() }
            application.deleteDatabase(database)
        }
    }
}
