package com.example.dollybot

import com.example.dollybot.connection.CarStatus
import com.example.dollybot.connection.LivePose
import com.example.dollybot.connection.PushRecordingSession
import com.example.dollybot.ui.RecordingMode
import com.example.dollybot.ui.RecordingStage
import com.example.dollybot.ui.RecordingUiState
import com.example.dollybot.ui.TeachingPhase
import com.example.dollybot.ui.TrajectoryKind
import org.junit.Assert.*
import org.junit.Test

class PushRecordingSessionTest {
    private fun sample(index: Long, id: Long = 7, frame: Long = 1, mode: Int = 3) = CarStatus(
        LivePose(index * 50, frame, (index * 10).toInt(), 200, 90f, 30f, -15f),
        mode, 1, id, index, 0
    )

    @Test
    fun samplesStayHiddenUntilAckAndRetainEveryPoseField() {
        val session = PushRecordingSession(1)
        session.receive(sample(1))
        session.receive(sample(2))
        assertFalse(session.confirmed)
        assertTrue(session.run.poses.isEmpty())
        assertEquals(TeachingPhase.Empty, session.run.phase)
        session.confirm(7)
        assertEquals(listOf(sample(1).pose, sample(2).pose), session.run.poses)
        assertEquals(TeachingPhase.Recording, session.run.phase)
        assertFalse(session.run.incomplete)
    }

    @Test
    fun otherRunsAndModesDoNotReplaceTheConfirmedRunAndDuplicatesAreIgnored() {
        val session = PushRecordingSession(1)
        session.receive(sample(1, id = 6))
        session.receive(sample(1))
        session.confirm(7)
        session.receive(sample(1))
        session.receive(sample(2, id = 8))
        session.receive(sample(2, mode = 1))
        session.receive(sample(2))
        assertEquals(listOf(sample(1).pose, sample(2).pose), session.run.poses)
        assertFalse(session.run.incomplete)
    }

    @Test
    fun indexGapMarksRecordingIncomplete() {
        val session = PushRecordingSession(1)
        session.confirm(7)
        session.receive(sample(1))
        session.receive(sample(3))
        assertEquals(3L, session.run.lastIndex)
        assertEquals(2, session.run.poses.size)
        assertTrue(session.run.incomplete)
    }

    @Test
    fun frameChangePreservesValidSamplesAndRejectsSubsequentSamples() {
        val session = PushRecordingSession(1)
        session.receive(sample(1))
        session.receive(sample(2, frame = 2))
        session.confirm(7)
        session.receive(sample(3))
        assertTrue(session.frameChanged)
        assertEquals(listOf(sample(1).pose), session.run.poses)
        assertTrue(session.run.incomplete)
        assertEquals(TeachingPhase.Interrupted, session.run.phase)
    }

    @Test
    fun boundedPreAckBufferMarksOverflowIncomplete() {
        val session = PushRecordingSession(1)
        (1L..300L).forEach { session.receive(sample(it)) }
        session.confirm(7)
        assertEquals(256, session.run.poses.size)
        assertTrue(session.run.incomplete)
        assertEquals(300L, session.run.lastIndex)
    }

    @Test
    fun pushBlocksDrivingOnlyDuringStartRecordingAndFinishAndSavesAsContinuous() {
        assertEquals(3, RecordingMode.Push.wireMode)
        assertEquals(TrajectoryKind.Continuous, RecordingMode.Push.kind)
        for (stage in RecordingStage.entries) {
            assertEquals(stage in listOf(RecordingStage.Starting, RecordingStage.Recording, RecordingStage.Finishing),
                RecordingUiState(mode = RecordingMode.Push, stage = stage).blocksDriving)
            assertFalse(RecordingUiState(mode = RecordingMode.Continuous, stage = stage).blocksDriving)
        }
    }
}
