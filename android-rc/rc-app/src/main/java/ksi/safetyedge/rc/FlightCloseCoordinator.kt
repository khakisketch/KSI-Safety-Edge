package ksi.safetyedge.rc

import java.io.File

/**
 * STOPPED-aware flight close-out coordinator (contract §6 closeout).
 *
 * Pure ordering + gating over injected steps: the production close path
 * ([MainActivity.finishFlight]) and the host tests invoke THIS coordinator,
 * so the verified order is the shipped order. A clean close (record end,
 * upload enqueue, new-session unlock) happens ONLY after an Edge-confirmed
 * STOPPED — or when no Edge session was ever opened. Anything else
 * finalizes the RC-local recording immediately but leaves the flight
 * record open (new sessions stay blocked) with the unresolved reason
 * durably recorded, until a later STOPPED completes it.
 *
 * No step result is invented: failures retain everything for retry (no
 * end, no enqueue, flight stays active).
 */
class FlightCloseCoordinator(
    val maxReconcileAttempts: Int = 10,
    val reconcileIntervalMs: Long = 1000L,
    val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    enum class Mode {
        /** Wait (bounded) for STOPPED, else fall back to recording-only. */
        FULL,

        /** Finalize RC recording now; the Edge session stays unresolved. */
        RECORDING_ONLY,
    }

    /** One finalized recorder file set handed to ingest. */
    data class IngestSummary(
        val ingested: Int,
        val total: Int,
        val problems: List<String>,
    )

    /** Clean-close completion data: summary plus the persisted outcome. */
    data class FinalizeResult(val note: String, val outcome: String)

    interface Steps {
        /** Sends STOP when a session is open; assesses the current state. */
        fun requestStop(): FlightCloseout.StopRequest

        /**
         * One reconcile round (STATUS_QUERY + pump); true when the Edge now
         * reports STOPPED for our stop.
         */
        fun reconcileOnce(): Boolean

        /** Finalizes the recorder; throws on failure (close aborts). */
        fun finalizeRecorder(): List<File>

        /** Ingests finalized files; never throws away local bytes. */
        fun ingest(files: List<File>): IngestSummary

        /**
         * Sticky recorder failure observed at finalization (true = a write
         * failure is known for this flight's capture; false/null = none
         * known — never guessed). Carried into the close-out record and the
         * verdict basis so a failed capture can never read as clean video.
         */
        fun recorderWriteFailed(): Boolean?

        /** Persists the UNRESOLVED close-out; returns the summary note. */
        fun persistUnresolved(reason: String, ingest: IngestSummary, videoFailed: Boolean?): String

        /**
         * Recorder results in, then verdict + record end + bundle snapshot
         * artifact; returns the completion, or null when another path already
         * ended the flight (idempotent completion — not a failure) or when
         * the lifecycle gate still refuses. A DRAFT completion (explicit
         * never-started) needs no upload.
         */
        fun finalizeAndEnd(ingest: IngestSummary, videoFailed: Boolean?): FinalizeResult?

        /**
         * Why a clean finalize would currently refuse (null when it would
         * proceed). Lets the coordinator report the standby honestly
         * instead of mislabeling it "already ended".
         */
        fun closeBlockedReason(): String?

        /** Schedules the upload (only after a clean finalize). */
        fun enqueueUpload()
    }

    sealed interface Outcome {
        /** Clean close: record ended, upload enqueued, new sessions unlock. */
        data class CleanClosed(val note: String) : Outcome

        /**
         * RC recording finalized but the Edge session is unresolved: the
         * flight record stays open (new sessions blocked) until a later
         * STOPPED completes it. No upload enqueued.
         */
        data class RecordingFinalizedUnresolved(val reason: String, val note: String) : Outcome

        /** A step failed: nothing ended, nothing enqueued, retry retained. */
        data class Failed(val reason: String) : Outcome
    }

    fun close(mode: Mode, steps: Steps): Outcome {
        val stop = try {
            steps.requestStop()
        } catch (exc: Exception) {
            return Outcome.Failed("STOP 요청 실패: ${exc.message}")
        }
        // No open session, or STOPPED already confirmed: finalize now.
        if (!stop.sessionOpen || stop.assessment.clean) {
            return finalizeClean(steps, stop)
        }
        if (mode == Mode.RECORDING_ONLY) {
            return finalizeRecordingOnly(
                steps, "RC 기록만 마감 선택 · " + stop.assessment.note,
            )
        }
        // Pending: bounded reconciliation off the UI thread. No auto-
        // pretend — exhaustion falls back to recording-only.
        var stopped = false
        for (i in 0 until maxReconcileAttempts) {
            if (steps.reconcileOnce()) {
                stopped = true
                break
            }
            sleep(reconcileIntervalMs)
        }
        if (stopped) return finalizeClean(steps, stop)
        return finalizeRecordingOnly(
            steps,
            "STOPPED 미확인(재조정 ${maxReconcileAttempts}회 초과) — 미확인 종료",
        )
    }

    /**
     * Recorder finish → ingest → close-out persist → record end → enqueue.
     * Any step failure retains everything for retry (no end, no enqueue).
     */
    private fun finalizeClean(steps: Steps, stop: FlightCloseout.StopRequest): Outcome {
        val ingest = finalizeRecording(steps) ?: return finalizeRecordingFailed()
        val videoFailed = try {
            steps.recorderWriteFailed()
        } catch (_: Exception) {
            null
        }
        val summary = try {
            steps.finalizeAndEnd(ingest, videoFailed)
        } catch (exc: Exception) {
            return Outcome.Failed("마감 실패(로컬 보존, 재시도 가능): ${exc.message}")
        }
        if (summary != null) {
            if (summary.outcome == CloseoutRecord.DRAFT) {
                // Explicit never-started draft: record ended, nothing to
                // upload — scheduling a sync would only imply work.
                return Outcome.CleanClosed(summary.note + " · 동기화 불필요(기록 없음)")
            }
            try {
                steps.enqueueUpload()
            } catch (exc: Exception) {
                return Outcome.Failed("동기화 예약 실패(마감 기록 보존, 수동 재시도 가능): ${exc.message}")
            }
            return Outcome.CleanClosed(summary.note)
        }
        val blocked = try {
            steps.closeBlockedReason()
        } catch (_: Exception) {
            null
        }
        return if (blocked != null) {
            Outcome.RecordingFinalizedUnresolved(blocked, "마감 보류 · $blocked")
        } else if (stop.sessionOpen && !stop.assessment.clean) {
            Outcome.Failed("마감 거부됨 · Edge 세션 미확인 상태(재시도 가능)")
        } else {
            Outcome.CleanClosed("이미 마감됨(동시 완료)")
        }
    }

    private fun finalizeRecordingOnly(steps: Steps, reason: String): Outcome {
        val ingest = finalizeRecording(steps) ?: return finalizeRecordingFailed()
        val videoFailed = try {
            steps.recorderWriteFailed()
        } catch (_: Exception) {
            null
        }
        val note = try {
            steps.persistUnresolved(reason, ingest, videoFailed)
        } catch (exc: Exception) {
            return Outcome.Failed("미확인 종료 기록 실패(로컬 보존, 재시도 가능): ${exc.message}")
        }
        // Deliberately no record end and no enqueue: the flight stays
        // active (new sessions blocked) until a later STOPPED completes it.
        return Outcome.RecordingFinalizedUnresolved(reason, note)
    }

    private fun finalizeRecording(steps: Steps): IngestSummary? {
        val files = try {
            steps.finalizeRecorder()
        } catch (_: Exception) {
            return null
        }
        return try {
            steps.ingest(files)
        } catch (_: Exception) {
            null
        }
    }

    private fun finalizeRecordingFailed(): Outcome =
        Outcome.Failed("녹화 마무리/인제스트 실패(로컬 보존, 재시도 가능)")
}
