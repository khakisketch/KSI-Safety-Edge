package ksi.msdk

/**
 * Device-test-only AutoSensing observation probe (DJI MSDK v5, device gate).
 *
 * DEBUG SOURCE SET ONLY: compiled into debug builds, excluded from release
 * builds (see AUTOSENSING_PROBE.md §6). No production caller exists; there
 * is no UI, entry point, or wiring — the probe is NOT runnable yet.
 *
 * Purpose: after a separately approved entry point and explicit human
 * permission on a device day, observe `addAutoSensingInfoListener`
 * callbacks and the reported enable-state — without changing production
 * flight behaviour or Manifold independence.
 *
 * What this probe NEVER does (by construction, not convention):
 * - never calls `startAutoSensing` / `stopAutoSensing` (no auto-enable);
 * - never calls `selectIntelligentModel` (no model selection);
 * - never calls `init` / `destroy` / `initAutoSensingSource` (no manager
 *   initialization or destruction);
 * - never initialises or registers the DJI SDK (registration is only READ);
 * - never sends aircraft / camera / control commands, never touches video,
 *   never records;
 * - never reads the DJI app key (not even presence), credentials, device
 *   identifiers, or personal content;
 * - never persists, uploads, or logs payload contents;
 * - never wires results into production assessments, contracts, or the MOP.
 *
 * Honesty rules (see AUTOSENSING_PROBE.md):
 * - receive-time ONLY (injected clock). No capture timestamp, no frame id,
 *   no confidence exists on this path, so none is reported.
 * - target index OPAQUE: never read, never joined or ordered by.
 * - rect values UNVERIFIED/OPAQUE: only confirmed-present /
 *   unreadable counts; no numbers retained or scored.
 * - free-form labels NEVER read (not even transiently).
 * - A null payload, null list, or unreadable list is reported as
 *   UNREADABLE with a fixed [ProbeReason] — never as an empty result.
 *   Zero callbacks means "no callbacks observed" (unknown), never "no
 *   detections". A confirmed empty list is a payload fact, not a scene
 *   proof.
 * - Truncation and skipped entries are counted explicitly. Counts are
 *   session-scoped (reset on every claimed [start]).
 * - Failures surface as fixed [ProbeReason] codes only; exception text is
 *   never stored or displayed.
 * - Listener removal is reported as [RemovalState]: CALL_RETURNED means the
 *   SDK removal call returned normally — explicitly NOT device-verified
 *   removal and NOT proof callbacks stopped.
 * - A successful `start()` (state ATTACHED_WAITING/OBSERVED,
 *   [Status.attached] true) means the local SDK add-listener call returned
 *   normally for this session — explicitly NOT proof of device support or
 *   callback operation.
 *
 * Lifecycle and concurrency:
 * - construction attaches nothing; [start] claims one session at a time and
 *   moves through ATTACHING while the vendor call is in flight (a concurrent
 *   second [start] observes ATTACHING, never a stale IDLE/STOPPED, and
 *   issues no vendor call); [stop] detaches. A session is fenced by a
 *   generation counter: late or synchronous callbacks from a previous
 *   session are ignored, never accepted as new.
 * - At most ONE vendor call (attach, detach, or cleanup) is ever in flight,
 *   coordinated by a probe-side operation slot plus the driver's vendor
 *   lock, so two attach operations can never race and cleanup can never
 *   remove a newer session's registration. A [start]() issued while any prior
 *   attach/detach/cleanup is in flight is REFUSED ([ProbeReason.OPERATION_IN_FLIGHT],
 *   no vendor call); while any prior removal or partial registration is
 *   unresolved it is REFUSED ([ProbeReason.ORPHAN_PRESENT], no vendor call).
 * - Locking discipline: the probe's state lock is never held across a vendor
 *   call. Driver gate reads ([AutoSensingDriver.hasUnconfirmed],
 *   [AutoSensingDriver.lastReason]) are lock-free volatile reads and occur
 *   only at points where no probe vendor operation is in flight, so they
 *   cannot wait on an in-flight SDK call. The callback path takes only the
 *   short state lock and never re-enters the driver. No claim is made about
 *   the SDK's own internal blocking behaviour — only about this probe's and
 *   this driver's locks. The driver is single-owner (this probe instance).
 * - A synchronous callback emitted inside attach carries the claiming
 *   generation and is accepted and preserved.
 * - A [stop] issued while an attach is in flight fences the session and
 *   reports removal as IN_FLIGHT (pending — explicitly never confirmed
 *   while the add can still complete); the in-flight [start] flow owns the
 *   single vendor slot and performs the cleanup removal on return, typing
 *   the outcome ([AutoSensingDriver.DetachOutcome]) so a no-op (nothing had
 *   registered) reports NOT_ATTEMPTED rather than a completed removal. A
 *   [stop] issued while a detach is in flight reports the ongoing state
 *   without piling on a second vendor call.
 * - Removal history is per-session: claiming a [start] resets it, and an
 *   attach failure that may have partially registered immediately marks it
 *   CALL_FAILED, so a stale CALL_RETURNED from an earlier session can never
 *   be misread as current cleanup. Recovery from an unconfirmed removal is
 *   an explicit [stop] (which retries removal), never a silent overwrite.
 *   The probe's own record cannot be bypassed by a driver that silently
 *   reports clean.
 * - All state is in-memory and bounded ([maxRetained] recent observations;
 *   driver conversion capped at [maxTargetsPerCallback]); [stop] retains
 *   the window for inspection until the next claimed [start].
 *   Nothing survives the process.
 *
 * Host safety: no DJI type in this class's declaration; all vendor contact
 * goes through [AutoSensingDriver]. Host tests inject fakes; the real
 * driver fail-closes on a host JVM.
 *
 * DEVICE GATE: whether callbacks fire on the actual unit/firmware and what
 * the enable flag means there are unobserved. No callbacks = unknown.
 */
class AutoSensingProbe(
    private val driver: AutoSensingDriver = DjiAutoSensingDriver(),
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val maxRetained: Int = MAX_RETAINED,
    private val maxTargetsPerCallback: Int = MAX_TARGETS_PER_CALLBACK,
) {
    init {
        require(maxRetained in 1..MAX_RETAINED_LIMIT) { "maxRetained out of bounds" }
        require(maxTargetsPerCallback in 1..MAX_TARGETS_LIMIT) { "maxTargetsPerCallback out of bounds" }
    }

    /** Lifecycle of one probe instance. Never implies scene content. */
    enum class State {
        /** Constructed; nothing attached, nothing observed. */
        IDLE,
        /** Attach vendor call in flight; no outcome yet. Never a scene claim. */
        ATTACHING,
        /** Listener attach call returned normally; zero callbacks so far (unknown, not empty). */
        ATTACHED_WAITING,
        /** One or more callbacks observed (see [Status.last]/[Status.recent]). */
        OBSERVED,
        /** Start refused or attach failed (see [Status.reasonCode]). */
        FAILED,
        /** Detached by [stop]; recent window retained for inspection. */
        STOPPED,
    }

    /**
     * One sanitized callback record. Field semantics:
     * - [seq] is the receive-order sequence within the session (1-based),
     *   NOT a frame id; [receiveElapsedMs] is receive-time ONLY (null when
     *   the clock itself was unreadable);
     * - [payloadOk] false means the callback arrived but was undecodable
     *   ([reason] names the fixed cause); decodable fields are null/empty
     *   and [targetCount] is null (unknown) — never zero;
     * - [targetCount]/[totalReported] hold the raw list size as reported
     *   (possibly above the cap); [types] holds at most the negotiated cap
     *   of allowlisted enum names (never label strings);
     * - [rectPresentCount]/[rectUnknownCount] count confirmed-present /
     *   unreadable rects (confirmed-absent is neither);
     * - [skippedEntries] counts null/unreadable entries skipped within the
     *   cap; [truncated] marks cap overflow.
     */
    data class Observation(
        val seq: Long,
        val receiveElapsedMs: Long?,
        val payloadOk: Boolean,
        val reason: ProbeReason?,
        val enableReported: Boolean?,
        val targetCount: Int?,
        val totalReported: Int?,
        val types: List<String>,
        val rectPresentCount: Int,
        val rectUnknownCount: Int,
        val skippedEntries: Int,
        val truncated: Boolean,
    )

    /** Immutable snapshot; obtain via [status]. */
    data class Status(
        val state: State,
        /**
         * True while the local SDK add-listener call has returned normally
         * for this session and no detach has fenced it — explicitly NOT proof
         * of device support or callback operation.
         */
        val attached: Boolean,
        val callbackCount: Long,
        val last: Observation?,
        /** Oldest-first, bounded to [maxRetained]; in-memory only. */
        val recent: List<Observation>,
        /** Fixed failure/unknown code; null when none. Never exception text. */
        val reasonCode: ProbeReason?,
        /** The probe's own removal-call record (never a physical claim). */
        val removalState: RemovalState,
    )

    companion object {
        const val MAX_RETAINED = 32
        const val MAX_TARGETS_PER_CALLBACK = 64
        const val MAX_RETAINED_LIMIT = 128
        const val MAX_TARGETS_LIMIT = 128
        const val UNRECOGNIZED = "UNRECOGNIZED"
        internal val ALLOWED_TYPES: Set<String> =
            setOf("UNKNOWN", "PERSON", "CAR", "BOAT", "CUSTOM")
    }

    private val lock = Any()

    @Volatile
    private var generation: Long = 0L

    @Volatile
    private var state: State = State.IDLE

    @Volatile
    private var attached: Boolean = false

    @Volatile
    private var attaching: Boolean = false

    /**
     * True while exactly one vendor call (attach, detach, or cleanup) is in
     * flight. A new [start] is refused while this holds; a concurrent
     * [stop] fences without issuing a second vendor call.
     */
    @Volatile
    private var opInFlight: Boolean = false

    @Volatile
    private var callbackCount: Long = 0L

    @Volatile
    private var reasonCode: ProbeReason? = null

    @Volatile
    private var removalState: RemovalState = RemovalState.NOT_ATTEMPTED

    private val recent: ArrayDeque<Observation> = ArrayDeque()
    private var currentSink: SessionSink? = null

    /** Generation-bound sink: callbacks from another session are ignored. */
    private inner class SessionSink(val gen: Long) : AutoSensingDriver.Sink {
        override fun onReport(report: AutoSensingDriver.Report) = deliver(gen, report)
    }

    /**
     * Explicit, user-initiated attach. Idempotent while attaching/attached
     * (a concurrent second call observes ATTACHING, never a stale state, and
     * issues no vendor call). REFUSED (FAILED, no vendor call) while any
     * prior attach/detach/cleanup is in flight (OPERATION_IN_FLIGHT) or
     * unresolved — by the probe's own [removalState] or the driver's
     * [AutoSensingDriver.hasUnconfirmed] (ORPHAN_PRESENT). Claiming a session
     * resets the per-session removal history. Never throws for SDK absence —
     * that is FAILED with a fixed code.
     */
    fun start(): Status {
        val sink: SessionSink
        val gen: Long
        synchronized(lock) {
            if (attached || attaching) return statusLocked()
            if (opInFlight) {
                state = State.FAILED
                reasonCode = ProbeReason.OPERATION_IN_FLIGHT
                return statusLocked()
            }
            // Gate reads below are lock-free volatile reads that occur only
            // when no probe vendor operation is in flight, so they cannot
            // wait on an in-flight SDK call (see class locking docs).
            if (removalState == RemovalState.IN_FLIGHT ||
                removalState == RemovalState.CALL_FAILED ||
                driver.hasUnconfirmed()
            ) {
                state = State.FAILED
                reasonCode = ProbeReason.ORPHAN_PRESENT
                return statusLocked()
            }
            generation += 1
            gen = generation
            attaching = true
            opInFlight = true
            state = State.ATTACHING
            reasonCode = null
            removalState = RemovalState.NOT_ATTEMPTED
            sink = SessionSink(gen)
            currentSink = sink
            recent.clear()
            callbackCount = 0L
        }
        // No lock held across the vendor call. A synchronous callback takes
        // only the short lock in deliver() and is accepted (generation
        // matches, attaching is true).
        var ok = false
        try {
            ok = driver.attach(sink, maxTargetsPerCallback)
        } catch (_: LinkageError) {
            ok = false
        } catch (_: Exception) {
            ok = false
        }
        // Short, vendor-free reads for reconciliation (volatile reads only).
        val unconfirmed = driver.hasUnconfirmed()
        val driverReason = driver.lastReason()
        var mismatched = false
        synchronized(lock) {
            if (gen != generation) {
                mismatched = true
            } else if (ok && !unconfirmed) {
                attached = true
                attaching = false
                opInFlight = false
                reasonCode = null
                if (state == State.ATTACHING) state = State.ATTACHED_WAITING
            } else {
                attaching = false
                attached = false
                opInFlight = false
                state = State.FAILED
                if (unconfirmed) {
                    // The add may have partially registered: the removal
                    // record must show unconfirmed AT ONCE, never a stale
                    // CALL_RETURNED from an earlier session.
                    removalState = RemovalState.CALL_FAILED
                    reasonCode = driverReason ?: ProbeReason.ATTACH_UNCONFIRMED
                } else {
                    // Clean failure: no removal attempted in this session.
                    reasonCode = driverReason ?: ProbeReason.ATTACH_FAILED
                }
            }
        }
        if (mismatched) {
            // A stop() fenced this session while attach was in flight (the
            // stop itself issued no vendor call). This flow owns the single
            // vendor slot, so it performs the cleanup removal here, outside
            // the lock, and types the outcome honestly.
            var outcome = AutoSensingDriver.DetachOutcome.CALL_FAILED
            try {
                outcome = driver.detach()
            } catch (_: LinkageError) {
                outcome = AutoSensingDriver.DetachOutcome.CALL_FAILED
            } catch (_: Exception) {
                outcome = AutoSensingDriver.DetachOutcome.CALL_FAILED
            }
            // Vendor-free read for reconciliation.
            val stillOpen = driver.hasUnconfirmed()
            synchronized(lock) {
                opInFlight = false
                when {
                    outcome == AutoSensingDriver.DetachOutcome.CALL_FAILED || stillOpen -> {
                        removalState = RemovalState.CALL_FAILED
                        reasonCode = ProbeReason.DETACH_FAILED
                    }
                    outcome == AutoSensingDriver.DetachOutcome.CALL_RETURNED -> {
                        removalState = RemovalState.CALL_RETURNED
                        reasonCode = null
                    }
                    else -> {
                        // Nothing had registered: no removal call attempted.
                        removalState = RemovalState.NOT_ATTEMPTED
                        reasonCode = null
                    }
                }
                // The fencing stop already set STOPPED; re-assert it: this
                // flow must never revive the fenced session.
                state = State.STOPPED
            }
        }
        return status()
    }

    /**
     * Explicit detach. Fences the session FIRST (generation bump under the
     * short lock, so in-flight and late callbacks die immediately), then —
     * unless another operation owns the single vendor slot — runs exactly
     * one vendor detach outside the lock. A stop issued while an attach is
     * in flight fences and reports removal as IN_FLIGHT (pending, never
     * confirmed — the in-flight start flow performs the typed cleanup on
     * return). A stop issued while a detach is in flight reports the ongoing
     * state without a second vendor call. Safe to call in any state; never
     * throws.
     */
    fun stop(): Status {
        synchronized(lock) {
            if (opInFlight) {
                if (attaching) {
                    // Attach in flight: fence it. This stop issues NO vendor
                    // call; the in-flight start flow owns the slot and will
                    // clean up on return. Pending, never confirmed.
                    generation += 1
                    attached = false
                    attaching = false
                    currentSink = null
                    removalState = RemovalState.IN_FLIGHT
                    reasonCode = null
                    state = State.STOPPED
                }
                // Else a detach/cleanup is already running: report ongoing.
                return statusLocked()
            }
            // Gate reads below are lock-free volatile reads taken while no
            // probe vendor operation is in flight (see class locking docs).
            if (!attached && !attaching &&
                removalState != RemovalState.CALL_FAILED &&
                !driver.hasUnconfirmed()
            ) {
                // Nothing to remove and nothing unconfirmed: no vendor call.
                // Rest in a terminal state but keep any failure code visible.
                if (state == State.IDLE || state == State.FAILED) state = State.STOPPED
                return statusLocked()
            }
            // Exactly one vendor detach for this stop (fresh detach, or an
            // explicit retry after an unconfirmed removal).
            generation += 1
            attached = false
            attaching = false
            currentSink = null
            opInFlight = true
            removalState = RemovalState.IN_FLIGHT
            reasonCode = null
            state = State.STOPPED
        }
        var outcome = AutoSensingDriver.DetachOutcome.CALL_FAILED
        try {
            outcome = driver.detach()
        } catch (_: LinkageError) {
            outcome = AutoSensingDriver.DetachOutcome.CALL_FAILED
        } catch (_: Exception) {
            outcome = AutoSensingDriver.DetachOutcome.CALL_FAILED
        }
        // Vendor-free read for reconciliation.
        val stillOpen = driver.hasUnconfirmed()
        synchronized(lock) {
            opInFlight = false
            when {
                outcome == AutoSensingDriver.DetachOutcome.CALL_FAILED || stillOpen -> {
                    removalState = RemovalState.CALL_FAILED
                    reasonCode = ProbeReason.DETACH_FAILED
                }
                outcome == AutoSensingDriver.DetachOutcome.CALL_RETURNED -> {
                    removalState = RemovalState.CALL_RETURNED
                    reasonCode = null
                }
                else -> {
                    removalState = RemovalState.NOT_ATTEMPTED
                    reasonCode = null
                }
            }
            state = State.STOPPED
        }
        return status()
    }

    /**
     * SDK-callback entry. Accepts only the current generation while the
     * session is attaching or attached; everything else (stray, late,
     * previous-session, or post-stop callbacks) is ignored. Never throws
     * into the SDK. Only the short lock is taken.
     *
     * Internal (not private) so same-module debug tests can drive the fence
     * directly without reflection; never called from production code.
     */
    internal fun deliver(gen: Long, report: AutoSensingDriver.Report) {
        try {
            synchronized(lock) {
                if (gen != generation) return
                if (!attached && !attaching) return
                callbackCount += 1
                val now: Long? = try {
                    clock()
                } catch (_: Exception) {
                    null
                }
                val observation = when (report) {
                    is AutoSensingDriver.Report.Valid -> Observation(
                        seq = callbackCount,
                        receiveElapsedMs = now,
                        payloadOk = true,
                        reason = null,
                        enableReported = report.enable,
                        targetCount = report.totalReported,
                        totalReported = report.totalReported,
                        types = report.views.map { sanitize(it.typeName) },
                        rectPresentCount = report.views.count { it.hasRect == true },
                        rectUnknownCount = report.views.count { it.hasRect == null },
                        skippedEntries = report.skipped,
                        truncated = report.truncated,
                    )
                    is AutoSensingDriver.Report.Unreadable -> Observation(
                        seq = callbackCount,
                        receiveElapsedMs = now,
                        payloadOk = false,
                        reason = report.reason,
                        enableReported = null,
                        targetCount = null,
                        totalReported = null,
                        types = emptyList(),
                        rectPresentCount = 0,
                        rectUnknownCount = 0,
                        skippedEntries = 0,
                        truncated = false,
                    )
                }
                recent.addLast(observation)
                while (recent.size > maxRetained) recent.removeFirst()
                state = State.OBSERVED
            }
        } catch (_: Exception) {
            // Never propagate into the SDK.
        }
    }

    private fun sanitize(name: String?): String =
        if (name != null && ALLOWED_TYPES.contains(name)) name else UNRECOGNIZED

    /** Immutable snapshot copy. */
    fun status(): Status = synchronized(lock) { statusLocked() }

    private fun statusLocked(): Status = Status(
        state = state,
        attached = attached,
        callbackCount = callbackCount,
        last = recent.lastOrNull(),
        recent = recent.toList(),
        reasonCode = reasonCode,
        removalState = removalState,
    )
}
