package com.example.dollybot.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.dollybot.R

@Composable
internal fun ControlJoystick(
    position: Offset,
    gestureSession: Int,
    description: String,
    positionDescription: String,
    onPositionChange: (Offset) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = MaterialTheme.colorScheme
    val currentPosition by rememberUpdatedState(position)
    val changePosition by rememberUpdatedState(onPositionChange)
    val release by rememberUpdatedState(onRelease)
    val directions = listOf(
        stringResource(R.string.strafe_left) to Offset(-0.1f, 0f),
        stringResource(R.string.strafe_right) to Offset(0.1f, 0f),
        stringResource(R.string.forward) to Offset(0f, 0.1f),
        stringResource(R.string.backward) to Offset(0f, -0.1f),
        stringResource(R.string.joystick_center) to Offset.Zero
    )

    Canvas(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.38f)
            .size(144.dp)
            .semantics {
                if (!enabled) disabled()
                contentDescription = description
                stateDescription = positionDescription
                customActions = if (!enabled) emptyList() else directions.map { (label, step) ->
                    CustomAccessibilityAction(label) {
                        val next = if (step == Offset.Zero) Offset.Zero else currentPosition + step
                        changePosition(next.insideUnitCircle())
                        release()
                        true
                    }
                }
            }
            .onKeyEvent { event ->
                if (!enabled) return@onKeyEvent false
                val step = when (event.key) {
                    Key.DirectionLeft -> Offset(-0.1f, 0f)
                    Key.DirectionRight -> Offset(0.1f, 0f)
                    Key.DirectionUp -> Offset(0f, 0.1f)
                    Key.DirectionDown -> Offset(0f, -0.1f)
                    else -> return@onKeyEvent false
                }
                if (event.type == KeyEventType.KeyDown) {
                    changePosition((currentPosition + step).insideUnitCircle())
                } else if (event.type == KeyEventType.KeyUp) {
                    release()
                }
                true
            }
            .focusable(enabled)
            .pointerInput(gestureSession, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = (minOf(size.width, size.height) / 2f - 19.dp.toPx())
                        .coerceAtLeast(1f)
                    fun update(touch: Offset) {
                        changePosition(
                            Offset(
                                (touch.x - center.x) / radius,
                                (center.y - touch.y) / radius
                            ).insideUnitCircle()
                        )
                    }
                    try {
                        update(down.position)
                        do {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                ?: break
                            if (change.isConsumed) break
                            change.consume()
                            if (!change.pressed) break
                            update(change.position)
                        } while (true)
                    } finally {
                        release()
                    }
                }
            }
    ) {
        val radius = size.minDimension / 2f - 2.dp.toPx()
        val knobRadius = 17.dp.toPx()
        val travel = (radius - knobRadius).coerceAtLeast(0f)
        val value = position.insideUnitCircle()
        val knob = center + Offset(value.x * travel, -value.y * travel)
        drawCircle(colors.surfaceContainer, radius)
        drawCircle(colors.outlineVariant, radius, style = Stroke(1.dp.toPx()))
        drawCircle(colors.outlineVariant, radius * 0.58f, style = Stroke(1.dp.toPx()))
        drawLine(
            colors.outlineVariant,
            Offset(center.x - radius, center.y),
            Offset(center.x + radius, center.y),
            1.dp.toPx()
        )
        drawLine(
            colors.outlineVariant,
            Offset(center.x, center.y - radius),
            Offset(center.x, center.y + radius),
            1.dp.toPx()
        )
        drawLine(colors.primary, center, knob, 2.dp.toPx(), StrokeCap.Round)
        drawCircle(colors.primary, knobRadius, knob)
        drawCircle(colors.onPrimary, 3.dp.toPx(), knob)
    }
}

private fun Offset.insideUnitCircle(): Offset {
    val distance = getDistance()
    return if (distance > 1f) this / distance else this
}
