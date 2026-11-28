package com.example.dollybot

import com.example.dollybot.connection.LivePose
import com.example.dollybot.trajectory.executionSamples
import com.example.dollybot.trajectory.PreviewTimeline
import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.StoredPose
import com.example.dollybot.ui.TrajectoryKind
import org.junit.Assert.*
import org.junit.Test

class PreviewTimelineTest {
    private fun point(id: String, time: Long, x: Int, yaw: Float = 0f) =
        StoredPose(id, LivePose(time, 1, x, 0, yaw, 30f, -10f))

    private fun track(kind: TrajectoryKind, vararg points: StoredPose) =
        SavedTrajectory("test", "测试", kind, 1, 1, points.toList())

    @Test
    fun continuousInterpolationUsesRelativeTimeAndShortestHeading() {
        val trajectory = track(TrajectoryKind.Continuous,
            point("a", 5000, 0, 179f), point("b", 7000, 200, -179f))
        val timeline = PreviewTimeline(trajectory)
        assertEquals(2000L, timeline.durationMs)
        val middle = timeline.poseAt(1000)!!
        assertEquals(100, middle.xMm)
        assertEquals(180f, kotlin.math.abs(middle.yawDegrees), 0.01f)
        assertEquals(30f, middle.gimbalYaw, 0f)
        assertEquals(trajectory.points.last().pose, timeline.poseAt(3000))
    }

    @Test
    fun reorderedWaypointsUseGeometryInsteadOfOriginalTimestamps() {
        val trajectory = track(TrajectoryKind.Waypoints,
            point("b", 9000, 200), point("a", 1000, 0))
        val timeline = PreviewTimeline(trajectory)
        assertEquals(1000L, timeline.durationMs)
        assertEquals(100, timeline.poseAt(500)!!.xMm)
        assertEquals(listOf("b", "a"), trajectory.points.map { it.id })
    }

    @Test
    fun rotationAndZeroDistanceRespectMinimumSegmentDuration() {
        assertEquals(2000L, PreviewTimeline(track(TrajectoryKind.Waypoints,
            point("a", 0, 0), point("b", 10, 0, 180f))).durationMs)
        assertEquals(200L, PreviewTimeline(track(TrajectoryKind.Waypoints,
            point("a", 0, 0), point("b", 10, 0))).durationMs)
    }

    @Test
    fun executionAndPreviewShareDwellCurveGimbalAndRate() {
        val points = listOf(point("a",0,0), point("b",300,100,45f).copy(dwellMs=1000),
            point("c",500,200,90f).let { it.copy(pose=it.pose.copy(yMm=100,gimbalPitch=30f)) })
        val route = track(TrajectoryKind.Waypoints,*points.toTypedArray()).copy(smooth=true,speedPercent=50,repeats=3)
        val timeline = PreviewTimeline(route)
        val samples = timeline.executionSamples()
        assertTrue(samples.zipWithNext().all { (a,b) -> b.timeMs>a.timeMs })
        val arrival = timeline.times[1]
        assertEquals(100,timeline.poseAt(arrival+999)!!.xMm)
        assertEquals(0,timeline.poseAt(arrival+999)!!.yMm)
        assertTrue(samples.any { it.timeMs==arrival+1000 })
        for (p in samples) assertEquals(p,timeline.poseAt(p.timeMs)!!.copy(timeMs=p.timeMs))
        assertEquals(30f,samples.last().gimbalPitch,0f)
        val fast=PreviewTimeline(route.copy(speedPercent=200))
        assertTrue(timeline.durationMs-1000 >= 4*(fast.durationMs-1000)-8)
    }

    @Test
    fun threeMinuteContinuousShotFitsWithoutResamplingAwayTheEnd() {
        val points=(0..3600).map { point(it.toString(),10000L+it*50,it) }
        val timeline=PreviewTimeline(track(TrajectoryKind.Continuous,*points.toTypedArray()))
        val samples=timeline.executionSamples()
        assertEquals(3601,samples.size)
        assertEquals(180000L,samples.last().timeMs)
    }

    @Test
    fun clockWrapEmptyAndSinglePointRemainValid() {
        val wrapped = PreviewTimeline(track(TrajectoryKind.Continuous,
            point("a", 0xFFFFFFF0, 0), point("b", 32, 48)))
        assertEquals(48L, wrapped.durationMs)
        assertEquals(24, wrapped.poseAt(24)!!.xMm)
        assertNull(PreviewTimeline(track(TrajectoryKind.Continuous)).poseAt(0))
        val single = point("a", 0, 5)
        assertEquals(single.pose, PreviewTimeline(track(TrajectoryKind.Waypoints, single)).poseAt(0))
    }
}
