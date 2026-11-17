package com.example.dollybot

import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.Espresso.closeSoftKeyboard
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.dollybot.connection.DollyBotViewModel
import com.example.dollybot.connection.LivePose
import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.StoredPose
import com.example.dollybot.trajectory.TrajectoryStore
import com.example.dollybot.ui.DollyBotApp
import com.example.dollybot.ui.RecordingStage
import com.example.dollybot.ui.TrajectoryKind
import com.example.dollybot.ui.theme.DollybotTheme
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
class DollyBotNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val databaseName = "test-library-" + UUID.randomUUID() + ".db"
    private var owner: ViewModelStoreOwner? = null
    private lateinit var model: DollyBotViewModel
    private lateinit var repository: TrajectoryStore

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        FileOutputStream(File(compose.activity.filesDir, "qa-$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun show(vararg trajectories: SavedTrajectory) {
        compose.runOnUiThread {
            owner?.viewModelStore?.clear()
            val application = compose.activity.application
            repository = TrajectoryStore(application, databaseName)
            trajectories.forEach(repository::put)
            val nextOwner = object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
            owner = nextOwner
            model = ViewModelProvider(nextOwner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DollyBotViewModel(application, repository) as T
            })[DollyBotViewModel::class.java]
            compose.activity.setContent {
                val state by model.state.collectAsState()
                DollybotTheme { DollyBotApp(state, model) }
            }
        }
        compose.waitUntil(5000) { !model.state.value.libraryLoading }
    }

    @After
    fun cleanup() {
        compose.runOnUiThread { owner?.viewModelStore?.clear() }
        compose.activity.deleteDatabase(databaseName)
    }

    private fun trajectory(kind: TrajectoryKind = TrajectoryKind.Waypoints, draft: Boolean = false) =
        SavedTrajectory("test", if (draft) "待保存" else "点路线", kind, 100, 100,
            listOf(
                StoredPose("a", LivePose(1000, 1, 0, 0, 0f, 10f, 20f)),
                StoredPose("b", LivePose(2000, 1, 200, 0, 30f, 40f, 50f)),
                StoredPose("c", LivePose(3000, 1, 400, 0, 60f, 70f, 80f))
            ), isDraft = draft)

    @Test
    fun emptyLibraryAndReadOnlyViewersReplaceTheOriginalExamples() {
        show()
        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithText("我的轨迹").assertIsDisplayed()
        compose.onNodeWithText("连续轨迹").assertIsDisplayed()
        screenshot("library")
        compose.onNodeWithText("点轨迹").assertIsDisplayed()
        compose.onNodeWithText("暂无已保存的轨迹").assertIsDisplayed()
        compose.onNodeWithText("桌面横移").assertDoesNotExist()
        compose.onNodeWithText("慢速推进").assertDoesNotExist()
        compose.onNodeWithText("示例轨迹库").performClick()
        compose.onNodeWithText("暂无示例轨迹").assertIsDisplayed()
        compose.onNodeWithText("控制").performClick()
        compose.onNodeWithText("查看连续示教").performScrollTo().performClick()
        compose.onNodeWithTag("teaching_canvas").assertIsDisplayed()
        screenshot("teaching")
        compose.onNodeWithText("开始录制").assertDoesNotExist()
        compose.onNodeWithText("结束录制").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText("查看关键点示教").performScrollTo().performClick()
        compose.onNodeWithTag("teaching_canvas").assertIsDisplayed()
        compose.onNodeWithText("取点").assertDoesNotExist()
    }

    @Test
    fun editPointOrderRenameSaveAndDeleteThroughTheRealStore() {
        show(trajectory())
        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithText("点轨迹").performClick()
        compose.onNodeWithText("点路线").performClick()
        screenshot("editor")
        compose.onNodeWithTag("trajectory_name").performTextReplacement("新名称")
        compose.onNodeWithTag("point_b").performScrollTo().performClick()
        compose.onNodeWithText("上移").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(listOf("b", "a", "c"), model.state.value.editor!!.edited.points.map { it.id })
            assertEquals(40f, model.state.value.editor!!.edited.points.first().pose.gimbalYaw, 0f)
        }
        compose.onNodeWithText("保存修改").performClick()
        compose.waitUntil(5000) { model.state.value.editor?.dirty == false }
        assertEquals("新名称", repository.all().single().name)
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("新名称").assertIsDisplayed().performClick()
        compose.onNodeWithText("删除轨迹").performClick()
        compose.onNodeWithText("删除").performClick()
        compose.waitUntil(5000) { model.state.value.trajectories.isEmpty() }
        compose.onNodeWithText("暂无已保存的轨迹").assertIsDisplayed()
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun namedDraftSurvivesClosingTheDialogAndReopeningTheViewModel() {
        show(trajectory(TrajectoryKind.Continuous, draft = true))
        compose.onNodeWithText("稍后保存").performClick()
        compose.runOnIdle { assertEquals(RecordingStage.PendingSave, model.state.value.recording.stage) }
        show()
        compose.onNodeWithTag("recording_name").performTextReplacement("连续录制成果")
        compose.onNodeWithText("保存").performClick()
        compose.waitUntil(5000) { model.state.value.recording.stage == RecordingStage.Idle }
        val stored = repository.all().single()
        assertFalse(stored.isDraft)
        assertEquals("连续录制成果", stored.name)
        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithText("连续录制成果").assertIsDisplayed()
    }

    @Test
    fun unsavedChangesCanBeKeptDiscardedOrSavedBeforeReturning() {
        show(trajectory(TrajectoryKind.Continuous))
        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithText("点路线").performClick()
        compose.onNodeWithTag("trajectory_name").performTextReplacement("临时名称")
        closeSoftKeyboard()
        pressBack()
        compose.onNodeWithText("继续编辑").performClick()
        compose.runOnIdle { assertEquals("临时名称", model.state.value.editor!!.edited.name) }
        pressBack()
        compose.onNodeWithText("放弃修改").performClick()
        assertEquals("点路线", repository.all().single().name)
        compose.onNodeWithText("点路线").performClick()
        compose.onNodeWithTag("trajectory_name").performTextReplacement("已保存名称")
        closeSoftKeyboard()
        pressBack()
        compose.onNodeWithText("保存并返回").performClick()
        compose.waitUntil(5000) { model.state.value.editor == null }
        compose.onNodeWithText("已保存名称").assertIsDisplayed()
        assertEquals("已保存名称", repository.all().single().name)
    }

    @Test
    fun offlinePreviewResetsWhenPointsChangeAndAnEmptyDraftCannotBeSaved() {
        show(trajectory())
        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithText("点轨迹").performClick()
        compose.onNodeWithText("点路线").performClick()
        compose.onNodeWithText("播放").performScrollTo().performClick()
        compose.waitUntil(5000) { (model.state.value.editor?.elapsedMs ?: 0) > 0 }
        compose.runOnUiThread { model.togglePreview() }
        compose.runOnIdle { assertFalse(model.state.value.editor!!.playing) }
        compose.onNodeWithText("上移").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("删除点").performClick()
        compose.runOnIdle {
            assertEquals(0L, model.state.value.editor!!.elapsedMs)
            assertFalse(model.state.value.editor!!.playing)
        }
        compose.onNodeWithText("删除点").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, model.state.value.editor!!.edited.points.size) }
        compose.onNodeWithText("播放").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("删除点").performScrollTo().performClick()
        compose.onNodeWithText("保存修改").assertIsNotEnabled()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("保存并返回").assertIsNotEnabled()
        compose.onNodeWithText("放弃修改").performClick()
        assertEquals(3, repository.all().single().points.size)
    }
}
