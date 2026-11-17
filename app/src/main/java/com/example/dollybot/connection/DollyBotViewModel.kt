package com.example.dollybot.connection

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.dollybot.R
import com.example.dollybot.ui.ConnectionStatus
import com.example.dollybot.ui.DeviceUiState
import com.example.dollybot.ui.DollyBotActions
import com.example.dollybot.ui.DollyBotUiState
import com.example.dollybot.ui.DriveDirection
import com.example.dollybot.ui.GimbalAxis
import com.example.dollybot.ui.MotionInput
import com.example.dollybot.ui.NoOpDollyBotActions
import com.example.dollybot.ui.PairedDevice
import com.example.dollybot.ui.TeachingPhase
import com.example.dollybot.ui.TeachingRun
import com.example.dollybot.ui.TeachingUiState
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

internal class DollyBotViewModel(application: Application) :
    AndroidViewModel(application), DollyBotActions by NoOpDollyBotActions {

    private val mutableState = MutableStateFlow(
        DollyBotUiState(device = DeviceUiState(live = true), teaching = TeachingUiState())
    )
    val state = mutableState.asStateFlow()
    private val adapter = application.getSystemService(BluetoothManager::class.java)?.adapter
    private var link: BluetoothLink? = null
    private var session = 0L
    private var sequence = 0
    private var connectionJob: Job? = null
    private var pump: Job? = null
    private var heartbeat: Job? = null
    private var teachingJob: Job? = null
    private var motion = MotionInput()
    private var yaw = 0f
    private var pitch = 0f
    private var gimbalDirty = false
    private var gimbalInitialized = false
    private var endingTeachId: Long? = null
    private var lastStatusAt = 0L
    private data class Pending(val type: Int, val reply: CompletableDeferred<Packet>)
    private val pending = mutableMapOf<Int, Pending>()

    val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    init {
        refreshDevices()
    }

    @SuppressLint("MissingPermission")
    override fun refreshDevices() {
        val permitted = hasPermission
        val devices = if (permitted && adapter != null) {
            runCatching {
                adapter?.bondedDevices.orEmpty().map {
                    PairedDevice(it.address, it.name ?: it.address)
                }.sortedBy { it.name }
            }.getOrDefault(emptyList())
        } else emptyList()
        mutableState.update {
            val selected = it.device.selectedAddress?.takeIf { address ->
                devices.any { device -> device.address == address }
            } ?: devices.firstOrNull()?.address
            it.copy(device = it.device.copy(
                permissionGranted = permitted,
                available = adapter != null,
                pairedDevices = devices,
                selectedAddress = selected,
                message = when {
                    adapter == null -> text(R.string.bluetooth_unavailable)
                    !permitted -> text(R.string.bluetooth_permission_needed)
                    adapter?.isEnabled != true -> text(R.string.bluetooth_disabled)
                    devices.isEmpty() -> text(R.string.pair_computer_first)
                    else -> null
                }
            ))
        }
    }

    override fun selectDevice(address: String) {
        if (state.value.device.connection == ConnectionStatus.Connecting ||
            state.value.device.connection == ConnectionStatus.Connected
        ) return
        mutableState.update { it.copy(device = it.device.copy(selectedAddress = address)) }
    }

    @SuppressLint("MissingPermission")
    override fun connect() {
        if (!hasPermission || adapter == null) {
            refreshDevices()
            return
        }
        val address = state.value.device.selectedAddress ?: return
        if (connectionJob?.isActive == true || state.value.device.connection == ConnectionStatus.Connected) return
        releaseConnection()
        session = SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        if (session == 0L) session = 1L
        sequence = 0
        gimbalDirty = false
        gimbalInitialized = false
        val currentSession = session
        val selected = state.value.device.pairedDevices.firstOrNull { it.address == address }
        mutableState.update {
            it.copy(
                device = it.device.copy(
                    connection = ConnectionStatus.Connecting,
                    calibrated = false,
                    name = selected?.name,
                    message = text(R.string.bluetooth_handshaking)
                ),
                teaching = TeachingUiState()
            )
        }
        val connection = BluetoothLink(
            viewModelScope,
            receive = { packet ->
                viewModelScope.launch { if (session == currentSession) receive(packet) }
            },
            disconnected = { error ->
                viewModelScope.launch {
                    if (session == currentSession) connectionFailed(error.message)
                }
            }
        )
        link = connection
        connectionJob = viewModelScope.launch {
            try {
                connection.connect(adapter.getRemoteDevice(address))
                request(Wire.HELLO)
                lastStatusAt = SystemClock.elapsedRealtime()
                mutableState.update {
                    it.copy(device = it.device.copy(
                        connection = ConnectionStatus.Connected,
                        message = null
                    ))
                }
                pump = viewModelScope.launch {
                    try {
                        while (true) {
                            val now = SystemClock.elapsedRealtime()
                            if (now - lastStatusAt > 3000) throw IOException(text(R.string.telemetry_timeout))
                            sendMotion()
                            if (gimbalDirty) {
                                sendGimbal()
                                gimbalDirty = false
                            }
                            delay(50)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        connectionFailed(error.message)
                    }
                }
                heartbeat = viewModelScope.launch {
                    try {
                        while (true) {
                            delay(1000)
                            request(Wire.PING)
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        connectionFailed(error.message)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                connectionFailed(error.message)
            }
        }
    }

    override fun disconnect() {
        connectionJob?.cancel()
        releaseConnection()
        mutableState.update { it.copy(device = it.device.copy(
            connection = ConnectionStatus.Disconnected, calibrated = false, message = null
        )) }
    }

    override fun move(input: MotionInput) {
        motion = input.copy(
            strafe = input.strafe.coerceIn(-1f, 1f),
            forward = input.forward.coerceIn(-1f, 1f),
            rotation = input.rotation.coerceIn(-1f, 1f)
        )
        if (state.value.device.connection == ConnectionStatus.Connected && motion == MotionInput()) {
            sendMotion()
        }
    }

    override fun drive(direction: DriveDirection) {
        move(when (direction) {
            DriveDirection.Forward -> MotionInput(forward = 1f)
            DriveDirection.Backward -> MotionInput(forward = -1f)
            DriveDirection.StrafeLeft -> MotionInput(strafe = -1f)
            DriveDirection.StrafeRight -> MotionInput(strafe = 1f)
            DriveDirection.TurnLeft -> MotionInput(rotation = -1f)
            DriveDirection.TurnRight -> MotionInput(rotation = 1f)
        })
    }

    override fun adjustGimbal(axis: GimbalAxis, degrees: Float) {
        if (axis == GimbalAxis.Yaw) yaw = degrees.coerceIn(-180f, 180f)
        else pitch = degrees.coerceIn(-180f, 180f)
        gimbalDirty = true
        mutableState.update { it.copy(yawDegrees = yaw, pitchDegrees = pitch) }
    }

    override fun stopMotion() {
        motion = MotionInput()
        if (state.value.device.connection != ConnectionStatus.Connected) return
        viewModelScope.launch {
            runCatching { request(Wire.STOP) }.onFailure { showError(it.message) }
        }
    }

    override fun calibrate() = teachingOperation {
        check(state.value.teaching?.activeMode == 0) { text(R.string.finish_teaching_first) }
        val reply = request(Wire.CALIBRATE)
        val frame = ackValue(reply)
        mutableState.update {
            it.copy(
                device = it.device.copy(calibrated = true),
                teaching = TeachingUiState(
                    pose = it.teaching?.pose?.copy(frameId = frame, xMm = 0, yMm = 0, yawDegrees = 0f),
                    busy = true
                )
            )
        }
    }

    override fun startRecording() = teachingOperation {
        endTeaching()
        beginTeaching(1)
    }

    override fun finishRecording() = teachingOperation { endTeaching() }

    override fun captureWaypoint() = teachingOperation {
        val teaching = state.value.teaching!!
        if (teaching.activeMode != 2) {
            endTeaching()
            beginTeaching(2)
        }
        val id = state.value.teaching!!.waypoints.id
        val packet = request(Wire.TAKE_POINT, Wire.buffer(4).putInt(id.toInt()).array())
        val point = TeachingPoint.decode(packet.payload)
        val current = state.value.teaching!!
        require(point.teachId == id && point.pose.frameId == current.pose?.frameId)
        if (point.index > current.waypoints.lastIndex) {
            mutableState.update {
                val run = it.teaching!!.waypoints
                it.copy(teaching = it.teaching.copy(waypoints = run.copy(
                    poses = run.poses + point.pose,
                    incomplete = run.incomplete || point.index != run.lastIndex + 1L,
                    lastIndex = point.index
                )))
            }
        }
    }

    fun onBackground() {
        if (state.value.device.connection != ConnectionStatus.Connected) return
        stopMotion()
        viewModelScope.launch {
            teachingJob?.cancel()
            runCatching { withTimeout(1000) { endTeaching(interrupted = true) } }
            disconnect()
        }
    }

    private fun teachingOperation(operation: suspend () -> Unit) {
        if (state.value.device.connection != ConnectionStatus.Connected ||
            state.value.teaching?.busy == true
        ) return
        mutableState.update { it.copy(
            device = it.device.copy(message = null),
            teaching = it.teaching!!.copy(busy = true, message = null)
        ) }
        teachingJob = viewModelScope.launch {
            try {
                operation()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showError(error.message)
            } finally {
                mutableState.update { it.copy(teaching = it.teaching!!.copy(busy = false)) }
            }
        }
    }

    private suspend fun beginTeaching(mode: Int) {
        val current = state.value.teaching!!
        check(state.value.device.calibrated && current.pose != null) { text(R.string.calibrate_first) }
        val reply = request(
            Wire.TEACH_BEGIN,
            Wire.buffer(5).put(mode.toByte()).putInt(current.pose.frameId.toInt()).array()
        )
        ensureRun(mode, ackValue(reply))
    }

    private suspend fun endTeaching(interrupted: Boolean = false) {
        val current = state.value.teaching!!
        val mode = current.activeMode
        if (mode == 0) return
        val run = if (mode == 1) current.continuous else current.waypoints
        endingTeachId = run.id
        try {
            val expected = ackValue(request(Wire.TEACH_END, Wire.buffer(4).putInt(run.id.toInt()).array()))
            mutableState.update {
                val latest = it.teaching!!
                val completed = if (mode == 1) latest.continuous else latest.waypoints
                val incomplete = completed.incomplete || expected != completed.poses.size.toLong()
                val result = completed.copy(
                    phase = if (interrupted || incomplete) TeachingPhase.Interrupted else TeachingPhase.Finished,
                    incomplete = incomplete
                )
                it.copy(teaching = latest.copy(
                    activeMode = 0,
                    continuous = if (mode == 1) result else latest.continuous,
                    waypoints = if (mode == 2) result else latest.waypoints,
                    message = if (incomplete) text(R.string.sample_count_mismatch) else latest.message
                ))
            }
        } finally {
            endingTeachId = null
        }
    }

    private fun ensureRun(mode: Int, id: Long) {
        mutableState.update {
            val current = it.teaching!!
            val previous = if (mode == 1) current.continuous else current.waypoints
            val run = if (previous.id == id) previous else TeachingRun(id = id)
            val active = run.copy(phase = TeachingPhase.Recording)
            it.copy(teaching = current.copy(
                activeMode = mode,
                continuous = if (mode == 1) active else current.continuous,
                waypoints = if (mode == 2) active else current.waypoints
            ))
        }
    }

    private fun receive(packet: Packet) {
        if (packet.session != session || packet.version != 1) return
        try {
            when (packet.type) {
                Wire.STATUS -> {
                    val status = CarStatus.decode(packet.payload)
                    lastStatusAt = SystemClock.elapsedRealtime()
                    if (!gimbalInitialized) {
                        yaw = status.pose.gimbalYaw
                        pitch = status.pose.gimbalPitch
                        gimbalInitialized = true
                        mutableState.update { it.copy(yawDegrees = yaw, pitchDegrees = pitch) }
                    }
                    if (status.mode in 1..2) ensureRun(status.mode, status.teachId)
                    mutableState.update {
                        var teaching = it.teaching!!
                        if (status.mode == 1) {
                            val run = teaching.continuous
                            if (status.pointIndex > run.lastIndex) {
                                teaching = teaching.copy(continuous = run.copy(
                                    poses = run.poses + status.pose,
                                    incomplete = run.incomplete || status.pointIndex != run.lastIndex + 1L,
                                    lastIndex = status.pointIndex
                                ))
                            }
                        } else if (status.mode == 0 && teaching.activeMode != 0 &&
                            endingTeachId == null
                        ) {
                            teaching = interrupted(teaching)
                        }
                        it.copy(
                            device = it.device.copy(calibrated = status.flags and 1 != 0),
                            teaching = teaching.copy(pose = status.pose)
                        )
                    }
                }
                Wire.ACK, Wire.POINT -> {
                    val expected = pending[packet.seq] ?: return
                    if (packet.type == Wire.ACK) {
                        require(packet.payload.size == 6)
                        require(packet.payload[0].toInt() and 0xFF == expected.type)
                    } else require(expected.type == Wire.TAKE_POINT && packet.payload.size == 30)
                    expected.reply.complete(packet)
                }
            }
        } catch (error: IllegalArgumentException) {
            showError(text(R.string.invalid_packet))
        }
    }

    private suspend fun request(type: Int, data: ByteArray = byteArrayOf()): Packet {
        val seq = nextSequence()
        val packet = Packet(type, seq, session, data)
        val response = CompletableDeferred<Packet>()
        pending[seq] = Pending(type, response)
        try {
            repeat(3) {
                link?.send(packet) ?: throw IOException(text(R.string.disconnected))
                val result = withTimeoutOrNull(1000) { response.await() }
                if (result != null) {
                    if (result.type == Wire.ACK) {
                        val code = result.payload[1].toInt() and 0xFF
                        check(code == 0) {
                            text(when (code) {
                                1 -> R.string.command_bad_state
                                2 -> R.string.calibrate_first
                                3 -> R.string.command_bad_argument
                                4 -> R.string.command_frame_mismatch
                                else -> R.string.command_unsupported
                            })
                        }
                    }
                    return result
                }
            }
            throw IOException(text(R.string.command_timeout))
        } finally {
            if (pending[seq]?.reply === response) pending.remove(seq)
        }
    }

    private fun ackValue(packet: Packet): Long = Wire.uint(Wire.read(packet.payload).getInt(2))

    private fun nextSequence(): Int {
        do { sequence = (sequence + 1) and 0xFFFF } while (pending.containsKey(sequence))
        return sequence
    }

    private fun sendMotion() {
        val data = Wire.buffer(6)
            .putShort((motion.strafe * 1000).roundToInt().toShort())
            .putShort((motion.forward * 1000).roundToInt().toShort())
            .putShort((motion.rotation * 1000).roundToInt().toShort()).array()
        link?.send(Packet(Wire.MOTION, nextSequence(), session, data))
    }

    private fun sendGimbal() {
        val data = Wire.buffer(4).putShort((yaw * 100).roundToInt().toShort())
            .putShort((pitch * 100).roundToInt().toShort()).array()
        link?.send(Packet(Wire.GIMBAL, nextSequence(), session, data))
    }

    private fun interrupted(teaching: TeachingUiState): TeachingUiState = teaching.copy(
        activeMode = 0,
        continuous = if (teaching.continuous.phase == TeachingPhase.Recording) {
            teaching.continuous.copy(phase = TeachingPhase.Interrupted)
        } else teaching.continuous,
        waypoints = if (teaching.waypoints.phase == TeachingPhase.Recording) {
            teaching.waypoints.copy(phase = TeachingPhase.Interrupted)
        } else teaching.waypoints
    )

    private fun releaseConnection() {
        pump?.cancel()
        heartbeat?.cancel()
        teachingJob?.cancel()
        link?.close()
        link = null
        pending.values.forEach { it.reply.completeExceptionally(IOException(text(R.string.disconnected))) }
        pending.clear()
        motion = MotionInput()
        mutableState.update { it.copy(teaching = interrupted(it.teaching!!).copy(busy = false)) }
    }

    private fun connectionFailed(message: String?) {
        releaseConnection()
        mutableState.update { it.copy(device = it.device.copy(
            connection = ConnectionStatus.Error,
            calibrated = false,
            message = message ?: text(R.string.connection_error_feedback)
        )) }
    }

    private fun showError(message: String?) {
        mutableState.update { it.copy(teaching = it.teaching!!.copy(
            message = message ?: text(R.string.command_timeout)
        ), device = it.device.copy(message = message)) }
    }

    private fun text(id: Int): String = getApplication<Application>().getString(id)

    override fun onCleared() {
        link?.close()
        super.onCleared()
    }
}
