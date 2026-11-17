package com.example.dollybot

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.example.dollybot.connection.CarLink
import com.example.dollybot.connection.DollyBotViewModel
import com.example.dollybot.connection.Packet
import com.example.dollybot.connection.Wire
import com.example.dollybot.trajectory.TrajectoryStore
import com.example.dollybot.ui.ConnectionStatus
import com.example.dollybot.ui.RecordingStage
import com.example.dollybot.ui.RecordingMode
import com.example.dollybot.ui.TrajectoryKind
import com.example.dollybot.ui.DriveDirection
import com.example.dollybot.ui.GimbalAxis
import java.io.IOException
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real request/telemetry/finalization/storage code using the existing V1 packet types. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 31)
class RecordingSessionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val name = "test-session-${UUID.randomUUID()}.db"
    private val owner = object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    private lateinit var store: TrajectoryStore
    private lateinit var model: DollyBotViewModel
    private lateinit var car: PacketCar

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 5000
        while (!condition() && SystemClock.elapsedRealtime() < until) SystemClock.sleep(10)
        assertTrue("Timed out waiting for recording state: ${model.state.value.recording}", condition())
    }

    @Before
    fun connect() {
        instrumentation.uiAutomation.grantRuntimePermission(application.packageName, Manifest.permission.BLUETOOTH_CONNECT)
        main {
            store = TrajectoryStore(application, name)
            model = ViewModelProvider(owner, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    DollyBotViewModel(application, store) { _, receive, disconnected ->
                        PacketCar(receive, disconnected).also { car = it }
                    } as T
            })[DollyBotViewModel::class.java]
        }
        await { !model.state.value.libraryLoading }
        main { model.selectDevice("00:11:22:33:44:55"); model.connect() }
        await { model.state.value.device.connection == ConnectionStatus.Connected && model.state.value.device.calibrated }
    }

    @After
    fun cleanup() {
        main { owner.viewModelStore.clear() }
        application.deleteDatabase(name)
    }

    private fun begin(kind: RecordingMode) {
        main { model.selectRecordingMode(kind); model.startRecording(kind) }
        await { model.state.value.recording.stage == RecordingStage.Recording && !model.state.value.teaching.busy }
    }

    @Test
    fun explicitPointSessionEndsAfterStopAndPersistsCompleteSnapshots() {
        main { model.captureWaypoint() }
        await { !model.state.value.teaching.busy }
        assertFalse(car.sent.any { it.type == Wire.TAKE_POINT || it.type == Wire.TEACH_BEGIN })
        begin(RecordingMode.Waypoints)
        repeat(2) {
            main { model.captureWaypoint() }
            await { model.state.value.teaching.waypoints.poses.size == it + 1 && !model.state.value.teaching.busy }
        }
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        val pending = model.state.value.recording.pending!!
        assertFalse(pending.incomplete)
        assertEquals(listOf(100, 200), pending.points.map { it.pose.xMm })
        assertEquals(30f, pending.points.first().pose.gimbalYaw, 0f)
        assertEquals(-15f, pending.points.first().pose.gimbalPitch, 0f)
        assertTrue(car.sent.indexOfFirst { it.type == Wire.STOP } < car.sent.indexOfFirst { it.type == Wire.TEACH_END })
        assertTrue(store.all().single().isDraft)
        main { model.dismissRecordingSaveDialog(); model.selectRecordingMode(RecordingMode.Continuous) }
        assertEquals(RecordingStage.PendingSave, model.state.value.recording.stage)
        main { model.saveRecording("  成果  ") }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertEquals(pending.points, store.all().single().points)
        assertEquals("成果", store.all().single().name)
        assertFalse(store.all().single().isDraft)
    }

    @Test
    fun finalContinuousSamplePrecedesSaveAndCountMismatchIsMarkedIncomplete() {
        begin(RecordingMode.Continuous)
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertEquals(2, model.state.value.recording.pending!!.points.size)
        assertFalse(model.state.value.recording.pending!!.incomplete)
        main { model.discardRecording() }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertTrue(store.all().isEmpty())
        main { car.omitFinalSample = true }
        begin(RecordingMode.Continuous)
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertEquals(1, model.state.value.recording.pending!!.points.size)
        assertTrue(model.state.value.recording.pending!!.incomplete)
    }

    @Test
    fun disconnectWhileBeginAckIsPendingPreservesNewSamplesWithoutReplacingTheDraft() {
        main { car.holdBeginAck = true; model.selectRecordingMode(RecordingMode.Continuous); model.startRecording(RecordingMode.Continuous) }
        await { model.state.value.teaching.continuous.poses.size == 1 }
        main { car.fail() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertTrue(model.state.value.recording.pending!!.incomplete)
        assertEquals(1, store.all().single().points.size)
        assertEquals(RecordingStage.PendingSave, model.state.value.recording.stage)
    }

    @Test
    fun stoppingAnEmptyPointSessionReturnsToIdleWithoutCreatingADraft() {
        begin(RecordingMode.Waypoints)
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertEquals(application.getString(R.string.no_recorded_data), model.state.value.recording.message)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun pushAutoStartsAfterParkingWaitsForAckAndSavesBothPosesAsContinuous() {
        main { car.holdBeginAck = true; model.selectRecordingMode(RecordingMode.Push) }
        await { car.beginRequest != null }
        assertEquals(RecordingStage.Starting, model.state.value.recording.stage)
        assertTrue(model.state.value.teaching.continuous.poses.isEmpty())
        val beginIndex = car.sent.indexOfFirst { it.type == Wire.TEACH_BEGIN }
        assertTrue(car.sent.indexOfFirst { it.type == Wire.STOP } < beginIndex)
        assertEquals(3, car.sent[beginIndex].payload[0].toInt())
        main { car.releaseBeginAck() }
        await { model.state.value.recording.stage == RecordingStage.Recording && !model.state.value.teaching.busy }
        assertEquals(1, model.state.value.teaching.continuous.poses.size)
        main {
            model.drive(DriveDirection.Forward)
            model.adjustGimbal(GimbalAxis.Yaw, 45f)
        }
        await { car.sent.any { it.type == Wire.GIMBAL } }
        assertFalse(car.sent.drop(beginIndex).any { it.type == Wire.MOTION })
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        val pending = model.state.value.recording.pending!!
        assertEquals(TrajectoryKind.Continuous, pending.kind)
        assertEquals(listOf(100, 200), pending.points.map { it.pose.xMm })
        assertEquals(45f, pending.points.last().pose.gimbalYaw, 0f)
        assertEquals(-15f, pending.points.last().pose.gimbalPitch, 0f)
        assertFalse(pending.incomplete)
        assertTrue(store.all().single().isDraft)
        main { model.saveRecording("推车测试") }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertEquals(pending.points, store.all().single().points)
        assertEquals(TrajectoryKind.Continuous, model.state.value.trajectories.single().kind)
    }

    @Test
    fun pushRejectsOtherRecordingsAndDuplicatesAndMarksMissingSamplesIncomplete() {
        main { model.selectRecordingMode(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Recording }
        main {
            car.emit(1)
            car.emit(2, teachId = 999)
            car.emit(3)
        }
        await { model.state.value.teaching.continuous.poses.size == 2 }
        assertEquals(listOf(100, 300), model.state.value.teaching.continuous.poses.map { it.xMm })
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertTrue(model.state.value.recording.pending!!.incomplete)
    }

    @Test
    fun pushFrameChangeAndDisconnectPreserveOnlyValidSamples() {
        main { model.selectRecordingMode(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Recording }
        main { car.emit(2, frame = 2) }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertEquals(1, model.state.value.recording.pending!!.points.size)
        assertTrue(model.state.value.recording.pending!!.incomplete)
        main { model.discardRecording() }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        main { model.selectRecordingMode(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Recording }
        main { car.fail() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertEquals(1, store.all().single().points.size)
        assertTrue(store.all().single().incomplete)
    }

    @Test
    fun rejectedPushCanRetryAndUnconfirmedDisconnectCreatesNoDraft() {
        main { car.beginResult = 5; model.selectRecordingMode(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Ready && !model.state.value.teaching.busy }
        assertFalse(model.state.value.recording.blocksDriving)
        assertTrue(store.all().isEmpty())
        main { car.beginResult = 0; car.holdBeginAck = true; model.startRecording(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Starting && car.beginRequest != null }
        main { car.fail() }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun pushBeginTimeoutDisconnectsWithoutPublishingUnconfirmedSamples() {
        main { car.holdBeginAck = true; model.selectRecordingMode(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Starting }
        await { model.state.value.device.connection == ConnectionStatus.Error }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun pushBackgroundSavesAnIncompleteDraftAndDisconnects() {
        main { model.selectRecordingMode(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Recording && !model.state.value.teaching.busy }
        main { model.onBackground() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave &&
            model.state.value.device.connection == ConnectionStatus.Disconnected }
        assertTrue(store.all().single().incomplete)
        assertEquals(2, store.all().single().points.size)
    }

    @Test
    fun stoppedPushDraftRecoversAfterRestartAndCanBeNamedAndSaved() {
        main { model.selectRecordingMode(RecordingMode.Push) }
        await { model.state.value.recording.stage == RecordingStage.Recording && !model.state.value.teaching.busy }
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        val draft = model.state.value.recording.pending!!
        main {
            owner.viewModelStore.clear()
            store = TrajectoryStore(application, name)
            model = DollyBotViewModel(application, store)
            owner.viewModelStore.put("recovered", model)
        }
        await { !model.state.value.libraryLoading && model.state.value.recording.stage == RecordingStage.PendingSave }
        assertEquals(draft, model.state.value.recording.pending)
        assertTrue(model.state.value.recording.showSaveDialog)
        main { model.saveRecording("恢复的推车轨迹") }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertEquals(draft.points, store.all().single().points)
        assertEquals(TrajectoryKind.Continuous, store.all().single().kind)
    }

    internal class PacketCar(
        val receive: (Packet) -> Unit,
        val disconnected: (Throwable) -> Unit
    ) : CarLink {
        val sent = mutableListOf<Packet>()
        var omitFinalSample = false
        var holdBeginAck = false
        var beginResult = 0
        var beginRequest: Packet? = null
        private var session = 0L
        private var mode = 0
        private var index = 0
        private var teach = 0
        private var time = 0
        private var gimbalYaw = 3000
        private var gimbalPitch = -1500
        override suspend fun connect(device: BluetoothDevice) = Unit
        override fun close() = Unit
        fun fail() = disconnected(IOException("test disconnect"))
        private fun ack(request: Packet, value: Int = 0, result: Int = 0) = receive(Packet(
            Wire.ACK, request.seq, session, Wire.buffer(6).put(request.type.toByte()).put(result.toByte()).putInt(value).array()
        ))
        fun releaseBeginAck() = ack(beginRequest!!, teach)
        fun emit(sampleIndex: Int, teachId: Int = teach, frame: Int = 1) {
            time += 50
            index = sampleIndex
            receive(Packet(Wire.STATUS, time, session, Wire.buffer(34).put(pose(frame)).put(mode.toByte())
                .put(1).putInt(teachId).putInt(index).putShort(0).array()))
        }
        private fun pose(frame: Int = 1) = Wire.buffer(22).putInt(time).putInt(frame).putInt(index * 100).putInt(200)
            .putShort(9000).putShort(gimbalYaw.toShort()).putShort(gimbalPitch.toShort()).array()
        private fun status() {
            time += 50
            receive(Packet(Wire.STATUS, time, session, Wire.buffer(34).put(pose()).put(mode.toByte())
                .put(1).putInt(teach).putInt(index).putShort(0).array()))
        }
        override fun send(packet: Packet) {
            sent += packet
            session = packet.session
            when (packet.type) {
                Wire.HELLO -> { ack(packet); status() }
                Wire.PING -> { ack(packet); status() }
                Wire.STOP -> ack(packet)
                Wire.TEACH_BEGIN -> {
                    if (beginResult != 0) { ack(packet, result = beginResult); return }
                    beginRequest = packet
                    mode = packet.payload[0].toInt(); teach++; index = if (mode == 1 || mode == 3) 1 else 0
                    status()
                    if (!holdBeginAck) ack(packet, teach)
                }
                Wire.TAKE_POINT -> {
                    index++; time += 50
                    receive(Packet(Wire.POINT, packet.seq, session,
                        Wire.buffer(30).putInt(teach).putInt(index).put(pose()).array()))
                }
                Wire.TEACH_END -> {
                    if (mode == 1 || mode == 3) { index++; if (!omitFinalSample) status() }
                    ack(packet, index); mode = 0; status()
                }
                Wire.GIMBAL -> {
                    val angles = Wire.read(packet.payload)
                    gimbalYaw = angles.short.toInt(); gimbalPitch = angles.short.toInt()
                }
            }
        }
    }
}
