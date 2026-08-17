package com.example.audiomemo.features.logs.ui

import com.example.audiomemo.core.logging.AppEventLogger
import com.example.audiomemo.core.logging.LogCategory
import com.example.audiomemo.core.logging.LogEvent
import com.example.audiomemo.core.logging.LogFileReader
import io.kotest.core.spec.style.StringSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.nio.file.Files

private fun tempLogsDir(): File = Files.createTempDirectory("logs-viewmodel-test").toFile()

/**
 * Polls [condition] for up to [timeoutMillis], sleeping briefly between attempts. Used only by the
 * real-[LogsViewModel] wiring test below: [LogFileReader.readPersistedEvents] hops onto real
 * `Dispatchers.IO` (not [testDispatcher]), so there's no purely-virtual-time way to know when that
 * hop has landed back on the test dispatcher — [condition] itself calls `advanceUntilIdle()`
 * (available because it's defined inline inside the enclosing `runTest` block, where `this` is the
 * `TestScope`) on every poll so any continuation that *has* landed gets drained immediately.
 */
private fun pollUntil(timeoutMillis: Long = 2_000L, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(20L)
    }
    return condition()
}

/**
 * Covers [LogsViewModel.mergeEvents] (am2-2, FR10 / AC #3: "vejo eventos de antes do restart... +
 * novos eventos ao vivo") as a pure function of two plain lists — no `ViewModel`/`Hilt`/`Context`
 * involved, so most of the merge/ordering/dedup/cap logic is unit-testable from plain-JVM
 * `src/test` without Robolectric (this project has none — see `SupabaseUploadWorkerTest` for the
 * same rationale applied elsewhere).
 *
 * The last test additionally drives a **real** [LogsViewModel] (real [AppEventLogger] +
 * [LogFileReader] against a temp dir) end-to-end, so the actual wiring — `init` →
 * `readPersistedEvents()` → `combine` → `events` — has regression coverage too, not just the
 * extracted pure function.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogsViewModelTest : StringSpec({

    val testDispatcher = StandardTestDispatcher()
    beforeTest { Dispatchers.setMain(testDispatcher) }
    afterTest { Dispatchers.resetMain() }

    val old1 = LogEvent(1_000L, LogCategory.RECORDING, "old-1")
    val old2 = LogEvent(2_000L, LogCategory.RECORDING, "old-2")
    val live1 = LogEvent(3_000L, LogCategory.UPLOAD, "live-1")

    "mergeEvents returns the live list unchanged when there's no persisted history" {
        val merged = LogsViewModel.mergeEvents(live = listOf(live1), persisted = emptyList())

        check(merged == listOf(live1))
    }

    "mergeEvents appends persisted history (oldest file order) after live events, newest-persisted-first" {
        // persisted is oldest-first on disk: old1 happened before old2.
        val merged = LogsViewModel.mergeEvents(live = listOf(live1), persisted = listOf(old1, old2))

        check(merged == listOf(live1, old2, old1)) {
            "expected live events first, then persisted history newest-first, got: $merged"
        }
    }

    "mergeEvents dedupes persisted events already present in the live list" {
        // old2 was logged this session (so it's in `live`) but was also already written to disk
        // and read back by LogFileReader before this session's events fully landed.
        val merged = LogsViewModel.mergeEvents(live = listOf(live1, old2), persisted = listOf(old1, old2))

        check(merged == listOf(live1, old2, old1)) {
            "expected old2 to appear only once (from live, not duplicated from persisted), got: $merged"
        }
    }

    "mergeEvents with no live events yet still surfaces persisted history newest-first" {
        val merged = LogsViewModel.mergeEvents(live = emptyList(), persisted = listOf(old1, old2))

        check(merged == listOf(old2, old1))
    }

    "mergeEvents caps the result at MAX_EVENTS even when persisted history is much larger" {
        val live = listOf(live1)
        // Oldest-first, as LogFileReader returns it — "old-499" is the newest historical entry.
        val persisted = (0 until 500).map { i -> LogEvent(i.toLong(), LogCategory.RECORDING, "old-$i") }

        val merged = LogsViewModel.mergeEvents(live = live, persisted = persisted)

        check(merged.size == AppEventLogger.MAX_EVENTS) {
            "expected the merged list capped at MAX_EVENTS (${AppEventLogger.MAX_EVENTS}), got ${merged.size}"
        }
        check(merged.first() == live1) { "expected the live event to still be first, got ${merged.first()}" }
        check(merged[1].message == "old-499") {
            "expected the newest historical entries to be kept over older ones, got ${merged[1].message}"
        }
        check(merged.none { it.message == "old-0" }) {
            "expected the oldest historical entries to be dropped once the cap was exceeded"
        }
    }

    "LogsViewModel (real instance) merges a persisted event from disk into events on init, then keeps merging new live events" {
        runTest(testDispatcher) {
            val dir = tempLogsDir()
            File(dir, AppEventLogger.LOG_FILE_NAME).writeText("1000 | RECORDING | persisted-on-restart\n")
            val appEventLogger = AppEventLogger(dir)
            val logFileReader = LogFileReader(dir)

            val viewModel = LogsViewModel(appEventLogger, logFileReader)
            // events is stateIn(..., started = WhileSubscribed(...)) — the combine() upstream only
            // runs while there's an active collector, so we need one for events.value to ever update.
            val collectJob = launch { viewModel.events.collect {} }

            val persistedMerged = pollUntil {
                advanceUntilIdle()
                viewModel.events.value.any { it.message == "persisted-on-restart" }
            }
            check(persistedMerged) {
                "expected the persisted event to be merged into events.value, got ${viewModel.events.value}"
            }

            appEventLogger.log(LogCategory.UPLOAD, "live-after-init")
            val liveAlsoMerged = pollUntil {
                advanceUntilIdle()
                viewModel.events.value.any { it.message == "live-after-init" }
            }
            check(liveAlsoMerged) {
                "expected a live event logged after init to also appear alongside the persisted " +
                    "one, got ${viewModel.events.value}"
            }
            check(viewModel.events.value.any { it.message == "persisted-on-restart" }) {
                "expected the persisted event to still be present after a new live event arrived, " +
                    "got ${viewModel.events.value}"
            }

            collectJob.cancel()
        }
    }
})
