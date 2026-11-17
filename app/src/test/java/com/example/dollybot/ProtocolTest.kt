package com.example.dollybot

import com.example.dollybot.connection.CarStatus
import com.example.dollybot.connection.Packet
import com.example.dollybot.connection.PacketDecoder
import com.example.dollybot.connection.TeachingPoint
import com.example.dollybot.connection.Wire
import com.example.dollybot.connection.crc16
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun bytes(hex: String) = hex.filterNot(Char::isWhitespace).chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun approvedVectorsAndCrcMatchPython() {
        assertEquals(0x29B1, crc16("123456789".toByteArray()))
        val motion = Packet(Wire.MOTION, 4, 0x12345678, Wire.buffer(6)
            .putShort(500).putShort(300).putShort(0).array())
        assertArrayEquals(
            bytes("A5 5A 01 10 04 00 06 00 78 56 34 12 F4 01 2C 01 00 00 73 D5"),
            motion.encode()
        )
        val gimbal = Packet(Wire.GIMBAL, 5, 0x12345678, Wire.buffer(4)
            .putShort(3000).putShort(-1500).array())
        assertArrayEquals(bytes("A5 5A 01 11 05 00 04 00 78 56 34 12 B8 0B 24 FA 4C AE"), gimbal.encode())
    }

    @Test
    fun decoderHandlesFragmentsNoiseCorruptionAndConsecutiveFrames() {
        val ping = Packet(Wire.PING, 1, 123).encode()
        val stop = Packet(Wire.STOP, 2, 123).encode()
        val corrupt = ping.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val decoder = PacketDecoder()
        val result = ("garbage".toByteArray() + corrupt + ping + stop)
            .flatMap { decoder.feed(byteArrayOf(it)) }
        assertEquals(listOf(Wire.PING, Wire.STOP), result.map { it.type })
        assertEquals(listOf(1, 2), result.map { it.seq })
        assertEquals(listOf(123L, 123L), result.map { it.session })
    }

    @Test
    fun statusAndPointUseTheApprovedUnitsAndFieldOrder() {
        val packet = bytes("""
            A5 5A 01 90 64 00 22 00 78 56 34 12
            DC 05 00 00 01 00 00 00 78 00 00 00
            2C 01 00 00 00 00 B8 0B 24 FA 01 01
            07 00 00 00 04 00 00 00 04 00 58 CA
        """)
        val status = CarStatus.decode(PacketDecoder().feed(packet).single().payload)
        assertEquals(1500L, status.pose.timeMs)
        assertEquals(1L, status.pose.frameId)
        assertEquals(120, status.pose.xMm)
        assertEquals(300, status.pose.yMm)
        assertEquals(30f, status.pose.gimbalYaw, 0f)
        assertEquals(-15f, status.pose.gimbalPitch, 0f)
        assertEquals(7L, status.teachId)
        assertEquals(4L, status.pointIndex)
        val point = TeachingPoint.decode(Wire.buffer(30)
            .putInt(8).putInt(1).putInt(1500).putInt(1).putInt(120).putInt(300)
            .putShort(0).putShort(3000).putShort(-1500).array())
        assertEquals(status.pose, point.pose)
    }

    @Test
    fun illegalLengthResynchronizesAndUnknownVersionRemainsInspectable() {
        val invalid = bytes("A5 5A 01 01 01 00 FF FF 78 56 34 12")
        val frame = Packet(Wire.HELLO, 1, 1, version = 2).encode()
        val result = PacketDecoder().feed(invalid + frame).single()
        assertEquals(2, result.version)
    }
}
