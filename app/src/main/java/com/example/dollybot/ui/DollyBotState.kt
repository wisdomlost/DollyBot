package com.example.dollybot.ui

import androidx.annotation.StringRes
import com.example.dollybot.R

internal enum class ConnectionStatus(@param:StringRes val label: Int) {
    Disconnected(R.string.disconnected),
    Connected(R.string.connected),
    Error(R.string.connection_error)
}

internal data class DeviceUiState(
    val connection: ConnectionStatus = ConnectionStatus.Disconnected,
    val name: String? = null,
    val calibrated: Boolean = false
) {
    val calibrationLabel: Int
        get() = if (calibrated) R.string.calibrated else R.string.uncalibrated
}

internal enum class TrajectoryKind(@param:StringRes val label: Int) {
    Waypoints(R.string.waypoints),
    Continuous(R.string.continuous_teaching)
}

// Positions are normalized to the preview grid. Headings are clockwise from screen right.
internal data class DiagramPoint(val x: Float, val y: Float, val heading: Float)

internal data class GimbalKeyframe(
    // One-based waypoint number or elapsed seconds, according to the trajectory kind.
    val position: Int,
    val yawDegrees: Int,
    val pitchDegrees: Int
)

internal data class TrajectoryUiState(
    @param:StringRes val name: Int,
    val kind: TrajectoryKind,
    val points: List<DiagramPoint>,
    val durationSeconds: Int,
    val speed: Float,
    val dwellSeconds: Int,
    val repetitions: Int,
    val keyframes: List<GimbalKeyframe>
)

internal enum class PlaybackPhase(@param:StringRes val label: Int) {
    Ready(R.string.ready_to_start),
    Running(R.string.executing),
    Fault(R.string.fault)
}

internal data class PlaybackUiState(
    val phase: PlaybackPhase = PlaybackPhase.Ready,
    val progressPercent: Int = 0,
    val uploaded: Boolean = false,
    val startConfirmed: Boolean = false
)

internal data class DollyBotUiState(
    val device: DeviceUiState = DeviceUiState(),
    val yawDegrees: Float = 0f,
    val pitchDegrees: Float = 0f,
    val recordingSeconds: Int? = null,
    val playback: PlaybackUiState = PlaybackUiState(),
    val waypointTrajectory: TrajectoryUiState = sampleWaypoints,
    val continuousTrajectory: TrajectoryUiState = sampleContinuous
)

internal enum class DriveDirection {
    Forward, Backward, StrafeLeft, StrafeRight, TurnLeft, TurnRight
}

internal enum class GimbalAxis(@param:StringRes val label: Int) {
    Yaw(R.string.yaw),
    Pitch(R.string.pitch)
}

internal interface DollyBotActions {
    fun connect()
    fun calibrate()
    fun drive(direction: DriveDirection)
    fun adjustGimbal(axis: GimbalAxis, degrees: Float)
    fun startRecording()
    fun finishRecording()
    fun captureWaypoint()
    fun save(trajectory: TrajectoryUiState)
    fun upload(trajectory: TrajectoryUiState)
    fun confirmStart()
    fun startPlayback()
    fun stopMotion()
}

internal object NoOpDollyBotActions : DollyBotActions {
    override fun connect() = Unit
    override fun calibrate() = Unit
    override fun drive(direction: DriveDirection) = Unit
    override fun adjustGimbal(axis: GimbalAxis, degrees: Float) = Unit
    override fun startRecording() = Unit
    override fun finishRecording() = Unit
    override fun captureWaypoint() = Unit
    override fun save(trajectory: TrajectoryUiState) = Unit
    override fun upload(trajectory: TrajectoryUiState) = Unit
    override fun confirmStart() = Unit
    override fun startPlayback() = Unit
    override fun stopMotion() = Unit
}

private val sampleWaypoints = TrajectoryUiState(
    name = R.string.table_slide,
    kind = TrajectoryKind.Waypoints,
    points = listOf(
        DiagramPoint(0.07f, 0.58f, -90f),
        DiagramPoint(0.50f, 0.58f, -90f),
        DiagramPoint(0.93f, 0.58f, -90f)
    ),
    durationSeconds = 0,
    speed = 1f,
    dwellSeconds = 1,
    repetitions = 1,
    keyframes = listOf(GimbalKeyframe(1, 0, 0), GimbalKeyframe(3, 0, 0))
)

private val sampleContinuous = TrajectoryUiState(
    name = R.string.slow_push,
    kind = TrajectoryKind.Continuous,
    points = listOf(
        DiagramPoint(0.07f, 0.58f, 0f),
        DiagramPoint(0.32f, 0.58f, 0f),
        DiagramPoint(0.61f, 0.58f, 0f),
        DiagramPoint(0.93f, 0.58f, 0f)
    ),
    durationSeconds = 8,
    speed = 1f,
    dwellSeconds = 0,
    repetitions = 1,
    keyframes = listOf(GimbalKeyframe(0, 0, 0), GimbalKeyframe(8, 0, -10))
)
