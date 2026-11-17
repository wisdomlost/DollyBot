package com.example.dollybot.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dollybot.R
import kotlin.math.roundToInt

@Composable
internal fun ControlScreen(
    state: DollyBotUiState,
    actions: DollyBotActions,
    onContinuousTeaching: () -> Unit,
    onWaypointTeaching: () -> Unit
) {
    PageStatus(
        label = stringResource(R.string.interface_preview),
        status = stringResource(
            R.string.device_status_summary,
            stringResource(state.device.connection.label),
            stringResource(state.device.calibrationLabel)
        ),
        isError = state.device.connection == ConnectionStatus.Error
    )

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1.15f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.drive),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center
                )
                Joystick(actions)
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    stringResource(R.string.rotation),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center
                )
                PlaceholderButton(
                    stringResource(R.string.turn_left),
                    { actions.drive(DriveDirection.TurnLeft) },
                    Modifier.fillMaxWidth()
                )
                PlaceholderButton(
                    stringResource(R.string.turn_right),
                    { actions.drive(DriveDirection.TurnRight) },
                    Modifier.fillMaxWidth()
                )
            }
        }
    }

    Panel {
        Text(stringResource(R.string.gimbal), style = MaterialTheme.typography.titleMedium)
        GimbalSlider(GimbalAxis.Yaw, state.yawDegrees, actions)
        GimbalSlider(GimbalAxis.Pitch, state.pitchDegrees, actions)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NavigationRow(stringResource(R.string.view_continuous_teaching), onContinuousTeaching)
        NavigationRow(stringResource(R.string.view_waypoint_teaching), onWaypointTeaching)
    }
}

@Composable
private fun Joystick(actions: DollyBotActions) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = Modifier.size(144.dp)) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.minDimension / 2 - 2.dp.toPx()
            drawCircle(colors.surfaceContainer, radius)
            drawCircle(colors.outlineVariant, radius, style = Stroke(1.dp.toPx()))
            drawCircle(colors.outlineVariant, radius * 0.62f, style = Stroke(1.dp.toPx()))
            drawCircle(colors.onSurfaceVariant, radius * 0.16f)
        }
        DirectionButton("↑", R.string.forward, Modifier.align(Alignment.TopCenter)) {
            actions.drive(DriveDirection.Forward)
        }
        DirectionButton("↓", R.string.backward, Modifier.align(Alignment.BottomCenter)) {
            actions.drive(DriveDirection.Backward)
        }
        DirectionButton("←", R.string.strafe_left, Modifier.align(Alignment.CenterStart)) {
            actions.drive(DriveDirection.StrafeLeft)
        }
        DirectionButton("→", R.string.strafe_right, Modifier.align(Alignment.CenterEnd)) {
            actions.drive(DriveDirection.StrafeRight)
        }
    }
}

@Composable
private fun DirectionButton(
    symbol: String,
    @StringRes label: Int,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val description = stringResource(label)
    IconButton(
        onClick = onClick,
        enabled = false,
        modifier = modifier.size(48.dp).semantics { contentDescription = description }
    ) {
        Text(symbol, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun GimbalSlider(axis: GimbalAxis, angle: Float, actions: DollyBotActions) {
    val label = stringResource(axis.label)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = angle,
            onValueChange = { actions.adjustGimbal(axis, it) },
            enabled = false,
            valueRange = -180f..180f,
            modifier = Modifier.weight(1f).semantics { contentDescription = label }
        )
        Text(
            stringResource(R.string.angle_value, angle.roundToInt()),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun DeviceScreen(state: DeviceUiState, actions: DollyBotActions) {
    val isError = state.connection == ConnectionStatus.Error
    val feedback = when (state.connection) {
        ConnectionStatus.Disconnected -> R.string.waiting_for_device
        ConnectionStatus.Connected -> if (state.calibrated) {
            R.string.calibration_complete
        } else {
            R.string.waiting_for_calibration
        }
        ConnectionStatus.Error -> R.string.connection_error_feedback
    }

    PageStatus(stringResource(R.string.interface_preview))
    Panel {
        InfoRow(
            stringResource(R.string.device_status),
            stringResource(state.connection.label),
            isError = isError
        )
        InfoRow(
            stringResource(R.string.device_name),
            state.name ?: stringResource(R.string.unknown_value)
        )
        PlaceholderButton(
            stringResource(R.string.connect),
            actions::connect,
            modifier = Modifier.align(Alignment.End)
        )
        HorizontalDivider()
        InfoRow(
            stringResource(R.string.calibration_status),
            stringResource(state.calibrationLabel)
        )
        PlaceholderButton(
            stringResource(R.string.calibrate),
            actions::calibrate,
            modifier = Modifier.align(Alignment.End)
        )
    }
    Panel {
        Text(stringResource(R.string.device_feedback), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(feedback),
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
