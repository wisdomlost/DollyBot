package com.example.dollybot

import androidx.annotation.StringRes
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.espresso.Espresso.pressBack
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DollyBotNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun teachingAndDeviceScreensKeepTheirPreviewControlsDisabled() {
        text(R.string.stop).assertIsDisplayed().assertIsEnabled()
        text(R.string.view_continuous_teaching).performScrollTo().performClick()
        text(R.string.continuous_teaching).assertIsDisplayed()
        text(R.string.start_recording).assertIsDisplayed().assertIsNotEnabled()
        pressBack()

        text(R.string.view_waypoint_teaching).performScrollTo().performClick()
        text(R.string.waypoint_teaching).assertIsDisplayed()
        text(R.string.capture_waypoint).assertIsDisplayed().assertIsNotEnabled()
        toolbarBack()

        text(R.string.device).performClick()
        text(R.string.disconnected).assertIsDisplayed()
        text(R.string.connect).performScrollTo().assertIsDisplayed()
        text(R.string.uncalibrated).performScrollTo().assertIsDisplayed()
        text(R.string.calibrate).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun waypointPlaybackReturnsToEditorAndList() {
        text(R.string.trajectories).performClick()
        text(R.string.table_slide).performClick()
        text(R.string.connection_mode).performScrollTo().assertIsDisplayed()
        text(R.string.curve).assertIsNotEnabled()
        text(R.string.save).performScrollTo().assertIsNotEnabled()
        text(R.string.view_playback).performScrollTo().performClick()
        assertPlayback(R.string.table_slide, R.string.straight)

        toolbarBack()
        text(R.string.trajectory_editor).assertIsDisplayed()
        pressBack()
        text(R.string.table_slide).assertIsDisplayed()
    }

    @Test
    fun continuousPlaybackKeepsSelectionAfterActivityRecreation() {
        text(R.string.trajectories).performClick()
        text(R.string.slow_push).performClick()
        text(R.string.original_duration).performScrollTo().assertIsDisplayed()
        text(R.string.connection_mode).assertDoesNotExist()
        text(R.string.view_playback).performScrollTo().performClick()
        assertPlayback(R.string.slow_push, R.string.continuous_teaching)

        compose.activityRule.scenario.recreate()
        assertPlayback(R.string.slow_push, R.string.continuous_teaching)
        pressBack()
        text(R.string.original_duration).performScrollTo().assertIsDisplayed()
    }

    private fun assertPlayback(@StringRes name: Int, @StringRes mode: Int) {
        text(R.string.playback_preview).assertIsDisplayed()
        val title = compose.activity.getString(
            R.string.playback_route_title,
            compose.activity.getString(name),
            compose.activity.getString(mode)
        )
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        text(R.string.start).assertIsDisplayed().assertIsNotEnabled()
        text(R.string.stop).assertIsDisplayed().assertIsNotEnabled()
        text(R.string.upload).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(compose.activity.getString(R.string.progress_value, 0))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun toolbarBack() {
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
    }

    private fun text(@StringRes label: Int) = compose.onNodeWithText(compose.activity.getString(label))
}
