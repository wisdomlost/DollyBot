package com.example.dollybot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.example.dollybot.R
import com.example.dollybot.connection.LivePose
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

@Composable
internal fun LiveTrajectoryDiagram(
    poses: List<LivePose>,
    current: LivePose?,
    waypoints: Boolean,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    emptyMessage: String? = null,
    path: List<LivePose> = poses
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.onPrimary)
    val car = current?.takeIf { poses.isEmpty() || it.frameId == poses.first().frameId }
    val all = path + listOfNotNull(car)
    Surface(
        modifier = modifier.testTag("teaching_canvas"),
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(12.dp)) {
                if (size.width <= 0f || size.height <= 0f) return@Canvas
                val minX = all.minOfOrNull { it.xMm }?.toFloat() ?: -200f
                val maxX = all.maxOfOrNull { it.xMm }?.toFloat() ?: 200f
                val minY = all.minOfOrNull { it.yMm }?.toFloat() ?: -200f
                val maxY = all.maxOfOrNull { it.yMm }?.toFloat() ?: 200f
                val cx = (minX + maxX) / 2
                val cy = (minY + maxY) / 2
                val scale = minOf(
                    (size.width - 64.dp.toPx()).coerceAtLeast(1f) / (maxX - minX + 120).coerceAtLeast(400f),
                    (size.height - 64.dp.toPx()).coerceAtLeast(1f) / (maxY - minY + 120).coerceAtLeast(400f)
                )
                fun position(pose: LivePose) = Offset(
                    center.x + (pose.xMm - cx) * scale,
                    center.y - (pose.yMm - cy) * scale
                )
                val span = maxOf(size.width, size.height) / scale
                val gridStep = 10f.pow(ceil(log10(span / 10f)))
                val xStart = floor((cx - size.width / scale / 2) / gridStep).toInt()
                val xEnd = ceil((cx + size.width / scale / 2) / gridStep).toInt()
                for (index in xStart..xEnd) {
                    val x = center.x + (index * gridStep - cx) * scale
                    drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, size.height), 1.dp.toPx())
                }
                val yStart = floor((cy - size.height / scale / 2) / gridStep).toInt()
                val yEnd = ceil((cy + size.height / scale / 2) / gridStep).toInt()
                for (index in yStart..yEnd) {
                    val y = center.y - (index * gridStep - cy) * scale
                    drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                path.zipWithNext { from, to ->
                    drawLine(colors.primary, position(from), position(to), 2.dp.toPx(), StrokeCap.Round)
                }
                poses.forEachIndexed { index, pose ->
                    if (waypoints) {
                        val label = measurer.measure((index + 1).toString(), labelStyle)
                        val radius = maxOf(11.dp.toPx(), label.size.height / 2f + 2.dp.toPx())
                        val point = position(pose)
                        if (index == selectedIndex) {
                            drawCircle(colors.primary, radius + 5.dp.toPx(), point, style = Stroke(2.dp.toPx()))
                        }
                        drawCircle(colors.primary, radius, point)
                        drawText(label, topLeft = point - Offset(label.size.width / 2f, label.size.height / 2f))
                        drawHeading(colors.primary, point - Offset(0f, 30.dp.toPx()), pose.yawDegrees - 90f)
                    } else if (index == 0 || index == poses.lastIndex) {
                        drawCircle(colors.primary, 5.dp.toPx(), position(pose))
                    }
                }
                if (car != null) {
                    val point = position(car)
                    val angle = Math.toRadians(car.yawDegrees.toDouble())
                    val onNumberedPoint = waypoints && poses.any {
                        (position(it) - point).getDistance() < 20.dp.toPx()
                    }
                    val radius = if (onNumberedPoint) 19.dp.toPx() else 8.dp.toPx()
                    val direction = Offset(sin(angle).toFloat(), -cos(angle).toFloat())
                    val tip = point + direction * (radius + 18.dp.toPx())
                    if (!onNumberedPoint) drawCircle(colors.surface, radius, point)
                    drawCircle(colors.primary, radius, point, style = Stroke(2.dp.toPx()))
                    drawLine(colors.primary, point + direction * radius, tip, 2.dp.toPx(), StrokeCap.Round)
                    val back = -direction * 26.dp.toPx()
                    val wing = Offset(-back.y, back.x) * 0.17f
                    drawLine(colors.primary, tip, tip + back * 0.28f + wing, 2.dp.toPx())
                    drawLine(colors.primary, tip, tip + back * 0.28f - wing, 2.dp.toPx())
                }
            }
            if (all.isEmpty()) {
                Text(
                    emptyMessage ?: stringResource(R.string.empty_live_path),
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            if (car != null) {
                Text(
                    stringResource(R.string.pose_position, car.xMm, car.yMm),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
