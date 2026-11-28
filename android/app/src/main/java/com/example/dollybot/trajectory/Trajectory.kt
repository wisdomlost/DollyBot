package com.example.dollybot.trajectory

import com.example.dollybot.connection.LivePose
import com.example.dollybot.ui.TrajectoryKind
import java.util.UUID
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.roundToInt

internal data class StoredPose(val id: String, val pose: LivePose, val dwellMs: Int = 0)

internal data class SavedTrajectory(
    val id: String,
    val name: String,
    val kind: TrajectoryKind,
    val createdAt: Long,
    val updatedAt: Long,
    val points: List<StoredPose>,
    val incomplete: Boolean = false,
    val isDraft: Boolean = false,
    val speedPercent: Int = 100,
    val smooth: Boolean = false,
    val repeats: Int = 1
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
    private val waypoint = trajectory.kind == TrajectoryKind.Waypoints
    private fun cornerFactor(i: Int): Double {
        if (!trajectory.smooth || i <= 0 || i >= points.lastIndex) return 1.0
        val a = points[i-1].pose
        val b = points[i].pose
        val c = points[i+1].pose
        val ux = b.xMm.toDouble()-a.xMm
        val uy = b.yMm.toDouble()-a.yMm
        val vx = c.xMm.toDouble()-b.xMm
        val vy = c.yMm.toDouble()-b.yMm
        val norm = hypot(ux,uy)*hypot(vx,vy)
        return if (norm < 1) 1.0 else 1.0 + (1.0-(ux*vx+uy*vy)/norm).coerceIn(0.0,2.0)*0.5
    }
    private fun dwell(i: Int): Long {
        if (!waypoint) return 0
        val settle = if (!trajectory.smooth && i in 1 until points.lastIndex) 250L else 0L
        return settle + points[i].dwellMs
    }
    fun departure(i: Int) = times[i] + dwell(i)
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
            val bend = if (waypoint) maxOf(cornerFactor(index-1), cornerFactor(index)) else 1.0
            add(last() + dwell(index-1) + ceil(delta * bend * 100 / trajectory.speedPercent).toLong().coerceAtLeast(1))
        }
    }
    fun outline(): List<LivePose> {
        if (!waypoint || !trajectory.smooth) return points.map { it.pose }
        return buildList {
            for (i in 0 until times.lastIndex) {
                val start = departure(i)
                for (step in 0 until 12) poseAt(start+(times[i+1]-start)*step/12)?.let(::add)
            }
            points.lastOrNull()?.pose?.let(::add)
        }
    }
    val durationMs: Long get() = if (points.isEmpty()) 0L else times.last() + dwell(points.lastIndex)

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
        if (low == points.lastIndex) return points.last().pose
        val index = low
        val from = points[index].pose
        val to = points[index + 1].pose
        val departure = times[index] + dwell(index)
        if (time <= departure) return from
        val t = ((time-departure).toFloat()/(times[index+1]-departure)).coerceIn(0f,1f)
        val ratio = if (waypoint && (!trajectory.smooth || dwell(index)>0 || dwell(index+1)>0))
            t*t*(3f-2f*t) else t
        fun coordinate(axis: (LivePose) -> Int): Int {
            val a = axis(from).toDouble()
            val b = axis(to).toDouble()
            if (!waypoint || !trajectory.smooth) return (a+(b-a)*ratio).roundToInt()
            // Shape-preserving cubic Hermite. A coordinate has zero tangent at an extremum.
            fun tangent(left: Double, right: Double) =
                if (left*right <= 0) 0.0 else 2*left*right/(left+right)
            val before = points.getOrNull(index-1)?.pose
            val after = points.getOrNull(index+2)?.pose
            val m0 = if (before == null || dwell(index)>0) 0.0 else tangent(a-axis(before), b-a)
            val m1 = if (after == null || dwell(index+1)>0) 0.0 else tangent(b-a, axis(after)-b)
            val u = ratio.toDouble()
            return (a+(b-a)*u*u*(3-2*u)+m0*u*(1-u)*(1-u)+m1*u*u*(u-1)).roundToInt()
        }
        return from.copy(
            timeMs = time,
            xMm = coordinate { it.xMm },
            yMm = coordinate { it.yMm },
            yawDegrees = ((from.yawDegrees + angleDelta(from.yawDegrees, to.yawDegrees) * ratio + 540f) % 360f) - 180f,
            gimbalYaw = from.gimbalYaw + (to.gimbalYaw - from.gimbalYaw) * ratio,
            gimbalPitch = from.gimbalPitch + (to.gimbalPitch - from.gimbalPitch) * ratio
        )
    }
}

/** Evaluate the same camera path used by the editor; retain every arrival and departure. */
internal fun PreviewTimeline.executionSamples(): List<LivePose> {
    require(times.size <= 4096 && durationMs <= 3_600_000) { "轨迹超出车端容量" }
    val samples = sortedSetOf<Long>()
    for ((index,time) in times.withIndex()) {
        samples.add(time)
        if (trajectory.kind == TrajectoryKind.Waypoints) {
            val departure = departure(index)
            samples.add(departure)
            val end = times.getOrNull(index+1) ?: continue
            for (t in departure+50 until end step 50L) {
                samples.add(t)
                require(samples.size <= 4096) { "轨迹超过 4096 个执行点" }
            }
        }
    }
    require(samples.size <= 4096) { "轨迹超过 4096 个执行点" }
    return samples.map { poseAt(it)!!.copy(timeMs=it) }
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
