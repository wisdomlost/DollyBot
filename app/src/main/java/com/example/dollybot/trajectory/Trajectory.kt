package com.example.dollybot.trajectory

import com.example.dollybot.connection.LivePose
import com.example.dollybot.ui.TrajectoryKind
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class StoredPose(val id: String, val pose: LivePose)

internal data class SavedTrajectory(
    val id: String,
    val name: String,
    val kind: TrajectoryKind,
    val createdAt: Long,
    val updatedAt: Long,
    val points: List<StoredPose>,
    val incomplete: Boolean = false,
    val isDraft: Boolean = false
) {
    companion object {
        fun recorded(
            kind: TrajectoryKind, poses: List<LivePose>, incomplete: Boolean,
            id: String = UUID.randomUUID().toString()
        ): SavedTrajectory {
            val now = System.currentTimeMillis()
            return SavedTrajectory(
                id, "", kind, now, now,
                poses.map { StoredPose(UUID.randomUUID().toString(), it) }, incomplete, true
            )
        }
    }
}

/** Pure phone-side interpolation. It has no connection to the Bluetooth transport. */
internal class PreviewTimeline(val trajectory: SavedTrajectory) {
    private val points = trajectory.points
    val times: List<Long> = buildList {
        if (points.isNotEmpty()) add(0L)
        for (index in 1 until points.size) {
            val from = points[index - 1].pose
            val to = points[index].pose
            val delta = if (trajectory.kind == TrajectoryKind.Continuous) {
                (to.timeMs - from.timeMs) and 0xFFFFFFFFL
            } else {
                val distance = hypot(to.xMm.toDouble() - from.xMm, to.yMm.toDouble() - from.yMm)
                ceil(maxOf(distance / 200.0, abs(angleDelta(from.yawDegrees, to.yawDegrees)) / 90.0, 0.2) * 1000).toLong()
            }
            add(last() + delta)
        }
    }
    val durationMs: Long get() = times.lastOrNull() ?: 0L

    fun poseAt(elapsedMs: Long): LivePose? {
        if (points.isEmpty()) return null
        if (points.size == 1 || elapsedMs >= durationMs) return points.last().pose
        val time = elapsedMs.coerceAtLeast(0)
        var low = 0
        var high = times.lastIndex
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (times[middle] <= time) low = middle else high = middle - 1
        }
        val index = low.coerceAtMost(points.lastIndex - 1)
        val from = points[index].pose
        val to = points[index + 1].pose
        val duration = times[index + 1] - times[index]
        val ratio = if (duration == 0L) 1f else (time - times[index]).toFloat() / duration
        return from.copy(
            timeMs = time,
            xMm = (from.xMm.toDouble() + (to.xMm.toDouble() - from.xMm) * ratio).roundToInt(),
            yMm = (from.yMm.toDouble() + (to.yMm.toDouble() - from.yMm) * ratio).roundToInt(),
            yawDegrees = ((from.yawDegrees + angleDelta(from.yawDegrees, to.yawDegrees) * ratio + 540f) % 360f) - 180f,
            gimbalYaw = from.gimbalYaw + (to.gimbalYaw - from.gimbalYaw) * ratio,
            gimbalPitch = from.gimbalPitch + (to.gimbalPitch - from.gimbalPitch) * ratio
        )
    }
}

internal fun angleDelta(from: Float, to: Float): Float = ((to - from + 540f) % 360f) - 180f

internal data class TrajectoryEditorState(
    val original: SavedTrajectory,
    val edited: SavedTrajectory = original,
    val selectedPointId: String? = original.points.firstOrNull()?.id,
    val elapsedMs: Long = 0,
    val playing: Boolean = false,
    val saving: Boolean = false,
    val message: String? = null
) {
    val dirty: Boolean get() = original != edited
}
