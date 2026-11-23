package com.example.dollybot.connection

import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.ui.RecordingMode
import java.io.IOException
import kotlin.math.roundToInt

private const val POSE_BYTES = 22
private const val OFFSET_BYTES = 4
private const val DATA_BYTES = Wire.MAX_PAYLOAD - OFFSET_BYTES

internal suspend fun uploadTrajectory(
    trajectory: SavedTrajectory,
    send: suspend (type: Int, data: ByteArray) -> Long,
    onProgress: (Int) -> Unit
) {
    require(!trajectory.isDraft && trajectory.points.isNotEmpty())
    val name = trajectory.name.toByteArray(Charsets.UTF_8)
    val count = trajectory.points.size
    require(name.isNotEmpty() && name.size + count.toLong() * POSE_BYTES <= 0xFFFFFFFFL)
    val begin = Wire.buffer(10)
        .put(RecordingMode.fromKind(trajectory.kind).wireMode.toByte())
        .put(if (trajectory.incomplete) 1 else 0)
        .putInt(name.size).putInt(count)
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
            val pose = trajectory.points[index].pose
            chunk.putInt(pose.timeMs.toInt()).putInt(pose.frameId.toInt()).putInt(pose.xMm).putInt(pose.yMm)
                .putShort((pose.yawDegrees * 100).roundToInt().toShort())
                .putShort((pose.gimbalYaw * 100).roundToInt().toShort())
                .putShort((pose.gimbalPitch * 100).roundToInt().toShort())
        }
        send(Wire.TRAJECTORY_DATA, chunk.array())
        offset += length * POSE_BYTES
        onProgress(start + length)
    }
    if (send(Wire.TRAJECTORY_COMMIT, byteArrayOf()) != count.toLong()) throw IOException()
}
