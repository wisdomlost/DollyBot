package com.example.dollybot

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.dollybot.connection.LivePose
import com.example.dollybot.trajectory.SavedTrajectory
import com.example.dollybot.trajectory.StoredPose
import com.example.dollybot.trajectory.TrajectoryStore
import com.example.dollybot.ui.TrajectoryKind
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrajectoryStoreTest {
    private lateinit var context: Context
    private lateinit var store: TrajectoryStore
    private val databaseName = "test-trajectories-" + UUID.randomUUID() + ".db"

    @Before
    fun createStore() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        store = TrajectoryStore(context, databaseName)
    }

    @After
    fun cleanup() {
        store.close()
        context.deleteDatabase(databaseName)
    }

    private fun track(id: String, draft: Boolean = false) = SavedTrajectory(
        id, if (draft) "" else "同名轨迹", TrajectoryKind.Waypoints, 100, 200,
        listOf(
            StoredPose(id + "-a", LivePose(1000, 3, 10, 20, 30f, 40f, -50f)),
            StoredPose(id + "-b", LivePose(2000, 3, 80, 90, 100f, 110f, -120f))
        ), incomplete = true, isDraft = draft
    )

    @Test
    fun draftRenameOrderAndAllPoseFieldsSurviveReopening() {
        val draft = track("first", draft = true)
        store.put(draft)
        store.close()
        store = TrajectoryStore(context, databaseName)
        assertEquals(draft, store.all().single())
        val saved = draft.copy(name = "重新命名", isDraft = false, points = draft.points.reversed())
        store.put(saved)
        store.close()
        store = TrajectoryStore(context, databaseName)
        assertEquals(saved, store.all().single())
        store.delete(saved.id)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun duplicateNamesUseIndependentIdsAndFailedUpdateRollsBack() {
        val first = track("first")
        val second = track("second")
        store.put(first)
        store.put(second)
        assertEquals(2, store.all().size)
        try {
            store.put(first.copy(name = "不应提交", points = second.points))
            fail("Duplicate point IDs should reject the transaction")
        } catch (_: android.database.sqlite.SQLiteConstraintException) {
            assertEquals(first, store.all().first { it.id == first.id })
            assertEquals(second, store.all().first { it.id == second.id })
        }
    }
}
