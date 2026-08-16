package com.example.audiomemo.core.db

import com.example.audiomemo.data.db.MIGRATION_1_2_SQL
import io.kotest.core.spec.style.StringSpec
import java.sql.Connection
import java.sql.DriverManager

/**
 * Validates [MIGRATION_1_2_SQL] (the raw SQL Room's `MIGRATION_1_2` executes) against a *real*
 * SQLite engine, via plain JDBC (`org.xerial:sqlite-jdbc`, testImplementation-only) — code review
 * item 10 on am1-2.
 *
 * **Why JDBC instead of `androidx.room:room-testing`'s `MigrationTestHelper`:** `MigrationTestHelper`
 * needs an instrumented environment (`androidTest`, a device/emulator) — this project has no
 * `androidTest` Room infrastructure at all today (no schema-export directory either, since
 * `exportSchema = false`), so adopting it would be a disproportionate new investment for a single
 * one-line `ALTER TABLE`. This test is deliberately narrower: it does **not** verify Room's full v1
 * schema fidelity (column types/constraints for every entity) — only that the exact SQL string
 * Room will execute (a) is valid SQLite syntax, (b) adds the column with the right type/NOT
 * NULL/default, and (c) leaves pre-existing rows readable with the expected default applied. That
 * is precisely the failure mode this review item is worried about (a typo breaking the migration on
 * the owner's device, which already has real recorded chunks) — a full schema-fidelity test would
 * catch more, but this catches the actual risk at a fraction of the infra cost.
 *
 * Risk framing (documented per the review item, not just asserted): even in the worst case this
 * migration turns out broken and forces the owner to reinstall (wiping the local Room DB), no
 * *audio* is lost — every chunk that already reached `supabaseUploadStatus = DONE` is already
 * durably in Supabase Storage by the time that would happen. Only local upload-tracking bookkeeping
 * for as-yet-unconfirmed chunks would need to redo from a fresh v2 DB.
 */
class Migration1To2Test : StringSpec({

    fun openV1ChunksDb(): Connection {
        // In-memory DB, one instance per test (a fresh ":memory:" connection is a brand-new DB).
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        // Mirrors Room's actual v1-generated DDL for `chunks` closely enough to exercise the
        // ALTER TABLE realistically (backtick-quoted identifiers, same column set/order/types as
        // ChunkEntity pre-am1-2). The `sessions` FK target is intentionally omitted — SQLite
        // doesn't enforce FKs unless `PRAGMA foreign_keys=ON`, and it's irrelevant to what this
        // migration touches.
        connection.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE `chunks` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `sessionId` INTEGER NOT NULL,
                    `chunkIndex` INTEGER NOT NULL,
                    `filePath` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `overlapMs` INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            // Pre-existing rows, simulating real chunks already recorded on the owner's device
            // before this migration ships (the exact scenario the review item is worried about).
            stmt.execute(
                "INSERT INTO chunks (sessionId, chunkIndex, filePath, status, overlapMs) " +
                    "VALUES (1, 0, '/data/chunk_0.m4a', 'DONE', 0)"
            )
            stmt.execute(
                "INSERT INTO chunks (sessionId, chunkIndex, filePath, status, overlapMs) " +
                    "VALUES (1, 1, '/data/chunk_1.m4a', 'PENDING', 250)"
            )
        }
        return connection
    }

    "MIGRATION_1_2_SQL is valid SQLite and adds supabaseUploadStatus as TEXT NOT NULL DEFAULT 'PENDING'" {
        openV1ChunksDb().use { connection ->
            connection.createStatement().use { stmt -> stmt.execute(MIGRATION_1_2_SQL) }

            val columns = mutableMapOf<String, Triple<String, Boolean, String?>>()
            connection.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA table_info(chunks)").use { rs ->
                    while (rs.next()) {
                        columns[rs.getString("name")] = Triple(
                            rs.getString("type"),
                            rs.getInt("notnull") == 1,
                            rs.getString("dflt_value")
                        )
                    }
                }
            }

            val expectedColumns = setOf(
                "id", "sessionId", "chunkIndex", "filePath", "status", "overlapMs",
                "supabaseUploadStatus"
            )
            check(columns.keys == expectedColumns) { "expected columns $expectedColumns, got ${columns.keys}" }

            val (type, notNull, default) = columns.getValue("supabaseUploadStatus")
            check(type == "TEXT") { "expected type TEXT, got $type" }
            check(notNull) { "expected supabaseUploadStatus to be NOT NULL" }
            // SQLite echoes the default literal back with its quotes in PRAGMA table_info.
            check(default == "'PENDING'") { "expected default 'PENDING', got $default" }
        }
    }

    "pre-existing rows read back with supabaseUploadStatus defaulted to PENDING, other columns untouched" {
        openV1ChunksDb().use { connection ->
            connection.createStatement().use { stmt -> stmt.execute(MIGRATION_1_2_SQL) }

            data class Row(val chunkIndex: Int, val status: String, val supabaseUploadStatus: String)
            val rows = mutableListOf<Row>()
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT chunkIndex, status, supabaseUploadStatus FROM chunks ORDER BY chunkIndex")
                    .use { rs ->
                        while (rs.next()) {
                            rows += Row(
                                rs.getInt("chunkIndex"),
                                rs.getString("status"),
                                rs.getString("supabaseUploadStatus")
                            )
                        }
                    }
            }

            val expectedRows = listOf(
                Row(chunkIndex = 0, status = "DONE", supabaseUploadStatus = "PENDING"),
                Row(chunkIndex = 1, status = "PENDING", supabaseUploadStatus = "PENDING")
            )
            check(rows == expectedRows) { "expected $expectedRows, got $rows" }
        }
    }

    "the new column is writable after migration (not just present)" {
        openV1ChunksDb().use { connection ->
            connection.createStatement().use { stmt -> stmt.execute(MIGRATION_1_2_SQL) }
            connection.createStatement().use { stmt ->
                stmt.execute("UPDATE chunks SET supabaseUploadStatus = 'DONE' WHERE chunkIndex = 0")
            }

            var updated: String? = null
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT supabaseUploadStatus FROM chunks WHERE chunkIndex = 0").use { rs ->
                    rs.next()
                    updated = rs.getString("supabaseUploadStatus")
                }
            }
            check(updated == "DONE") { "expected DONE, got $updated" }
        }
    }
})
