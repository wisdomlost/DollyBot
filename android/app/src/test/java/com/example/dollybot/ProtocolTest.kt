package com.example.dollybot

import com.example.dollybot.connection.CarStatus
import com.example.dollybot.connection.LivePose
import com.example.dollybot.connection.Packet
import com.example.dollybot.connection.PacketDecoder
import com.example.dollybot.connection.TeachingPoint
import com.example.dollybot.connection.Wire
import com.example.dollybot.connection.crc16
import com.example.dollybot.connection.uploadTrajectory
import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.StoredPose
import com.example.dollybot.ui.TrajectoryKind
import java.io.IOException
import kotlinx.coroutines.runBlocking
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
    fun pushVectorsMatchPythonAndKeepFullPoseFields() {
        val begin = Packet(Wire.TEACH_BEGIN, 6, 0x12345678, Wire.buffer(5).put(3).putInt(1).array())
        assertArrayEquals(bytes("a55a01300600050078563412030100000000e5"), begin.encode())
        val status = CarStatus.decode(PacketDecoder().feed(bytes(
            "a55a01906400220078563412dc05000001000000780000002c0100002823b80b24fa03010700000004000000040026c5"
        )).single().payload)
        assertEquals(3, status.mode)
        assertEquals(7L, status.teachId)
        assertEquals(4L, status.pointIndex)
        assertEquals(1500L, status.pose.timeMs)
        assertEquals(1L, status.pose.frameId)
        assertEquals(120, status.pose.xMm)
        assertEquals(300, status.pose.yMm)
        assertEquals(90f, status.pose.yawDegrees, 0f)
        assertEquals(30f, status.pose.gimbalYaw, 0f)
        assertEquals(-15f, status.pose.gimbalPitch, 0f)
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

    @Test
    fun trajectoryUploadPreservesLongUtf8NameAndAllPoseFieldsAcrossPackets() = runBlocking {
        val pose = LivePose(0xFFFFFFFFL, 0xFEDCBA98L, -120, 300, 90f, 30f, -15f)
        val points = List(12) { StoredPose("point-$it", pose.copy(xMm = pose.xMm + it, timeMs = (pose.timeMs + it * 50) and 0xFFFFFFFFL)) }
        val trajectory = SavedTrajectory("route", "A" + "轨".repeat(86), TrajectoryKind.Continuous,
            0, 0, points, repeats = 3)
        val frames = mutableListOf<Packet>()
        val progress = mutableListOf<Int>()

        uploadTrajectory(trajectory, send = { type, data ->
            val packet = Packet(type, frames.size + 1, 123, data)
            frames += PacketDecoder().feed(packet.encode()).single()
            if (type == Wire.TRAJECTORY_COMMIT) 12L else 0L
        }, onProgress = { sent, total -> assertEquals(12, total); progress.add(sent) })

        assertEquals(listOf(0x40, 0x41, 0x41, 0x41, 0x41, 0x42), frames.map { it.type })
        assertArrayEquals(bytes("01 00 03 01 00 00 0C 00 00 00 03"), frames.first().payload)
        assertEquals(listOf(0, 252, 259, 501), frames.subList(1, 5).map { Wire.read(it.payload).int })
        val name = frames[1].payload.drop(4) + frames[2].payload.drop(4)
        assertEquals(trajectory.name, name.toByteArray().toString(Charsets.UTF_8))
        assertArrayEquals(
            bytes("00 00 00 00 98 BA DC FE 88 FF FF FF 2C 01 00 00 28 23 B8 0B 24 FA"),
            frames[3].payload.copyOfRange(4, 26)
        )
        assertEquals(-109, Wire.read(frames[4].payload).getInt(12))
        assertEquals(listOf(11, 12), progress)
    }

    @Test
    fun trajectoryUploadRejectsMissingOrWrongReceiverCount() {
        val pose = LivePose(0, 1, 0, 0, 0f, 0f, 0f)
        val saved = SavedTrajectory("route", "一号", TrajectoryKind.Continuous, 0, 0,
            listOf(StoredPose("point", pose)))

        assertThrows(IOException::class.java) {
            runBlocking { uploadTrajectory(saved, send = { _, _ -> 0L }, onProgress = { _, _ -> }) }
        }
    }
}
