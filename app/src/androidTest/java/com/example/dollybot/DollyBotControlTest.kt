package com.example.dollybot

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.dollybot.ui.DollyBotActions
import com.example.dollybot.ui.DollyBotApp
import com.example.dollybot.ui.GimbalAxis
import com.example.dollybot.ui.MotionInput
import com.example.dollybot.ui.NoOpDollyBotActions
import com.example.dollybot.ui.theme.DollybotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DollyBotControlTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val actions = RecordedActions()

    private fun recordActions() {
        compose.runOnUiThread {
            compose.activity.setContent {
                DollybotTheme { DollyBotApp(actions = actions) }
            }
        }
    }

    @Test
    fun gimbalHoldsBothAnglesAcrossNavigationAndRecreationAndResetsOnDemand() {
        val joystick = compose.onNodeWithTag("gimbal_joystick")
        joystick.performScrollTo().performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.18f, -height * 0.18f))
            up()
        }
        val heldPosition = joystick.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertTrue(heldPosition != compose.activity.getString(R.string.gimbal_position_value, 0, 0))

        compose.onNodeWithText("轨迹").performClick()
        compose.onNodeWithText("控制").performClick()
        joystick.performScrollTo()
        assertEquals(heldPosition, joystick.fetchSemanticsNode().config[SemanticsProperties.StateDescription])

        compose.activityRule.scenario.recreate()
        joystick.performScrollTo()
        assertEquals(heldPosition, joystick.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
        compose.onNodeWithText("回零").assertIsEnabled().performClick()
        assertEquals(
            compose.activity.getString(R.string.gimbal_position_value, 0, 0),
            joystick.fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        )
    }

    @Test
    fun chassisDragAndHorizontalRotationButtonsStopWhenReleased() {
        recordActions()
        val joystick = compose.onNodeWithTag("drive_joystick")
        joystick.performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.15f, -height * 0.15f))
        }
        compose.runOnIdle {
            assertTrue(actions.motion.strafe > 0f)
            assertTrue(actions.motion.forward > 0f)
        }
        joystick.performTouchInput { up() }
        compose.runOnIdle { assertEquals(MotionInput(), actions.motion) }

        val left = compose.onNodeWithText("左转")
        val right = compose.onNodeWithText("右转")
        val leftBounds = left.fetchSemanticsNode().boundsInRoot
        val rightBounds = right.fetchSemanticsNode().boundsInRoot
        assertTrue(leftBounds.right <= rightBounds.left)
        assertEquals(leftBounds.center.y, rightBounds.center.y, 1f)
        left.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(-1f, actions.motion.rotation, 0f) }
        left.performTouchInput { up() }
        compose.runOnIdle { assertEquals(MotionInput(), actions.motion) }
        right.performTouchInput { down(center) }
        compose.runOnIdle { assertEquals(1f, actions.motion.rotation, 0f) }
        right.performTouchInput { up() }
        compose.runOnIdle { assertEquals(MotionInput(), actions.motion) }

        joystick.performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.15f, 0f))
        }
        compose.onRoot().performTouchInput { down(1, rightBounds.center) }
        compose.runOnIdle {
            assertTrue(actions.motion.strafe > 0f)
            assertEquals(1f, actions.motion.rotation, 0f)
        }
        compose.onRoot().performTouchInput { up(1) }
        compose.runOnIdle {
            assertTrue(actions.motion.strafe > 0f)
            assertEquals(0f, actions.motion.rotation, 0f)
        }
        joystick.performTouchInput { up() }
        compose.runOnIdle { assertEquals(MotionInput(), actions.motion) }
    }

    @Test
    fun stopCancelsAHeldGestureAndBackgroundingClearsMotion() {
        recordActions()
        val joystick = compose.onNodeWithTag("drive_joystick")
        joystick.performTouchInput {
            down(center)
            moveTo(center + Offset(width * 0.2f, 0f))
        }
        compose.runOnIdle { assertTrue(actions.motion.strafe > 0f) }
        val stopBounds = compose.onNodeWithText("停止").fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(1, stopBounds.center)
            up(1)
        }
        compose.runOnIdle { assertEquals(MotionInput(), actions.motion) }
        joystick.performTouchInput {
            moveTo(center + Offset(width * 0.25f, 0f))
            up()
        }
        compose.runOnIdle { assertEquals(MotionInput(), actions.motion) }

        joystick.performTouchInput {
            down(center)
            moveTo(center + Offset(0f, -height * 0.2f))
        }
        compose.runOnIdle { assertTrue(actions.motion.forward > 0f) }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.runOnIdle { assertEquals(MotionInput(), actions.motion) }
        joystick.performTouchInput { up() }
    }

    @Test
    fun draggingBeyondTheJoystickEdgeClampsTheRetainedPosition() {
        recordActions()
        val joystick = compose.onNodeWithTag("gimbal_joystick")
        joystick.performScrollTo().performTouchInput {
            down(center)
            moveTo(center + Offset(width * 2f, -height * 2f))
            up()
        }
        compose.runOnIdle {
            val normalizedLength = actions.yaw * actions.yaw + actions.pitch * actions.pitch
            assertTrue(normalizedLength <= 180f * 180f + 1f)
            assertTrue(actions.yaw > 0f && actions.pitch > 0f)
        }
        compose.onNodeWithText("回零").performClick()
        compose.runOnIdle {
            assertEquals(0f, actions.yaw, 0f)
            assertEquals(0f, actions.pitch, 0f)
        }
    }

    private class RecordedActions : DollyBotActions by NoOpDollyBotActions {
        var motion = MotionInput()
        var yaw = 0f
        var pitch = 0f

        override fun move(input: MotionInput) {
            motion = input
        }

        override fun adjustGimbal(axis: GimbalAxis, degrees: Float) {
            when (axis) {
                GimbalAxis.Yaw -> yaw = degrees
                GimbalAxis.Pitch -> pitch = degrees
            }
        }
    }
}
