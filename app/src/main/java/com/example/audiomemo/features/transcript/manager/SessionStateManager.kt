package com.example.audiomemo.features.transcript.manager

import com.example.audiomemo.data.db.dao.ChunkDao
import com.example.audiomemo.data.db.dao.SessionDao
import com.example.audiomemo.data.db.entities.ChunkEntity
import com.example.audiomemo.data.db.entities.SessionEntity
import com.example.audiomemo.features.transcript.domain.model.ChunkStatus
import com.example.audiomemo.features.transcript.domain.model.SessionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists recording session lifecycle (RECORDING → PAUSED → STOPPED) and completed chunk
 * metadata to Room so the app can recover after a crash or process death.
 */
class SessionStateManager(
    private val sessionDao: SessionDao,
    private val chunkDao: ChunkDao
) {
    var currentSessionId: Long = -1L
        private set

    private var chunkIndex = 0

    suspend fun startSession(): Long = withContext(Dispatchers.IO) {
        // Promote any RECORDING sessions left by a prior crash/process-kill to PAUSED so
        // getLastActiveSession() doesn't surface them as still-live ghost sessions.
        sessionDao.abandonOrphanedSessions()
        val id = sessionDao.insert(
            SessionEntity(
                state = SessionState.RECORDING,
                startTime = System.currentTimeMillis()
            )
        )
        currentSessionId = id
        chunkIndex = 0
        id
    }

    suspend fun pauseSession() = withContext(Dispatchers.IO) {
        if (currentSessionId > 0) sessionDao.updateState(currentSessionId, SessionState.PAUSED)
    }

    suspend fun resumeSession() = withContext(Dispatchers.IO) {
        if (currentSessionId > 0) sessionDao.updateState(currentSessionId, SessionState.RECORDING)
    }

    suspend fun stopSession() = withContext(Dispatchers.IO) {
        if (currentSessionId > 0) sessionDao.updateState(currentSessionId, SessionState.STOPPED)
    }

    /**
     * Creates the chunk's Room row in [ChunkStatus.RECORDING], right when recording of a new
     * chunk begins (am3-5) — before this, a chunk killed mid-recording left no trace in Room at
     * all until [saveChunk] ran, so the corrupted `.m4a` was untraceable. [saveChunk] later
     * UPDATEs this same row (matched by [filePath], stable across a chunk's lifetime) instead of
     * inserting a second row.
     */
    suspend fun markChunkStarted(filePath: String): Long = withContext(Dispatchers.IO) {
        if (currentSessionId > 0) {
            chunkDao.insert(
                ChunkEntity(
                    sessionId = currentSessionId,
                    chunkIndex = chunkIndex++,
                    filePath = filePath,
                    status = ChunkStatus.RECORDING
                )
            )
        } else -1L
    }

    /**
     * @param wasSilent (am4-2, FR9/FR10) true when the chunk's amplitude never crossed
     *   [com.example.audiomemo.features.transcript.manager.AudioRecorderManager.Companion.SILENCE_AMPLITUDE_THRESHOLD]
     *   during its whole recording. Persists `supabaseUploadStatus = SILENT` instead of the normal
     *   `PENDING` — a terminal state no existing supabaseUploadStatus-based sweep/query ever picks
     *   up (see [ChunkStatus.SILENT]'s KDoc for why this is a dedicated value, never `DONE`) — so
     *   the chunk is never enqueued for Supabase upload, while its local file and Whisper `status`
     *   (unaffected by this parameter) follow the exact same path as any other finished chunk.
     */
    suspend fun saveChunk(filePath: String, wasSilent: Boolean = false): Long = withContext(Dispatchers.IO) {
        if (currentSessionId > 0) {
            val supabaseUploadStatus = if (wasSilent) ChunkStatus.SILENT else ChunkStatus.PENDING
            val existing = chunkDao.getChunkByFilePathAndSession(filePath, currentSessionId)
            if (existing != null) {
                // Normal path (am3-5): update the RECORDING row markChunkStarted already created
                // for this exact filePath instead of inserting a second row for the same chunk.
                chunkDao.update(
                    existing.copy(status = ChunkStatus.PENDING, supabaseUploadStatus = supabaseUploadStatus)
                )
                existing.id
            } else {
                // Defensive fallback, should not happen in the normal flow (markChunkStarted is
                // always called before a chunk starts recording) — insert rather than silently
                // losing the chunk, matching pre-am3-5 behavior for this edge case.
                chunkDao.insert(
                    ChunkEntity(
                        sessionId = currentSessionId,
                        chunkIndex = chunkIndex++,
                        filePath = filePath,
                        status = ChunkStatus.PENDING,
                        supabaseUploadStatus = supabaseUploadStatus
                    )
                )
            }
        } else -1L
    }
}
