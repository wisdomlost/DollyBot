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
import com.example.dollybot.ui.TrajectoryKind
import com.example.dollybot.ui.RecordingStage
import com.example.dollybot.ui.RecordingUiState
import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.TrajectoryEditorState
import com.example.dollybot.trajectory.TrajectoryStore
import com.example.dollybot.trajectory.PreviewTimeline
import java.io.IOException
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class DollyBotViewModel @JvmOverloads constructor(
    application: Application,
    private val store: TrajectoryStore = TrajectoryStore(application),
    private val createLink: (CoroutineScope, (Packet) -> Unit, (Throwable) -> Unit) -> CarLink = ::BluetoothLink
) :
    AndroidViewModel(application), DollyBotActions by NoOpDollyBotActions {

    private val mutableState = MutableStateFlow(
        DollyBotUiState(device = DeviceUiState(live = true), libraryLoading = true)
    )
    val state = mutableState.asStateFlow()
    private val adapter = application.getSystemService(BluetoothManager::class.java)?.adapter
    private var link: CarLink? = null
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
    private val storageMutex = Mutex()
    private var finalizationJob: Job? = null
    private var previewJob: Job? = null

    val hasPermission: Boolean
        get() = Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED

    init {
        refreshDevices()
        viewModelScope.launch {
            try {
                val all = withContext(Dispatchers.IO) { store.all() }
                val draft = all.firstOrNull { it.isDraft }
                mutableState.update {
                    it.copy(
                        trajectories = all.filterNot { trajectory -> trajectory.isDraft },
                        libraryLoading = false,
                        recording = if (draft == null) it.recording else RecordingUiState(
                            mode = draft.kind, stage = RecordingStage.PendingSave,
                            draftId = draft.id, pending = draft, showSaveDialog = true
                        )
                    )
                }
            } catch (error: Exception) {
                mutableState.update { it.copy(
                    libraryLoading = false,
                    recording = it.recording.copy(message = text(R.string.storage_failed))
                ) }
            }
        }
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
        val connection = createLink(
            viewModelScope,
            { packet ->
                viewModelScope.launch { if (session == currentSession) receive(packet) }
            },
            { error ->
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

    override fun selectRecordingMode(kind: TrajectoryKind) {
        if (state.value.libraryLoading || state.value.recording.stage !in
            listOf(RecordingStage.Idle, RecordingStage.Ready)
        ) return
        mutableState.update { it.copy(recording = RecordingUiState(
            mode = kind, stage = RecordingStage.Ready
        )) }
    }

    override fun startRecording(kind: TrajectoryKind) = teachingOperation {
        check(state.value.recording.stage == RecordingStage.Ready &&
            state.value.recording.mode == kind && !state.value.libraryLoading
        )
        mutableState.update { it.copy(
            recording = it.recording.copy(
                stage = RecordingStage.Starting, draftId = UUID.randomUUID().toString(), message = null
            ),
            teaching = it.teaching.copy(
                continuous = if (kind == TrajectoryKind.Continuous) TeachingRun() else it.teaching.continuous,
                waypoints = if (kind == TrajectoryKind.Waypoints) TeachingRun() else it.teaching.waypoints
            )
        ) }
        try {
            beginTeaching(if (kind == TrajectoryKind.Continuous) 1 else 2)
            mutableState.update { it.copy(recording = it.recording.copy(stage = RecordingStage.Recording)) }
        } catch (error: Exception) {
            // Cancellation can race with disconnect finalization; never replace its pending draft.
            if (state.value.recording.stage == RecordingStage.Starting) {
                if (state.value.teaching.activeMode == 0) {
                    mutableState.update { it.copy(recording = it.recording.copy(stage = RecordingStage.Ready)) }
                } else if (error !is CancellationException) {
                    finalizeRecording(interrupted = true)
                }
            }
            throw error
        }
    }

    override fun finishRecording() {
        finalizeRecording(interrupted = false)
    }

    override fun captureWaypoint() = teachingOperation {
        val teaching = state.value.teaching!!
        check(teaching.activeMode == 2 &&
            state.value.recording.stage == RecordingStage.Recording
        ) { text(R.string.start_point_recording_first) }
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
        previewJob?.cancel()
        mutableState.update { it.copy(editor = it.editor?.copy(playing = false)) }
        if (state.value.device.connection != ConnectionStatus.Connected) return
        stopMotion()
        teachingJob?.cancel()
        finalizeRecording(interrupted = true)
        viewModelScope.launch {
            finalizationJob?.join()
            disconnect()
        }
    }

    private fun finalizeRecording(interrupted: Boolean) {
        val recording = state.value.recording
        if (recording.stage !in listOf(RecordingStage.Starting, RecordingStage.Recording) ||
            finalizationJob?.isActive == true
        ) return
        val kind = recording.mode ?: return
        motion = MotionInput()
        mutableState.update { it.copy(recording = recording.copy(
            stage = RecordingStage.Finishing, busy = true
        )) }
        finalizationJob = viewModelScope.launch {
            var failed = interrupted
            try {
                if (state.value.device.connection == ConnectionStatus.Connected) {
                    // Await parking before ending so an asynchronously launched STOP cannot be overtaken.
                    request(Wire.STOP)
                    endTeaching(interrupted)
                } else failed = true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failed = true
                connectionFailed(error.message)
            }
            if (failed) mutableState.update { it.copy(teaching = interrupted(it.teaching)) }
            val teaching = state.value.teaching
            val run = if (kind == TrajectoryKind.Continuous) teaching.continuous else teaching.waypoints
            persistEndedRun(kind, recording.draftId!!, run, failed)
        }
    }

    private suspend fun persistEndedRun(kind: TrajectoryKind, id: String, run: TeachingRun, failed: Boolean) {
        if (run.poses.isEmpty()) {
            mutableState.update { it.copy(recording = RecordingUiState(message = text(R.string.no_recorded_data))) }
            return
        }
        val draft = SavedTrajectory.recorded(
            kind, run.poses, run.incomplete || failed || run.phase == TeachingPhase.Interrupted, id
        ).let {
            it.copy(name = text(kind.label) + " " +
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(it.createdAt)))
        }
        var message: String? = null
        try {
            storageMutex.withLock { withContext(Dispatchers.IO) { store.put(draft) } }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            message = text(R.string.storage_failed)
        }
        mutableState.update {
            it.copy(recording = RecordingUiState(
                mode = kind, stage = RecordingStage.PendingSave, draftId = id,
                pending = draft, showSaveDialog = true, message = message
            ))
        }
    }

    override fun showRecordingSaveDialog() {
        mutableState.update { it.copy(recording = it.recording.copy(showSaveDialog = true)) }
    }

    override fun dismissRecordingSaveDialog() {
        mutableState.update { it.copy(recording = it.recording.copy(showSaveDialog = false)) }
    }

    override fun saveRecording(name: String) {
        val recording = state.value.recording
        val draft = recording.pending ?: return
        if (name.trim().isEmpty() || recording.busy) return
        mutableState.update { it.copy(recording = recording.copy(busy = true, message = null)) }
        viewModelScope.launch {
            try {
                val all = storageMutex.withLock {
                    withContext(Dispatchers.IO) {
                        store.put(draft.copy(name = name.trim(), isDraft = false, updatedAt = System.currentTimeMillis()))
                        store.all()
                    }
                }
                mutableState.update { it.copy(
                    trajectories = all.filterNot { trajectory -> trajectory.isDraft },
                    recording = RecordingUiState()
                ) }
            } catch (error: Exception) {
                mutableState.update { it.copy(recording = it.recording.copy(
                    busy = false, message = text(R.string.storage_failed)
                )) }
            }
        }
    }

    override fun discardRecording() {
        val recording = state.value.recording
        val draft = recording.pending ?: return
        if (recording.busy) return
        mutableState.update { it.copy(recording = recording.copy(busy = true)) }
        viewModelScope.launch {
            try {
                storageMutex.withLock { withContext(Dispatchers.IO) { store.delete(draft.id) } }
                mutableState.update { it.copy(recording = RecordingUiState()) }
            } catch (error: Exception) {
                mutableState.update { it.copy(recording = it.recording.copy(
                    busy = false, message = text(R.string.storage_failed)
                )) }
            }
        }
    }

    override fun openTrajectory(id: String) {
        if (state.value.editor?.edited?.id == id) return
        val trajectory = state.value.trajectories.firstOrNull { it.id == id } ?: return
        previewJob?.cancel()
        mutableState.update { it.copy(editor = TrajectoryEditorState(trajectory)) }
    }

    override fun renameEditedTrajectory(name: String) {
        mutableState.update {
            val editor = it.editor ?: return@update it
            if (editor.saving) it else it.copy(editor = editor.copy(edited = editor.edited.copy(name = name)))
        }
    }

    override fun selectEditedPoint(id: String) {
        mutableState.update { it.copy(editor = it.editor?.copy(selectedPointId = id)) }
    }

    override fun moveEditedPoint(delta: Int) {
        val editor = state.value.editor ?: return
        if (editor.saving || editor.edited.kind != TrajectoryKind.Waypoints) return
        val index = editor.edited.points.indexOfFirst { it.id == editor.selectedPointId }
        val target = index + delta
        if (index < 0 || target !in editor.edited.points.indices || delta !in listOf(-1, 1)) return
        val points = editor.edited.points.toMutableList()
        val point = points.removeAt(index)
        points.add(target, point)
        previewJob?.cancel()
        mutableState.update { it.copy(editor = editor.copy(
            edited = editor.edited.copy(points = points), playing = false, elapsedMs = 0
        )) }
    }

    override fun deleteEditedPoint() {
        val editor = state.value.editor ?: return
        if (editor.saving || editor.edited.kind != TrajectoryKind.Waypoints) return
        val index = editor.edited.points.indexOfFirst { it.id == editor.selectedPointId }
        if (index < 0) return
        val points = editor.edited.points.filterNot { it.id == editor.selectedPointId }
        previewJob?.cancel()
        mutableState.update { it.copy(editor = editor.copy(
            edited = editor.edited.copy(points = points),
            selectedPointId = points.getOrNull(index.coerceAtMost(points.lastIndex))?.id,
            playing = false, elapsedMs = 0
        )) }
    }

    override fun saveEditedTrajectory(closeAfter: Boolean) {
        val editor = state.value.editor ?: return
        if (editor.saving || editor.edited.name.trim().isEmpty() || editor.edited.points.isEmpty()) return
        previewJob?.cancel()
        mutableState.update { it.copy(editor = editor.copy(saving = true, playing = false, message = null)) }
        viewModelScope.launch {
            try {
                val saved = editor.edited.copy(name = editor.edited.name.trim(), updatedAt = System.currentTimeMillis())
                val all = storageMutex.withLock { withContext(Dispatchers.IO) {
                    store.put(saved)
                    store.all()
                } }
                mutableState.update { it.copy(
                    trajectories = all.filterNot { trajectory -> trajectory.isDraft },
                    editor = if (closeAfter) null else TrajectoryEditorState(
                        saved, selectedPointId = editor.selectedPointId
                    )
                ) }
            } catch (error: Exception) {
                mutableState.update { it.copy(editor = it.editor?.copy(
                    saving = false, message = text(R.string.storage_failed)
                )) }
            }
        }
    }

    override fun closeEditor() {
        previewJob?.cancel()
        mutableState.update { it.copy(editor = null) }
    }

    override fun deleteTrajectory(id: String) {
        val editor = state.value.editor ?: return
        if (editor.saving || editor.edited.id != id) return
        previewJob?.cancel()
        mutableState.update { it.copy(editor = editor.copy(saving = true, playing = false)) }
        viewModelScope.launch {
            try {
                val all = storageMutex.withLock { withContext(Dispatchers.IO) {
                    store.delete(id)
                    store.all()
                } }
                mutableState.update { it.copy(
                    trajectories = all.filterNot { trajectory -> trajectory.isDraft }, editor = null
                ) }
            } catch (error: Exception) {
                mutableState.update { it.copy(editor = it.editor?.copy(
                    saving = false, message = text(R.string.storage_failed)
                )) }
            }
        }
    }

    override fun togglePreview() {
        val editor = state.value.editor ?: return
        if (editor.saving) return
        previewJob?.cancel()
        if (editor.playing) {
            mutableState.update { it.copy(editor = editor.copy(playing = false)) }
            return
        }
        val timeline = PreviewTimeline(editor.edited)
        if (timeline.durationMs <= 0) return
        val initial = if (editor.elapsedMs >= timeline.durationMs) 0 else editor.elapsedMs
        mutableState.update { it.copy(editor = editor.copy(playing = true, elapsedMs = initial)) }
        previewJob = viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            while (true) {
                val elapsed = (initial + SystemClock.elapsedRealtime() - started).coerceAtMost(timeline.durationMs)
                mutableState.update { it.copy(editor = it.editor?.copy(
                    elapsedMs = elapsed, playing = elapsed < timeline.durationMs
                )) }
                if (elapsed >= timeline.durationMs) break
                delay(16)
            }
        }
    }

    override fun resetPreview() {
        previewJob?.cancel()
        mutableState.update { it.copy(editor = it.editor?.copy(playing = false, elapsedMs = 0)) }
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
                    if (status.mode == 0 && endingTeachId == null &&
                        state.value.recording.stage == RecordingStage.Recording
                    ) finalizeRecording(interrupted = true)
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
        finalizeRecording(interrupted = true)
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
        store.close()
        super.onCleared()
    }
}
