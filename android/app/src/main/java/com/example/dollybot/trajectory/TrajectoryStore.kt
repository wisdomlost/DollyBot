package com.example.dollybot.trajectory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.dollybot.connection.LivePose
import com.example.dollybot.ui.TrajectoryKind

internal class TrajectoryStore(context: Context, name: String = "trajectories.db") :
    SQLiteOpenHelper(context, name, null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE trajectories (
                id TEXT PRIMARY KEY, name TEXT NOT NULL, kind TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
                incomplete INTEGER NOT NULL, is_draft INTEGER NOT NULL,
                speed_percent INTEGER NOT NULL DEFAULT 100, smooth INTEGER NOT NULL DEFAULT 0,
                repeats INTEGER NOT NULL DEFAULT 1
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE poses (
                id TEXT PRIMARY KEY, trajectory_id TEXT NOT NULL,
                position INTEGER NOT NULL, time_ms INTEGER NOT NULL, frame_id INTEGER NOT NULL,
                x_mm INTEGER NOT NULL, y_mm INTEGER NOT NULL,
                yaw REAL NOT NULL, gimbal_yaw REAL NOT NULL, gimbal_pitch REAL NOT NULL, dwell_ms INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(trajectory_id) REFERENCES trajectories(id) ON DELETE CASCADE,
                UNIQUE(trajectory_id, position)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE trajectories ADD COLUMN speed_percent INTEGER NOT NULL DEFAULT 100")
            db.execSQL("ALTER TABLE trajectories ADD COLUMN smooth INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE trajectories ADD COLUMN repeats INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE poses ADD COLUMN dwell_ms INTEGER NOT NULL DEFAULT 0")
        }
    }

    fun put(trajectory: SavedTrajectory) {
        require(trajectory.points.isNotEmpty())
        require(trajectory.points.map { it.id }.distinct().size == trajectory.points.size)
        require(trajectory.isDraft || trajectory.name.trim().isNotEmpty())
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("id", trajectory.id)
                put("name", trajectory.name.trim())
                put("kind", trajectory.kind.name)
                put("created_at", trajectory.createdAt)
                put("updated_at", trajectory.updatedAt)
                put("incomplete", if (trajectory.incomplete) 1 else 0)
                put("is_draft", if (trajectory.isDraft) 1 else 0)
                put("speed_percent",trajectory.speedPercent)
                put("smooth",if (trajectory.smooth) 1 else 0)
                put("repeats",trajectory.repeats)
            }
            if (db.update("trajectories", values, "id=?", arrayOf(trajectory.id)) == 0) {
                db.insertOrThrow("trajectories", null, values)
            }
            db.delete("poses", "trajectory_id=?", arrayOf(trajectory.id))
            db.compileStatement("INSERT INTO poses VALUES(?,?,?,?,?,?,?,?,?,?,?)").use { statement ->
                trajectory.points.forEachIndexed { index, point ->
                    statement.clearBindings()
                    statement.bindString(1, point.id)
                    statement.bindString(2, trajectory.id)
                    statement.bindLong(3, index.toLong())
                    statement.bindLong(4, point.pose.timeMs)
                    statement.bindLong(5, point.pose.frameId)
                    statement.bindLong(6, point.pose.xMm.toLong())
                    statement.bindLong(7, point.pose.yMm.toLong())
                    statement.bindDouble(8, point.pose.yawDegrees.toDouble())
                    statement.bindDouble(9, point.pose.gimbalYaw.toDouble())
                    statement.bindDouble(10, point.pose.gimbalPitch.toDouble())
                    statement.bindLong(11, point.dwellMs.toLong())
                    statement.executeInsert()
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun all(): List<SavedTrajectory> = buildList {
        val db = readableDatabase
        db.rawQuery("SELECT * FROM trajectories ORDER BY updated_at DESC, id ASC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val points = buildList {
                    db.rawQuery("SELECT * FROM poses WHERE trajectory_id=? ORDER BY position", arrayOf(id)).use { rows ->
                        while (rows.moveToNext()) {
                            add(StoredPose(rows.getString(0), LivePose(
                                rows.getLong(3), rows.getLong(4), rows.getInt(5), rows.getInt(6),
                                rows.getFloat(7), rows.getFloat(8), rows.getFloat(9)
                            ), rows.getInt(10)))
                        }
                    }
                }
                add(SavedTrajectory(
                    id, cursor.getString(1), TrajectoryKind.valueOf(cursor.getString(2)),
                    cursor.getLong(3), cursor.getLong(4), points,
                    cursor.getInt(5) != 0, cursor.getInt(6) != 0, cursor.getInt(7), cursor.getInt(8) != 0, cursor.getInt(9)
                ))
            }
        }
    }

    fun delete(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("trajectories", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
