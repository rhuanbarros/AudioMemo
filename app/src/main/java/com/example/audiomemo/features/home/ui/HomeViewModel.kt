package com.example.audiomemo.features.home.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.logging.LogEvent
import com.example.audiomemo.core.logging.LogFileReader
import com.example.audiomemo.features.home.domain.HomeRepository
import com.example.audiomemo.features.home.domain.PendingUploadsInfo
import com.example.audiomemo.features.home.domain.UploadedLocalFilesInfo
import com.example.audiomemo.features.home.domain.model.HealthSegment
import com.example.audiomemo.features.home.domain.model.HealthState
import com.example.audiomemo.features.home.ui.state.HealthBanner
import com.example.audiomemo.features.home.ui.state.HomeUiState
import com.example.audiomemo.features.home.ui.state.LiveRecordingStatus
import com.example.audiomemo.features.summary.domain.repository.SummaryRepository
import com.example.audiomemo.features.transcript.domain.model.SessionState
import com.example.audiomemo.features.transcript.domain.repository.ChunkRepository
import com.example.audiomemo.features.transcript.domain.repository.SessionRepository
import com.example.audiomemo.features.transcript.domain.repository.TranscriptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Home as a pure status/health panel (am-hotfix-home-status-redesign) — zero manual recording
 * control anywhere in this ViewModel. Every field in [uiState] is derived from data that already
 * exists: [SessionRepository] (live status), [AppEventLogger]/[LogFileReader] (24h health strip +
 * "last successful upload"/error banner), and [HomeRepository] (chunk upload aggregates, over
 * `ChunkDao` — no new Room table/column).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val sessionRepository: SessionRepository,
    private val summaryRepository: SummaryRepository,
    private val chunkRepository: ChunkRepository,
    private val transcriptRepository: TranscriptRepository,
    private val appEventLogger: AppEventLogger,
    private val logFileReader: LogFileReader
) : ViewModel() {

    internal companion object {
        private const val TAG = "HomeViewModel"

        internal const val HEALTH_STRIP_HOURS = 24
        private const val HOUR_MS = 60 * 60 * 1000L

        /** How often the health strip / banner / offline-inference are recomputed against the
         *  wall clock while the screen is open — hour-bucket granularity doesn't need
         *  second-level freshness (the live duration ticks separately, in the UI layer). */
        internal const val HEALTH_TICK_INTERVAL_MS = 60_000L

        /** How often [HomeRepository]'s one-shot (non-`Flow`) `ChunkDao` queries are re-polled —
         *  `ChunkDao.getChunksBySupabaseUploadStatus` has no reactive variant (Code Map: "só
         *  leitura", no DAO changes for this screen), so periodic polling is the only way to keep
         *  the sync/storage cards reasonably fresh without touching it. */
        internal const val UPLOAD_POLL_INTERVAL_MS = 10_000L

        /** Design Notes' offline inference grace window: chunks are stuck pending, and neither a
         *  recent successful upload nor a short-enough "pending observed" streak clears it yet.
         *  5 minutes ≈ 2-3 chunk cycles (chunks are 2 minutes, FR1) without a single confirmed
         *  upload — long enough to not false-positive on a single slow upload or a fresh
         *  install's very first chunk, short enough to surface a real outage promptly. Value
         *  picked here per the Design Notes' explicit "decisão a refinar durante a implementação,
         *  documentar a escolha final". */
        internal const val OFFLINE_INFERENCE_WINDOW_MS = 5 * 60 * 1000L

        /**
         * (Code review, am-hotfix never-stop-recording, patch 2): every genuine "recording
         * stopped for a real reason" `INTERRUPTION`-category message
         * [AudioRecordingService] emits ends with this exact suffix — `LOW_STORAGE_STOPPED_MESSAGE`,
         * `PERMISSION_REVOKED_STOPPED_MESSAGE`, and `hardwareErrorStoppedMessage(...)` all share it
         * by construction. A suffix check (not an exact-string allowlist like the old
         * `ERROR_MESSAGES` set) means a future edit to the exact wording — e.g. interpolating the
         * attempt count into the hardware-error message, as this same story just did — can never
         * again silently desync this classifier from the producer without a test failing; the old
         * set-membership version broke exactly that way (confirmed independently by 2 reviewers)
         * and `HomeViewModelTest`'s hand-typed fixtures didn't catch it.
         *
         * Deliberately does NOT match "Recording stopped" (no dash — that's the separate
         * `RECORDING`-category explicit-stop message, handled by its own branch below as
         * [HealthState.PAUSED]) nor battery-low's current message (`BATTERY_LOW_CONTINUES_MESSAGE`
         * — battery-low no longer stops anything, so it must never classify as [HealthState.ERROR]
         * again; it doesn't end in this suffix by construction).
         */
        private const val STOPPED_FOR_REAL_REASON_SUFFIX = "— recording stopped"

        /**
         * Maps a single [LogEvent] to the [HealthState] it sets going forward, or `null` when the
         * event isn't one of the state-setting ones the story lists (e.g. "Audio source changed
         * to X", any `UPLOAD`-category event) — those are ignored by the health strip entirely.
         *
         * "Recording stopped" (`RECORDING` category, the explicit-stop path,
         * `AudioRecordingService.stopRecordingCleanly`) maps to [HealthState.PAUSED], not
         * [HealthState.ERROR] — nothing actually broke, recording is simply off until the next
         * "Recording started".
         */
        internal fun classifyEvent(event: LogEvent): HealthState? = when {
            event.category == LogCategory.RECORDING && event.message == "Recording started" ->
                HealthState.RECORDING
            event.category == LogCategory.RECORDING && event.message == "Recording stopped" ->
                HealthState.PAUSED
            event.category == LogCategory.INTERRUPTION && event.message.startsWith("Recording paused:") ->
                HealthState.PAUSED
            event.category == LogCategory.INTERRUPTION && event.message == "Recording resumed" ->
                HealthState.RECORDING
            event.category == LogCategory.INTERRUPTION && event.message.endsWith(STOPPED_FOR_REAL_REASON_SUFFIX) ->
                HealthState.ERROR
            else -> null
        }

        /**
         * Classifies each of the last [hourCount] hours (ending at [nowMs]) into a [HealthState],
         * derived only from already-logged [events] (am-hotfix-home-status-redesign Code Map — no
         * new log category, no new Room column). Returns oldest bucket first.
         *
         * **Carry-forward:** a bucket with no relevant event inherits the state last set by an
         * earlier event (possibly well before the 24h window — e.g. a single "Recording started"
         * from days ago should paint the *whole* strip green for an uninterrupted long session).
         * A bucket inherits nothing (→ [HealthState.NO_DATA]) only when no state-setting event
         * ever preceded it — the fresh-install case (I/O matrix: "Log com menos de 24h de
         * histórico… tira mostra só o período coberto, resto neutro").
         *
         * **Worst-state-wins within a bucket:** when a single hour contains more than one
         * relevant event (e.g. healthy for 50 minutes, then an error at minute 55),
         * `ERROR > PAUSED > RECORDING` — the strip's job is surfacing problems, not averaging
         * over them (matrix: "Aquela hora na tira fica vermelha", singular). No single boundary
         * line spells out the mixed-hour case explicitly; this is the implementation's
         * documented, low-risk reading of it (Dev Agent Record).
         *
         * An event timestamped after [nowMs] (clock skew, e.g. an NTP backward correction) is
         * never included in any bucket — every bucket's range tops out at [nowMs] by construction
         * — so a future-dated event silently can't win a bucket. [currentHealthStatus] applies the
         * same guard explicitly (code review, patch D) so the two stay consistent.
         */
        internal fun computeHourlySegments(
            events: List<LogEvent>,
            nowMs: Long,
            hourCount: Int = HEALTH_STRIP_HOURS
        ): List<HealthSegment> {
            val relevant = events
                .filter { it.category == LogCategory.RECORDING || it.category == LogCategory.INTERRUPTION }
                .sortedBy { it.timestamp }

            val windowStart = nowMs - hourCount * HOUR_MS

            var carryState: HealthState? = relevant
                .asReversed()
                .firstNotNullOfOrNull { event -> if (event.timestamp < windowStart) classifyEvent(event) else null }

            return (0 until hourCount).map { i ->
                val bucketStart = windowStart + i * HOUR_MS
                val bucketEnd = bucketStart + HOUR_MS
                val bucketStates = relevant
                    .filter { it.timestamp >= bucketStart && it.timestamp < bucketEnd }
                    .mapNotNull(::classifyEvent)

                val state = when {
                    HealthState.ERROR in bucketStates -> HealthState.ERROR
                    HealthState.PAUSED in bucketStates -> HealthState.PAUSED
                    HealthState.RECORDING in bucketStates -> HealthState.RECORDING
                    carryState != null -> carryState!!
                    else -> HealthState.NO_DATA
                }

                if (bucketStates.isNotEmpty()) carryState = bucketStates.last()

                HealthSegment(bucketStart, bucketEnd, state)
            }
        }

        /**
         * The state "right now" (independent of the 24h window) plus the [LogEvent] that set it —
         * used for the health banner. Scans the *entire* [events] list (not just the last 24h) so
         * a long-running healthy session doesn't read as [HealthState.NO_DATA] just because its
         * one "Recording started" event happened more than a day ago.
         *
         * Events timestamped after [nowMs] are excluded (code review, patch D) — a device clock
         * moving backward (NTP correction) must never let a stale future-dated event outrank the
         * real most-recent one, which would otherwise disagree with [computeHourlySegments] (that
         * function excludes future events implicitly, via its bucket ranges never exceeding
         * [nowMs]).
         */
        internal fun currentHealthStatus(events: List<LogEvent>, nowMs: Long): Pair<HealthState, LogEvent?> {
            val lastRelevant = events
                .asSequence()
                .filter { it.timestamp <= nowMs }
                .filter { it.category == LogCategory.RECORDING || it.category == LogCategory.INTERRUPTION }
                .sortedByDescending { it.timestamp }
                .firstOrNull { classifyEvent(it) != null }
            val state = lastRelevant?.let(::classifyEvent) ?: HealthState.NO_DATA
            return state to lastRelevant
        }

        /**
         * Decides which [HealthBanner] variant block 5 shows (code review, patch C) — a dedicated
         * [HealthBanner.NoData] branch, distinct from [HealthBanner.Normal], for a device/session
         * with zero classifiable history (fresh install, or the log rotated the evidence away):
         * that is not the same claim as "confirmed everything is fine".
         */
        internal fun computeHealthBanner(currentState: HealthState, currentEvent: LogEvent?): HealthBanner =
            when (currentState) {
                HealthState.ERROR -> HealthBanner.Error(currentEvent?.message ?: "Recording error")
                HealthState.NO_DATA -> HealthBanner.NoData
                HealthState.RECORDING, HealthState.PAUSED -> HealthBanner.Normal
            }

        /**
         * Whether a successful upload at [lastSuccessfulUploadAtMs] is recent enough (within
         * [graceWindowMs] of [nowMs]) to count as "we're clearly online right now".
         */
        private fun hasRecentSuccess(lastSuccessfulUploadAtMs: Long?, nowMs: Long, graceWindowMs: Long): Boolean =
            lastSuccessfulUploadAtMs != null && nowMs - lastSuccessfulUploadAtMs <= graceWindowMs

        /**
         * Advances the "since when has pending-with-no-recent-success been continuously true"
         * tracker (code review, patch A) — [previous] is the ViewModel's last-remembered value,
         * [HomeViewModel] persists whatever this returns across ticks (a plain instance var; safe
         * because `combine`'s transform lambda runs strictly sequentially, never concurrently with
         * itself).
         *
         * Resets to `null` (the "not currently observing the bad condition" state) the moment
         * there are no pending chunks at all, or a success was logged recently enough — matching
         * [computeIsLikelyOffline]'s own definition of "clearly online" so the two never disagree.
         * Otherwise keeps counting from whenever the bad condition was first seen ([previous] if
         * already set, else starts the clock at [nowMs]).
         */
        internal fun nextFirstPendingObservedAt(
            previous: Long?,
            pendingCount: Int,
            lastSuccessfulUploadAtMs: Long?,
            nowMs: Long,
            graceWindowMs: Long = OFFLINE_INFERENCE_WINDOW_MS
        ): Long? = if (pendingCount == 0 || hasRecentSuccess(lastSuccessfulUploadAtMs, nowMs, graceWindowMs)) {
            null
        } else {
            previous ?: nowMs
        }

        /**
         * Design Notes' offline inference, debounced (code review, patch A — 2 reviewers
         * confirmed the un-debounced version false-positived instantly on a fresh install's very
         * first pending chunk, since `lastSuccessfulUploadAtMs == null` used to short-circuit
         * straight to "offline"). Now `true` only once the pending-with-no-recent-success
         * condition has been continuously observed (see [nextFirstPendingObservedAt]) for longer
         * than [graceWindowMs] — a chunk that's simply queued for its first-ever attempt gets the
         * same grace window as one that's been stuck for a while, instead of an instant false
         * alarm.
         */
        internal fun computeIsLikelyOffline(
            pendingCount: Int,
            lastSuccessfulUploadAtMs: Long?,
            firstPendingObservedAtMs: Long?,
            nowMs: Long,
            graceWindowMs: Long = OFFLINE_INFERENCE_WINDOW_MS
        ): Boolean {
            if (pendingCount == 0 || hasRecentSuccess(lastSuccessfulUploadAtMs, nowMs, graceWindowMs)) return false
            val observedSince = firstPendingObservedAtMs ?: return false
            return nowMs - observedSince > graceWindowMs
        }

        /** Merges [AppEventLogger]'s live feed with [LogFileReader]'s persisted history,
         *  deduped, oldest-overall-last order not required by callers here (only filtering/
         *  classification matters, never display order). Deliberately **not** capped at
         *  [AppEventLogger.MAX_EVENTS] like `LogsViewModel.mergeEvents` — the health strip needs
         *  full 24h coverage, and a busy day (chunks every 2 minutes, several log lines each) can
         *  exceed 200 events well within a day, which would otherwise silently punch NO_DATA gaps
         *  into hours that actually have history. */
        internal fun mergeAllEvents(live: List<LogEvent>, persisted: List<LogEvent>): List<LogEvent> {
            if (persisted.isEmpty()) return live
            val seen = live.toSet()
            return live + persisted.filterNot { it in seen }
        }
    }

    private data class UploadAggregates(
        val pending: PendingUploadsInfo,
        val uploadedLocal: UploadedLocalFilesInfo
    ) {
        companion object {
            val EMPTY = UploadAggregates(PendingUploadsInfo(0, 0L), UploadedLocalFilesInfo(0, 0L))
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val recentMeetings: StateFlow<List<MeetingListItem>> = sessionRepository
        .getAllSessions()
        .flatMapLatest { sessions ->
            val recent = sessions.sortedByDescending { it.startTime }.take(5)
            if (recent.isEmpty()) {
                flowOf(emptyList())
            } else {
                val flows = recent.map { session ->
                    summaryRepository.getSummaryForSession(session.id).map { summary ->
                        MeetingListItem(
                            sessionId = session.id,
                            title = summary?.title?.takeIf { it.isNotBlank() }
                                ?: buildDefaultTitle(session.startTime),
                            startTime = session.startTime,
                            durationMs = session.totalDuration,
                            sessionState = session.state,
                            summaryStatus = summary?.status
                        )
                    }
                }
                combine(flows) { it.toList() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deleteSession(sessionId: Long) {
        viewModelScope.launch {
            chunkRepository.deleteForSession(sessionId)
            transcriptRepository.deleteForSession(sessionId)
            summaryRepository.deleteForSession(sessionId)
            sessionRepository.deleteById(sessionId)
        }
    }

    // ── Live status ──────────────────────────────────────────────────────────
    // getAllSessions() is already ORDER BY startTime DESC (SessionDao), so the first element is
    // always the most recent session regardless of its state.
    private val latestSession = sessionRepository.getAllSessions()
        .map { it.firstOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Log events (live in-memory feed + on-disk history, merged) ─────────────
    private val persistedEvents = MutableStateFlow<List<LogEvent>>(emptyList())

    private val allEvents = combine(appEventLogger.events, persistedEvents) { live, persisted ->
        mergeAllEvents(live, persisted)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), appEventLogger.events.value)

    // ── Upload aggregates (ChunkDao has no reactive query for this — periodic manual refresh) ──
    private val _uploadAggregates = MutableStateFlow(UploadAggregates.EMPTY)

    // Guards against a slower, earlier-started refresh (poll or delete-triggered) overwriting a
    // later one's fresher result (code review, patch B) — cancelling any in-flight refresh before
    // starting a new one means only the most-recently-*started* refresh can ever win.
    private var refreshJob: Job? = null

    private fun refreshUploadAggregates() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            try {
                _uploadAggregates.value = UploadAggregates(
                    pending = homeRepository.getPendingUploadsInfo(),
                    uploadedLocal = homeRepository.getUploadedLocalFilesInfo()
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never let a transient DAO/File-IO failure crash this ViewModel (code review,
                // patch B) — same "log and degrade" contract as every other coroutine in this
                // codebase (AppEventLogger.persist, RecordingWatchdogWorker, etc.). The next poll
                // tick (or the next explicit trigger) retries.
                Log.w(TAG, "Failed to refresh upload aggregates, will retry", e)
            }
        }
    }

    // ── Wall-clock tick, coarse (health strip / banner freshness only) ─────────
    private val nowTick = MutableStateFlow(System.currentTimeMillis())

    // "Since when has pending-with-no-recent-success been continuously observed" — plain instance
    // var, not a Flow: only ever read/written from inside uiState's combine transform, which runs
    // strictly sequentially (see nextFirstPendingObservedAt's KDoc).
    private var firstPendingObservedAtMs: Long? = null

    init {
        viewModelScope.launch {
            persistedEvents.value = logFileReader.readPersistedEvents()
        }
        refreshUploadAggregates()
        viewModelScope.launch {
            while (true) {
                delay(UPLOAD_POLL_INTERVAL_MS)
                refreshUploadAggregates()
            }
        }
        viewModelScope.launch {
            while (true) {
                delay(HEALTH_TICK_INTERVAL_MS)
                nowTick.value = System.currentTimeMillis()
            }
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        latestSession, allEvents, _uploadAggregates, nowTick
    ) { session, events, uploads, now ->
        val liveStatus = when (session?.state) {
            SessionState.RECORDING -> LiveRecordingStatus.Recording(session.startTime)
            SessionState.PAUSED -> LiveRecordingStatus.Paused(session.startTime)
            else -> LiveRecordingStatus.Unknown
        }

        val segments = computeHourlySegments(events, now)
        val (currentState, currentEvent) = currentHealthStatus(events, now)

        val lastSuccessfulUploadAt = events
            .filter { it.category == LogCategory.UPLOAD && it.message.startsWith("Supabase upload succeeded") }
            .maxOfOrNull { it.timestamp }

        firstPendingObservedAtMs = nextFirstPendingObservedAt(
            previous = firstPendingObservedAtMs,
            pendingCount = uploads.pending.count,
            lastSuccessfulUploadAtMs = lastSuccessfulUploadAt,
            nowMs = now
        )
        val isLikelyOffline = computeIsLikelyOffline(
            pendingCount = uploads.pending.count,
            lastSuccessfulUploadAtMs = lastSuccessfulUploadAt,
            firstPendingObservedAtMs = firstPendingObservedAtMs,
            nowMs = now
        )

        HomeUiState(
            liveStatus = liveStatus,
            healthSegments = segments,
            pendingUploadCount = uploads.pending.count,
            pendingUploadBytes = uploads.pending.totalBytes,
            isLikelyOffline = isLikelyOffline,
            uploadedLocalCount = uploads.uploadedLocal.count,
            uploadedLocalBytes = uploads.uploadedLocal.totalBytes,
            healthBanner = computeHealthBanner(currentState, currentEvent)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * "Limpar arquivos já enviados" — deletes local files for chunks already confirmed uploaded
     * (never `PENDING`/`FAILED`/`UPLOADING`/`SILENT`, enforced by [HomeRepository] itself). The UI
     * shows a confirmation dialog with the current count/size before calling this. Logs the
     * outcome (code review, patch G) — every other recording/upload lifecycle event is already
     * logged, and this is the one destructive action this screen can take.
     */
    fun clearUploadedLocalFiles() {
        viewModelScope.launch {
            val deletedCount = try {
                homeRepository.deleteUploadedLocalFiles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear uploaded local files", e)
                null
            }
            if (deletedCount != null) {
                appEventLogger.log(
                    LogCategory.UPLOAD,
                    "Cleared $deletedCount local file(s) already confirmed uploaded"
                )
            }
            refreshUploadAggregates()
        }
    }

    private fun buildDefaultTitle(startTimeMs: Long): String {
        val formatter = SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        return "Recording – ${formatter.format(Date(startTimeMs))}"
    }
}
