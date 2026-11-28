package com.example.dollybot.ui

import androidx.annotation.StringRes
import com.example.dollybot.R
import com.example.dollybot.connection.LivePose
import com.example.dollybot.connection.PowerStatus
import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.TrajectoryEditorState

internal enum class ConnectionStatus(@param:StringRes val label: Int) {
    Disconnected(R.string.disconnected),
    Connecting(R.string.connecting),
    Connected(R.string.connected),
    Error(R.string.connection_error)
}

internal data class DeviceUiState(
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val name: String? = null,
    val calibrated: Boolean = false,
    val live: Boolean = false,
    val permissionGranted: Boolean = false,
    val available: Boolean = true,
    val pairedDevices: List<PairedDevice> = emptyList(),
    val selectedAddress: String? = null,
    val message: String? = null,
    val power: PowerStatus? = null
) {
    val powerAlert: Int?
        get() = when {
            power == null -> null
            power.adcFault -> R.string.power_adc_fault
            power.motorTrip && power.gimbalTrip -> R.string.power_both_overcurrent
            power.motorTrip -> R.string.power_motor_overcurrent
            power.gimbalTrip -> R.string.power_gimbal_overcurrent
            power.batteryOutOfRange -> R.string.power_voltage_fault
            else -> null
        }

    val calibrationLabel: Int
        get() = if (calibrated) R.string.calibrated else R.string.uncalibrated
}

internal data class PairedDevice(val address: String, val name: String)

internal enum class TeachingPhase(@param:StringRes val label: Int) {
    Empty(R.string.waiting_for_recording),
    Recording(R.string.recording),
    Finished(R.string.teaching_finished),
    Interrupted(R.string.teaching_interrupted)
}

internal data class TeachingRun(
    val id: Long = 0,
    val poses: List<LivePose> = emptyList(),
    val phase: TeachingPhase = TeachingPhase.Empty,
    val incomplete: Boolean = false,
    val lastIndex: Long = 0
) {
    val seconds: Int
        get() = if (poses.size < 2) 0 else (((poses.last().timeMs - poses.first().timeMs) and 0xFFFFFFFFL) / 1000).toInt()
}

internal data class TeachingUiState(
    val pose: LivePose? = null,
    val continuous: TeachingRun = TeachingRun(),
    val waypoints: TeachingRun = TeachingRun(),
    val activeMode: Int = 0,
    val busy: Boolean = false,
    val message: String? = null
)

internal enum class TrajectoryKind(@param:StringRes val label: Int) {
    Waypoints(R.string.point_trajectories),
    Continuous(R.string.continuous_trajectories)
}

internal enum class RecordingMode(val wireMode: Int, val kind: TrajectoryKind) {
    Continuous(1, TrajectoryKind.Continuous),
    Waypoints(2, TrajectoryKind.Waypoints),
    Push(3, TrajectoryKind.Continuous);

    companion object {
        fun fromKind(kind: TrajectoryKind) = if (kind == TrajectoryKind.Waypoints) Waypoints else Continuous
    }
}

internal enum class RecordingStage {
    Idle, Ready, Starting, Recording, Finishing, PendingSave
}

internal data class RecordingUiState(
    val mode: RecordingMode? = null,
    val stage: RecordingStage = RecordingStage.Idle,
    val draftId: String? = null,
    val pending: SavedTrajectory? = null,
    val showSaveDialog: Boolean = false,
    val busy: Boolean = false,
    val message: String? = null
) {
    val blocksDriving: Boolean
        get() = mode == RecordingMode.Push && stage in listOf(
            RecordingStage.Starting, RecordingStage.Recording, RecordingStage.Finishing
        )
}

internal data class TrajectoryUploadUiState(
    val trajectoryId: String,
    val updatedAt: Long,
    val totalPoints: Int,
    val sentPoints: Int = 0,
    val sending: Boolean = true,
    val error: String? = null
)

internal data class DollyBotUiState(
    val device: DeviceUiState = DeviceUiState(),
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val teaching: TeachingUiState = TeachingUiState(),
    val recording: RecordingUiState = RecordingUiState(),
    val trajectories: List<SavedTrajectory> = emptyList(),
    val libraryLoading: Boolean = false,
    val editor: TrajectoryEditorState? = null,
    val replayState: String = "",
    val storedRepeats: Int = 1,
    val chassisConfigured: Boolean = true,
    val storedTask: Boolean = false,
    val trajectoryUpload: TrajectoryUploadUiState? = null
)

internal enum class DriveDirection {
    Forward, Backward, StrafeLeft, StrafeRight, TurnLeft, TurnRight
}

// Frontend input only. Each axis ranges from -1 to 1, with forward and right positive.
internal data class MotionInput(
    val strafe: Float = 0f,
    val forward: Float = 0f,
    val rotation: Float = 0f
)

internal enum class GimbalAxis(@param:StringRes val label: Int) {
    Yaw(R.string.yaw),
    Pitch(R.string.pitch)
}

internal interface DollyBotActions {
    fun connect() = Unit
    fun calibrate() = Unit
    fun setControlPageActive(active: Boolean) = Unit
    fun drive(direction: DriveDirection) = Unit
    fun move(input: MotionInput) = Unit
    fun adjustGimbal(axis: GimbalAxis, degrees: Float) = Unit
    fun selectRecordingMode(mode: RecordingMode) = Unit
    fun startRecording(mode: RecordingMode) = Unit
    fun finishRecording() = Unit
    fun captureWaypoint() = Unit
    fun showRecordingSaveDialog() = Unit
    fun dismissRecordingSaveDialog() = Unit
    fun saveRecording(name: String) = Unit
    fun discardRecording() = Unit
    fun openTrajectory(id: String) = Unit
    fun sendTrajectory(id: String) = Unit
    fun startReplay(repeats: Int) = Unit
    fun editPlayback(speed: Int, smooth: Boolean, repeats: Int) = Unit
    fun editKeyframe(dwellMs: Int, pan: Float, tilt: Float) = Unit
    fun renameEditedTrajectory(name: String) = Unit
    fun selectEditedPoint(id: String) = Unit
    fun moveEditedPoint(delta: Int) = Unit
    fun deleteEditedPoint() = Unit
    fun saveEditedTrajectory(closeAfter: Boolean = false) = Unit
    fun closeEditor() = Unit
    fun deleteTrajectory(id: String) = Unit
    fun togglePreview() = Unit
    fun resetPreview() = Unit
    fun stopMotion() = Unit
    fun refreshDevices() = Unit
    fun selectDevice(address: String) = Unit
    fun disconnect() = Unit
}

internal object NoOpDollyBotActions : DollyBotActions
