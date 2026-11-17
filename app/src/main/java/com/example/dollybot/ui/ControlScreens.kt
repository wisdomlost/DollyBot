package com.example.dollybot.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.dollybot.R
import kotlin.math.roundToInt

private const val GimbalPreviewAngleRange = 180f

@Composable
internal fun ControlScreen(
    state: DollyBotUiState,
    motion: MotionInput,
    gestureSession: Int,
    onMove: (Float, Float) -> Unit,
    onRotate: (DriveDirection, Boolean) -> Unit,
    onGimbalChange: (Float, Float) -> Unit,
    onStopMotion: () -> Unit,
    onContinuousTeaching: () -> Unit,
    onWaypointTeaching: () -> Unit
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val stopMotion by rememberUpdatedState(onStopMotion)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) stopMotion()
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stopMotion()
        }
    }

    PageStatus(
        label = stringResource(
            if (!state.device.live) R.string.interface_preview
            else if (state.device.connection == ConnectionStatus.Connected) R.string.live_connection
            else R.string.local_controls
        ),
        status = stringResource(
            R.string.device_status_summary,
            stringResource(state.device.connection.label),
            stringResource(state.device.calibrationLabel)
        ),
        isError = state.device.connection == ConnectionStatus.Error
    )
    if (state.teaching?.activeMode == 1) {
        NavigationRow(
            stringResource(R.string.recording) + " · " + state.teaching.continuous.seconds + " s",
            onContinuousTeaching
        )
    }

    Panel {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    stringResource(R.string.drive),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center
                )
                ControlJoystick(
                    position = Offset(motion.strafe, motion.forward),
                    gestureSession = gestureSession,
                    description = stringResource(R.string.drive_joystick),
                    positionDescription = stringResource(
                        R.string.motion_input_value,
                        (motion.strafe * 100).roundToInt(),
                        (motion.forward * 100).roundToInt()
                    ),
                    onPositionChange = { onMove(it.x, it.y) },
                    onRelease = { onMove(0f, 0f) },
                    modifier = Modifier.testTag("drive_joystick")
                )
                Text(
                    stringResource(R.string.drive_joystick_hint),
                    style = MaterialTheme.typography.bodySmall
                )
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
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RotationButton(
                        label = stringResource(R.string.turn_left),
                        gestureSession = gestureSession,
                        onPressedChange = { onRotate(DriveDirection.TurnLeft, it) },
                        modifier = Modifier.weight(1f)
                    )
                    RotationButton(
                        label = stringResource(R.string.turn_right),
                        gestureSession = gestureSession,
                        onPressedChange = { onRotate(DriveDirection.TurnRight, it) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    stringResource(
                        when {
                            motion.rotation < 0f -> R.string.turning_left
                            motion.rotation > 0f -> R.string.turning_right
                            else -> R.string.rotation_hold_hint
                        }
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
        }
        Text(
            stringResource(
                R.string.motion_input_value,
                (motion.strafe * 100).roundToInt(),
                (motion.forward * 100).roundToInt()
            ),
            modifier = Modifier.testTag("motion_input"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Panel {
        Text(stringResource(R.string.gimbal), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.gimbal_joystick_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ControlJoystick(
                    position = Offset(
                        state.yawDegrees / GimbalPreviewAngleRange,
                        state.pitchDegrees / GimbalPreviewAngleRange
                    ),
                    gestureSession = gestureSession,
                    description = stringResource(R.string.gimbal_joystick),
                    positionDescription = stringResource(
                        R.string.gimbal_position_value,
                        state.yawDegrees.roundToInt(),
                        state.pitchDegrees.roundToInt()
                    ),
                    onPositionChange = {
                        onGimbalChange(it.x * GimbalPreviewAngleRange, it.y * GimbalPreviewAngleRange)
                    },
                    onRelease = {},
                    modifier = Modifier.testTag("gimbal_joystick")
                )
                Text(
                    stringResource(R.string.gimbal_hold_hint),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(
                    modifier = Modifier.testTag("gimbal_angles"),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        stringResource(
                            R.string.gimbal_axis_value,
                            stringResource(R.string.yaw),
                            state.yawDegrees.roundToInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        stringResource(
                            R.string.gimbal_axis_value,
                            stringResource(R.string.pitch),
                            state.pitchDegrees.roundToInt()
                        ),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                PlaceholderButton(
                    stringResource(R.string.gimbal_reset),
                    { onGimbalChange(0f, 0f) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = true
                )
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        NavigationRow(stringResource(R.string.view_continuous_teaching), onContinuousTeaching)
        NavigationRow(stringResource(R.string.view_waypoint_teaching), onWaypointTeaching)
    }
}

@Composable
private fun RotationButton(
    label: String,
    gestureSession: Int,
    onPressedChange: (Boolean) -> Unit,
    modifier: Modifier
) {
    val colors = MaterialTheme.colorScheme
    var pressed by remember(gestureSession) { mutableStateOf(false) }
    val changePressed by rememberUpdatedState(onPressedChange)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = if (pressed) colors.primary else colors.secondaryContainer,
        contentColor = if (pressed) colors.onPrimary else colors.onSecondaryContainer
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    onClick {
                        changePressed(true)
                        changePressed(false)
                        true
                    }
                }
                .onKeyEvent { event ->
                    if (event.key != Key.Enter && event.key != Key.NumPadEnter &&
                        event.key != Key.Spacebar
                    ) return@onKeyEvent false
                    pressed = event.type == KeyEventType.KeyDown
                    changePressed(pressed)
                    true
                }
                .focusable()
                .pointerInput(gestureSession) {
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            changePressed(true)
                            try {
                                tryAwaitRelease()
                            } finally {
                                pressed = false
                                changePressed(false)
                            }
                        }
                    )
                }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
internal fun DeviceScreen(state: DeviceUiState, actions: DollyBotActions) {
    val isError = state.connection == ConnectionStatus.Error
    val feedback = when (state.connection) {
        ConnectionStatus.Disconnected -> R.string.waiting_for_device
        ConnectionStatus.Connecting -> R.string.bluetooth_handshaking
        ConnectionStatus.Connected -> if (state.calibrated) {
            R.string.calibration_complete
        } else {
            R.string.waiting_for_calibration
        }
        ConnectionStatus.Error -> R.string.connection_error_feedback
    }

    val connected = state.connection == ConnectionStatus.Connected
    val connecting = state.connection == ConnectionStatus.Connecting
    PageStatus(stringResource(if (state.live) R.string.live_connection else R.string.interface_preview))
    if (state.live) {
        Panel {
            Text(stringResource(R.string.paired_devices), style = MaterialTheme.typography.titleMedium)
            for (device in state.pairedDevices) {
                Surface(
                    onClick = { actions.selectDevice(device.address) },
                    enabled = !connected && !connecting,
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = device.address == state.selectedAddress,
                            onClick = null,
                            enabled = !connected && !connecting
                        )
                        Column(Modifier.weight(1f).padding(8.dp)) {
                            Text(device.name, style = MaterialTheme.typography.bodyMedium)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            PlaceholderButton(
                stringResource(R.string.refresh_paired_devices),
                actions::refreshDevices,
                enabled = !connected && !connecting && state.available
            )
        }
    }
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
            stringResource(if (connected || connecting) R.string.disconnect else R.string.connect),
            if (connected || connecting) actions::disconnect else actions::connect,
            modifier = Modifier.align(Alignment.End),
            enabled = state.live && state.available &&
                (connected || connecting || !state.permissionGranted || state.selectedAddress != null)
        )
        HorizontalDivider()
        InfoRow(
            stringResource(R.string.calibration_status),
            stringResource(state.calibrationLabel)
        )
        PlaceholderButton(
            stringResource(R.string.calibrate),
            actions::calibrate,
            modifier = Modifier.align(Alignment.End),
            enabled = state.live && connected
        )
    }
    Panel {
        Text(stringResource(R.string.device_feedback), style = MaterialTheme.typography.titleMedium)
        Text(
            state.message ?: stringResource(feedback),
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
