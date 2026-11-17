package com.example.dollybot.connection

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object Wire {
    const val HELLO = 0x01
    const val PING = 0x02
    const val MOTION = 0x10
    const val GIMBAL = 0x11
    const val STOP = 0x12
    const val CALIBRATE = 0x20
    const val TEACH_BEGIN = 0x30
    const val TEACH_END = 0x31
    const val TAKE_POINT = 0x32
    const val ACK = 0x80
    const val STATUS = 0x90
    const val POINT = 0x91

    fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    fun read(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    fun uint(value: Int): Long = value.toLong() and 0xFFFFFFFFL
}

internal data class Packet(
    val type: Int,
    val seq: Int,
    val session: Long,
    val payload: ByteArray = byteArrayOf(),
    val version: Int = 1
) {
    fun encode(): ByteArray {
        require(payload.size <= 256)
        val result = Wire.buffer(14 + payload.size)
        result.put(0xA5.toByte()).put(0x5A).put(version.toByte()).put(type.toByte())
        result.putShort(seq.toShort()).putShort(payload.size.toShort()).putInt(session.toInt())
        result.put(payload)
        result.putShort(crc16(result.array(), 2, 12 + payload.size).toShort())
        return result.array()
    }
}

internal fun crc16(bytes: ByteArray, start: Int = 0, end: Int = bytes.size): Int {
    var crc = 0xFFFF
    for (index in start until end) {
        crc = crc xor ((bytes[index].toInt() and 0xFF) shl 8)
        repeat(8) {
            crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
            crc = crc and 0xFFFF
        }
    }
    return crc
}

internal class PacketDecoder {
    private var buffer = byteArrayOf()

    fun feed(bytes: ByteArray): List<Packet> {
        buffer += bytes
        val packets = mutableListOf<Packet>()
        while (buffer.size >= 2) {
            val start = (0 until buffer.lastIndex).firstOrNull {
                buffer[it] == 0xA5.toByte() && buffer[it + 1] == 0x5A.toByte()
            }
            if (start == null) {
                buffer = if (buffer.last() == 0xA5.toByte()) byteArrayOf(buffer.last()) else byteArrayOf()
                break
            }
            if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
            if (buffer.size < 12) break
            val header = Wire.read(buffer)
            val length = header.getShort(6).toInt() and 0xFFFF
            if (length > 256) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            val total = 14 + length
            if (buffer.size < total) break
            val expected = header.getShort(total - 2).toInt() and 0xFFFF
            if (crc16(buffer, 2, total - 2) != expected) {
                buffer = buffer.copyOfRange(1, buffer.size)
                continue
            }
            packets += Packet(
                type = buffer[3].toInt() and 0xFF,
                seq = header.getShort(4).toInt() and 0xFFFF,
                session = Wire.uint(header.getInt(8)),
                payload = buffer.copyOfRange(12, total - 2),
                version = buffer[2].toInt() and 0xFF
            )
            buffer = buffer.copyOfRange(total, buffer.size)
        }
        return packets
    }
}

internal data class LivePose(
    val timeMs: Long,
    val frameId: Long,
    val xMm: Int,
    val yMm: Int,
    val yawDegrees: Float,
    val gimbalYaw: Float,
    val gimbalPitch: Float
)

internal data class CarStatus(
    val pose: LivePose,
    val mode: Int,
    val flags: Int,
    val teachId: Long,
    val pointIndex: Long,
    val lastMotionSeq: Int
) {
    companion object {
        fun decode(data: ByteArray): CarStatus {
            require(data.size == 34)
            val input = Wire.read(data)
            val pose = readPose(input)
            return CarStatus(
                pose,
                input.get().toInt() and 0xFF,
                input.get().toInt() and 0xFF,
                Wire.uint(input.int), Wire.uint(input.int),
                input.short.toInt() and 0xFFFF
            )
        }
    }
}

internal data class TeachingPoint(val teachId: Long, val index: Long, val pose: LivePose) {
    companion object {
        fun decode(data: ByteArray): TeachingPoint {
            require(data.size == 30)
            val input = Wire.read(data)
            val id = Wire.uint(input.int)
            val index = Wire.uint(input.int)
            return TeachingPoint(id, index, readPose(input))
        }
    }
}

private fun readPose(input: ByteBuffer) = LivePose(
    Wire.uint(input.int), Wire.uint(input.int), input.int, input.int,
    input.short / 100f, input.short / 100f, input.short / 100f
)
