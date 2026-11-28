package com.example.dollybot.connection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/** One reader and one frame writer per RFCOMM connection. */
internal interface CarLink {
    suspend fun connect(device: BluetoothDevice)
    fun send(packet: Packet)
    fun discardControlPackets() = Unit
    fun close()
}

internal class BluetoothLink(
    private val scope: CoroutineScope,
    private val receive: (Packet) -> Unit,
    private val disconnected: (Throwable) -> Unit
) : CarLink {
    private data class QueuedControl(val packet: Packet, val generation: Int)

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var closed = false
    @Volatile private var controlGeneration = 0
    private val urgent = Channel<Packet>(Channel.UNLIMITED)
    private val commands = Channel<Packet>(Channel.UNLIMITED)
    private val motion = Channel<QueuedControl>(Channel.CONFLATED)
    private val gimbal = Channel<QueuedControl>(Channel.CONFLATED)
    private var reader: Job? = null
    private var writer: Job? = null

    @SuppressLint("MissingPermission")
    override suspend fun connect(device: BluetoothDevice) = withContext(Dispatchers.IO) {
        val connection = device.createRfcommSocketToServiceRecord(
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        )
        socket = connection
        if (closed) {
            connection.close()
            throw IOException("Connection cancelled")
        }
        connection.connect()
        if (closed) throw IOException("Connection cancelled")
        reader = scope.launch(Dispatchers.IO) {
            try {
                val decoder = PacketDecoder()
                val bytes = ByteArray(1024)
                val input = connection.inputStream
                while (!closed) {
                    val length = input.read(bytes)
                    if (length < 0) throw IOException("Bluetooth connection ended")
                    decoder.feed(bytes.copyOf(length)).forEach(receive)
                }
            } catch (error: IOException) {
                if (!closed) disconnected(error)
            }
        }
        writer = scope.launch(Dispatchers.IO) {
            try {
                val output = connection.outputStream
                while (!closed) {
                    val packet = urgent.tryReceive().getOrNull() ?: select<Packet?> {
                        urgent.onReceive { it }
                        commands.onReceive { it }
                        motion.onReceive { it.currentPacket() }
                        gimbal.onReceive { it.currentPacket() }
                    }
                    if (packet == null) continue
                    output.write(packet.encode())
                    output.flush()
                }
            } catch (error: IOException) {
                if (!closed) disconnected(error)
            }
        }
    }

    override fun send(packet: Packet) {
        if (closed) return
        when (packet.type) {
            Wire.STOP -> {
                motion.tryReceive()
                urgent.trySend(packet)
            }
            Wire.MOTION, Wire.LEASE -> motion.trySend(QueuedControl(packet, controlGeneration))
            Wire.GIMBAL -> gimbal.trySend(QueuedControl(packet, controlGeneration))
            else -> commands.trySend(packet)
        }
    }

    private fun QueuedControl.currentPacket(): Packet? =
        packet.takeIf { generation == controlGeneration }

    override fun discardControlPackets() {
        // The writer may have selected a control packet before processing the page exit.
        controlGeneration++
        motion.tryReceive()
        gimbal.tryReceive()
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
        reader?.cancel()
        writer?.cancel()
        urgent.close()
        commands.close()
        motion.close()
        gimbal.close()
    }
}
