package com.example.dollybot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.dollybot.R

@Composable
internal fun TrajectoryDiagram(trajectory: TrajectoryUiState, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val markerTextStyle = MaterialTheme.typography.labelSmall.copy(
        color = colors.onPrimary,
        fontWeight = FontWeight.Bold
    )
    val description = stringResource(R.string.path_accessibility, stringResource(trajectory.name))
    val semantics = if (compact) {
        Modifier
    } else {
        Modifier.semantics { contentDescription = description }
    }
    val isWaypoint = trajectory.kind == TrajectoryKind.Waypoints

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Canvas(
                modifier = semantics
                    .fillMaxWidth()
                    .height(if (compact) 40.dp else 140.dp)
            ) {
                if (!compact) {
                    drawGrid(colors.outlineVariant)
                }

                val inset = (if (compact) 10.dp else 22.dp).toPx()
                val positions = trajectory.points.map { point ->
                    Offset(inset + (size.width - 2 * inset) * point.x, size.height * point.y)
                }
                positions.zipWithNext { from, to ->
                    drawLine(colors.primary, from, to, 2.5.dp.toPx(), StrokeCap.Round)
                }

                positions.forEachIndexed { index, position ->
                    when {
                        isWaypoint && !compact -> {
                            val label = textMeasurer.measure((index + 1).toString(), markerTextStyle)
                            val radius = maxOf(
                                10.dp.toPx(),
                                maxOf(label.size.width, label.size.height) / 2f + 2.dp.toPx()
                            )
                            drawCircle(colors.primary, radius, position)
                            drawText(
                                label,
                                topLeft = position - Offset(label.size.width / 2f, label.size.height / 2f)
                            )
                        }
                        index == positions.lastIndex && !isWaypoint -> {
                            val radius = (if (compact) 4.dp else 7.dp).toPx()
                            drawCircle(colors.surfaceContainer, radius, position)
                            drawCircle(colors.primary, radius, position, style = Stroke(2.dp.toPx()))
                        }
                        isWaypoint || index == 0 -> {
                            drawCircle(colors.primary, (if (compact) 4.dp else 7.dp).toPx(), position)
                        }
                    }
                    if (!compact && (isWaypoint || index == 0)) {
                        drawHeading(
                            colors.primary,
                            position - Offset(0f, 31.dp.toPx()),
                            trajectory.points[index].heading
                        )
                    }
                }
            }
            if (!compact) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(stringResource(R.string.path_start), style = MaterialTheme.typography.labelMedium)
                    Text(stringResource(R.string.path_end), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(color: Color) {
    for (column in 1..8) {
        val x = size.width * column / 9
        drawLine(color, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
    }
    for (row in 1..4) {
        val y = size.height * row / 5
        drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
    }
}

private fun DrawScope.drawHeading(color: Color, position: Offset, heading: Float) {
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
