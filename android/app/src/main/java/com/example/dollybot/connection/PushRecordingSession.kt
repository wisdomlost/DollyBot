package com.example.dollybot.connection

import com.example.dollybot.ui.TeachingPhase
import com.example.dollybot.ui.TeachingRun

/** A push run becomes visible only after its begin ACK, including samples delivered before it. */
internal class PushRecordingSession(val frameId: Long) {
    private val earlySamples = mutableListOf<CarStatus>()
    private var overflow = false
    var confirmed: Boolean = false
        private set
    var frameChanged: Boolean = false
        private set
    var run: TeachingRun = TeachingRun()
        private set

    fun confirm(teachId: Long) {
        require(teachId != 0L && !confirmed)
        confirmed = true
        run = TeachingRun(id = teachId, phase = TeachingPhase.Recording, incomplete = overflow)
        earlySamples.forEach(::receive)
        earlySamples.clear()
    }

    fun receive(status: CarStatus) {
        if (status.mode != 3) return
        if (!confirmed) {
            // Three one-second attempts at the minimum supported 20 ms period fit within this bound.
            if (earlySamples.size == 256) {
                earlySamples.removeAt(0)
                overflow = true
            }
            earlySamples += status
            return
        }
        if (status.teachId != run.id || frameChanged) return
        if (status.pose.frameId != frameId) {
            interruptForFrameChange()
            return
        }
        if (status.pointIndex <= run.lastIndex) return
        run = run.copy(
            poses = run.poses + status.pose,
            lastIndex = status.pointIndex,
            incomplete = run.incomplete || status.pointIndex != run.lastIndex + 1
        )
    }

    fun interruptForFrameChange() {
        frameChanged = true
        run = run.copy(phase = TeachingPhase.Interrupted, incomplete = true)
    }
}
