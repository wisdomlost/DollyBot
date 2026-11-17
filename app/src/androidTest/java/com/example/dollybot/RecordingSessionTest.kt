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
import com.example.dollybot.ui.TrajectoryKind
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

    private fun begin(kind: TrajectoryKind) {
        main { model.selectRecordingMode(kind); model.startRecording(kind) }
        await { model.state.value.recording.stage == RecordingStage.Recording && !model.state.value.teaching.busy }
    }

    @Test
    fun explicitPointSessionEndsAfterStopAndPersistsCompleteSnapshots() {
        main { model.captureWaypoint() }
        await { !model.state.value.teaching.busy }
        assertFalse(car.sent.any { it.type == Wire.TAKE_POINT || it.type == Wire.TEACH_BEGIN })
        begin(TrajectoryKind.Waypoints)
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
        main { model.dismissRecordingSaveDialog(); model.selectRecordingMode(TrajectoryKind.Continuous) }
        assertEquals(RecordingStage.PendingSave, model.state.value.recording.stage)
        main { model.saveRecording("  成果  ") }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertEquals(pending.points, store.all().single().points)
        assertEquals("成果", store.all().single().name)
        assertFalse(store.all().single().isDraft)
    }

    @Test
    fun finalContinuousSamplePrecedesSaveAndCountMismatchIsMarkedIncomplete() {
        begin(TrajectoryKind.Continuous)
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertEquals(2, model.state.value.recording.pending!!.points.size)
        assertFalse(model.state.value.recording.pending!!.incomplete)
        main { model.discardRecording() }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertTrue(store.all().isEmpty())
        main { car.omitFinalSample = true }
        begin(TrajectoryKind.Continuous)
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertEquals(1, model.state.value.recording.pending!!.points.size)
        assertTrue(model.state.value.recording.pending!!.incomplete)
    }

    @Test
    fun disconnectWhileBeginAckIsPendingPreservesNewSamplesWithoutReplacingTheDraft() {
        main { car.holdBeginAck = true; model.selectRecordingMode(TrajectoryKind.Continuous); model.startRecording(TrajectoryKind.Continuous) }
        await { model.state.value.teaching.continuous.poses.size == 1 }
        main { car.fail() }
        await { model.state.value.recording.stage == RecordingStage.PendingSave }
        assertTrue(model.state.value.recording.pending!!.incomplete)
        assertEquals(1, store.all().single().points.size)
        assertEquals(RecordingStage.PendingSave, model.state.value.recording.stage)
    }

    @Test
    fun stoppingAnEmptyPointSessionReturnsToIdleWithoutCreatingADraft() {
        begin(TrajectoryKind.Waypoints)
        main { model.finishRecording() }
        await { model.state.value.recording.stage == RecordingStage.Idle }
        assertEquals(application.getString(R.string.no_recorded_data), model.state.value.recording.message)
        assertTrue(store.all().isEmpty())
    }

    private class PacketCar(
        val receive: (Packet) -> Unit,
        val disconnected: (Throwable) -> Unit
    ) : CarLink {
        val sent = mutableListOf<Packet>()
        var omitFinalSample = false
        var holdBeginAck = false
        private var session = 0L
        private var mode = 0
        private var index = 0
        private var teach = 0
        private var time = 0
        override suspend fun connect(device: BluetoothDevice) = Unit
        override fun close() = Unit
        fun fail() = disconnected(IOException("test disconnect"))
        private fun ack(request: Packet, value: Int = 0) = receive(Packet(
            Wire.ACK, request.seq, session, Wire.buffer(6).put(request.type.toByte()).put(0).putInt(value).array()
        ))
        private fun pose() = Wire.buffer(22).putInt(time).putInt(1).putInt(index * 100).putInt(200)
            .putShort(9000).putShort(3000).putShort(-1500).array()
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
                Wire.PING, Wire.STOP -> ack(packet)
                Wire.TEACH_BEGIN -> {
                    mode = packet.payload[0].toInt(); teach++; index = if (mode == 1) 1 else 0
                    status()
                    if (!holdBeginAck) ack(packet, teach)
                }
                Wire.TAKE_POINT -> {
                    index++; time += 50
                    receive(Packet(Wire.POINT, packet.seq, session,
                        Wire.buffer(30).putInt(teach).putInt(index).put(pose()).array()))
                }
                Wire.TEACH_END -> {
                    if (mode == 1) { index++; if (!omitFinalSample) status() }
                    ack(packet, index); mode = 0; status()
                }
            }
        }
    }
}
