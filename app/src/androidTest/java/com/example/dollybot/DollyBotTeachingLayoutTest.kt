package com.example.dollybot

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DollyBotTeachingLayoutTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun bothTeachingCanvasesExpandAndOnlyShowRecordedData() {
        compose.onNodeWithText("查看连续示教").performScrollTo().performClick()
        checkExpandedCanvas()
        compose.onNodeWithText("开始录制").assertDoesNotExist()
        compose.onNodeWithText("结束录制").assertDoesNotExist()
        compose.onNodeWithContentDescription("返回").performClick()
        compose.onNodeWithText("查看关键点示教").performScrollTo().performClick()
        checkExpandedCanvas()
        compose.onNodeWithText("取点").assertDoesNotExist()
    }

    private fun checkExpandedCanvas() {
        val canvas = compose.onNodeWithTag("teaching_canvas").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue(canvas.height > screen.height * 0.3f)
        assertTrue(canvas.width > screen.width * 0.7f)
    }
}
