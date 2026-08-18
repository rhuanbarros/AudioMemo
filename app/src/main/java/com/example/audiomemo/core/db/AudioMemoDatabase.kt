package com.example.audiomemo.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.dao.SessionDao
import com.example.audiomemo.data.db.dao.SummaryDao
import com.example.audiomemo.data.db.dao.TranscriptDao
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.data.db.entities.SessionEntity
import com.example.audiomemo.data.db.entities.SummaryEntity
import com.example.audiomemo.data.db.entities.TranscriptEntity

@Database(
    entities = [
        SessionEntity::class,
        ChunkEntity::class,
        TranscriptEntity::class,
        SummaryEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AudioMemoDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun chunkDao(): ChunkDao
    abstract fun transcriptDao(): TranscriptDao
    abstract fun summaryDao(): SummaryDao
}

/**
 * Raw SQL for [MIGRATION_1_2], exposed as a constant so
 * `com.example.audiomemo.core.db.Migration1To2Test` (a plain-JVM SQLite/JDBC test — see that file
 * for why this is validated there instead of via `androidx.room:room-testing`'s
 * `MigrationTestHelper`) executes the exact same statement Room runs on a real device, instead of
 * a hand-copied string that could silently drift out of sync.
 */
const val MIGRATION_1_2_SQL =
    "ALTER TABLE chunks ADD COLUMN supabaseUploadStatus TEXT NOT NULL DEFAULT 'PENDING'"

/**
 * Adds the `supabaseUploadStatus` column tracking each chunk's upload to Supabase Storage —
 * independent of the pre-existing `status` column, which belongs exclusively to the Whisper
 * pipeline (am1-2 story). Defaults every existing row to `PENDING` (Room's `Converters` stores
 * [com.example.audiomemo.features.transcript.domain.model.ChunkStatus] as its `name` string), so
 * chunks recorded before this migration are simply never backfilled/re-enqueued (out of scope —
 * see am1-2 spec gate resolution).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(MIGRATION_1_2_SQL)
    }
}
