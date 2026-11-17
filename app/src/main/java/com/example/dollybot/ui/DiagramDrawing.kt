package com.example.dollybot.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp

internal fun DrawScope.drawHeading(color: Color, position: Offset, heading: Float) {
    val halfLength = 9.dp.toPx()
    val head = 4.dp.toPx()
    val tip = position + Offset(halfLength, 0f)
    val stroke = 1.5.dp.toPx()
    rotate(heading, pivot = position) {
        drawLine(color, position - Offset(halfLength, 0f), tip, stroke, StrokeCap.Round)
        drawLine(color, tip, tip + Offset(-head, -head), stroke, StrokeCap.Round)
        drawLine(color, tip, tip + Offset(-head, head), stroke, StrokeCap.Round)
    }
}
