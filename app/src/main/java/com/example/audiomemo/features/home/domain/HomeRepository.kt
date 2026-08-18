package com.example.audiomemo.features.home.domain

/**
 * Aggregated state of chunks still genuinely queued for Supabase upload —
 * `supabaseUploadStatus` in `PENDING`/`UPLOADING`/`FAILED`. Deliberately **never** includes
 * `SILENT` (am4-2, FR10: a silent chunk is intentionally never enqueued for upload — reporting it
 * as "aguardando envio" would be actively misleading) nor `DONE` (already sent).
 */
data class PendingUploadsInfo(val count: Int, val totalBytes: Long)

/**
 * Aggregated state of chunks already confirmed uploaded (`supabaseUploadStatus == DONE`) whose
 * local file still exists on disk — the rare auto-delete-miss case
 * (`SupabaseUploadWorker.deleteConfirmedUploadFile` failing, or a crash between marking `DONE`
 * and deleting) that the Home "Limpar arquivos já enviados" button targets.
 */
data class UploadedLocalFilesInfo(val count: Int, val totalBytes: Long)

/**
 * Home-dashboard-specific aggregation over data that already exists elsewhere (`ChunkDao`) — no
 * new Room table/column is introduced for this screen (am-hotfix-home-status-redesign boundary).
 */
interface HomeRepository {
    suspend fun getPendingUploadsInfo(): PendingUploadsInfo
    suspend fun getUploadedLocalFilesInfo(): UploadedLocalFilesInfo

    /**
     * Deletes the local file for every chunk confirmed uploaded (`supabaseUploadStatus == DONE`)
     * whose file still exists, reusing
     * [com.example.audiomemo.features.cloudsync.data.worker.SupabaseUploadWorker.deleteConfirmedUploadFile]'s
     * exact delete semantics (module-`internal`, already visible here — no extraction needed).
     * Re-queries fresh `DONE`+file-present chunks itself at delete time rather than trusting a
     * list computed earlier by the caller (avoids a stale-list TOCTOU race between the
     * confirmation dialog showing a count and the owner tapping confirm) — this is the only
     * write path for this screen, and it can **never** touch `PENDING`/`FAILED`/`UPLOADING`/
     * `SILENT` chunks no matter what.
     *
     * @return the number of files actually deleted.
     */
    suspend fun deleteUploadedLocalFiles(): Int
}
