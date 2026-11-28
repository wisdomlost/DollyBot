package com.example.dollybot.connection

import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.PreviewTimeline
import com.example.dollybot.trajectory.executionSamples
import java.io.IOException
import kotlin.math.roundToInt

private const val POSE_BYTES = 22
private const val OFFSET_BYTES = 4
private const val DATA_BYTES = Wire.MAX_PAYLOAD - OFFSET_BYTES

internal suspend fun uploadTrajectory(
    trajectory: SavedTrajectory,
    send: suspend (type: Int, data: ByteArray) -> Long,
    onProgress: (Int, Int) -> Unit
) {
    require(!trajectory.isDraft && trajectory.points.isNotEmpty())
    val name = trajectory.name.toByteArray(Charsets.UTF_8)
    require(!trajectory.incomplete) { "录制有缺帧，请重新示教" }
    val poses = PreviewTimeline(trajectory).executionSamples()
    val count = poses.size
    require(name.size in 1..512 && count in 1..4096)
    val begin = Wire.buffer(11)
        .put(1.toByte()) // Timestamped execution samples for both editing modes.
        .put(if (trajectory.incomplete) 1 else 0)
        .putInt(name.size).putInt(count).put(trajectory.repeats.toByte())
    send(Wire.TRAJECTORY_BEGIN, begin.array())

    var offset = 0L
    for (start in name.indices step DATA_BYTES) {
        val length = minOf(DATA_BYTES, name.size - start)
        val chunk = Wire.buffer(OFFSET_BYTES + length).putInt(offset.toInt()).put(name, start, length)
        send(Wire.TRAJECTORY_DATA, chunk.array())
        offset += length
    }
    val pointsPerPacket = DATA_BYTES / POSE_BYTES
    for (start in 0 until count step pointsPerPacket) {
        val length = minOf(pointsPerPacket, count - start)
        val chunk = Wire.buffer(OFFSET_BYTES + length * POSE_BYTES).putInt(offset.toInt())
        for (index in start until start + length) {
            val pose = poses[index]
            chunk.putInt(pose.timeMs.toInt()).putInt(pose.frameId.toInt()).putInt(pose.xMm).putInt(pose.yMm)
                .putShort((pose.yawDegrees * 100).roundToInt().toShort())
                .putShort((pose.gimbalYaw * 100).roundToInt().toShort())
                .putShort((pose.gimbalPitch * 100).roundToInt().toShort())
        }
        send(Wire.TRAJECTORY_DATA, chunk.array())
        offset += length * POSE_BYTES
        onProgress(start + length, count)
    }
    if (send(Wire.TRAJECTORY_COMMIT, byteArrayOf()) != count.toLong()) throw IOException()
}
