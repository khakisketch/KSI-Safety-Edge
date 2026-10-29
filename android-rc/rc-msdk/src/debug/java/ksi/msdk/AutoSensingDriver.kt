package ksi.msdk

/**
 * Fixed, bounded failure vocabulary for the AutoSensing probe.
 *
 * These codes are the ONLY failure information that ever leaves the probe:
 * exception messages (which may carry paths, identifiers, or other free
 * text) are caught at the boundary and mapped to a code, never stored or
 * surfaced. [display] returns fixed strings with no external content.
 */
enum class ProbeReason {
    SDK_REGISTRATION_UNCONFIRMED,
    MANAGER_UNAVAILABLE,
    ATTACH_FAILED,
    ATTACH_UNCONFIRMED,
    ATTACH_DENIED,
    DETACH_FAILED,
    ORPHAN_PRESENT,
    OPERATION_IN_FLIGHT,
    PAYLOAD_NULL,
    TARGETS_UNREADABLE,
    CLOCK_UNREADABLE,
}

/** Fixed display text per code. Contains no exception, payload, or path text. */
fun ProbeReason.display(): String = when (this) {
    // Deliberately NOT "SDK not registered": the registration read cannot
    // distinguish "absent" from "unreadable", so no definitive claim is made.
    ProbeReason.SDK_REGISTRATION_UNCONFIRMED ->
        "SDK registration not confirmed — unknown (not proof of unregistered)"
    ProbeReason.MANAGER_UNAVAILABLE -> "intelligent-flight manager unavailable — unknown"
    ProbeReason.ATTACH_FAILED -> "listener attach failed — unknown"
    ProbeReason.ATTACH_UNCONFIRMED ->
        "attach may have partially registered — removal unconfirmed, start refused until cleared"
    ProbeReason.ATTACH_DENIED ->
        "attach denied: a registration is already held — start refused"
    ProbeReason.DETACH_FAILED -> "listener removal unconfirmed — may still be registered"
    ProbeReason.ORPHAN_PRESENT ->
        "prior removal/registration unconfirmed — start refused until a detach confirms removal"
    ProbeReason.OPERATION_IN_FLIGHT ->
        "another attach/detach/cleanup is in flight — start refused until it settles"
    ProbeReason.PAYLOAD_NULL -> "callback payload null — unknown, not empty"
    ProbeReason.TARGETS_UNREADABLE -> "target list unreadable — unknown, not empty"
    ProbeReason.CLOCK_UNREADABLE -> "receive clock unreadable"
}

/**
 * What the probe itself knows about SDK-listener removal.
 *
 * This describes ONLY the probe's own removal-call record — never physical
 * facts. In particular, [CALL_RETURNED] means the DJI
 * `removeAutoSensingInfoListener` call returned normally (DJI declares it
 * void); that is explicitly NOT device-verified removal and NOT proof that
 * callback delivery stopped. On-device behaviour is unknown and unclaimed.
 */
enum class RemovalState {
    /** No removal vendor call has been attempted in this lifecycle. */
    NOT_ATTEMPTED,
    /**
     * A removal/cleanup vendor call is running, or an attach is in flight
     * that may still register (cleanup pending). Nothing about removal is
     * established while this holds.
     */
    IN_FLIGHT,
    /**
     * The last removal vendor call returned normally. See the type docs:
     * call-returned is not proof of on-device removal.
     */
    CALL_RETURNED,
    /** The last removal attempt threw, failed, or remains unconfirmed. */
    CALL_FAILED,
}

/**
 * Attach/detach seam for the DJI AutoSensing listener.
 *
 * The seam carries ONLY DJI-free snapshot values so [AutoSensingProbe] —
 * and host tests — run without the SDK runtime. The production
 * implementation ([DjiAutoSensingDriver]) converts inside its own method
 * bodies and is the only place that references DJI types.
 *
 * What the seam deliberately does NOT carry (opaque by construction):
 * target index (identity/join key), rect numbers (unverified geometry), and
 * free-form label strings (personal content / open vocabulary). The driver
 * must never call `getLabel()` or `getTargetIndex()`, and must never read
 * rect fields — presence only, tri-state.
 */
interface AutoSensingDriver {

    /**
     * Minimal per-target view the probe is allowed to see.
     * [typeName] is the raw `AutoSensingTargetType` enum name, or null when
     * unreadable; the probe allowlists it. [hasRect] is null-vs-present
     * ONLY: true = confirmed present, false = confirmed absent (null rect),
     * null = unreadable. No geometry is ever carried.
     */
    data class TargetView(
        val typeName: String?,
        val hasRect: Boolean?,
    )

    /** One SDK callback, converted at the boundary into honest states. */
    sealed interface Report {
        /**
         * A decodable payload. [totalReported] is the raw list size as
         * reported (it may exceed [views] when truncated); [views] holds at
         * most the negotiated cap; [skipped] counts null/unreadable entries
         * skipped within the cap; [truncated] marks cap overflow.
         * [enable] is null when the flag itself was unreadable.
         */
        data class Valid(
            val enable: Boolean?,
            val totalReported: Int,
            val views: List<TargetView>,
            val skipped: Int,
            val truncated: Boolean,
        ) : Report

        /** The callback arrived but the payload (or list) could not be read. */
        data class Unreadable(val reason: ProbeReason) : Report
    }

    /**
     * Outcome of a [detach] call, distinguishing "no removal call was
     * attempted" from "the removal call returned normally". This exists
     * precisely so a no-op (nothing held) can never be mistaken for a
     * completed removal.
     */
    enum class DetachOutcome {
        /** Nothing was held, so no SDK removal call was attempted. */
        NOTHING_HELD,
        /**
         * The SDK removal call returned normally. DJI declares removal void:
         * this is NOT proof of on-device removal.
         */
        CALL_RETURNED,
        /** The removal call threw, returned failure, or remains unconfirmed. */
        CALL_FAILED,
    }

    /**
     * Register the listener. True means the local SDK add-listener call
     * returned normally — explicitly NOT proof of device support or callback
     * operation. False (never a throw) when unavailable, refused, or a
     * registration is already held (single-registration driver: an
     * additional attach is denied, never shares or overwrites the held
     * listener). [maxTargets] bounds conversion work; the driver clamps it
     * defensively. A throw that may have partially registered MUST retain
     * the reference and report [hasUnconfirmed] = true (never a silent
     * half-registration).
     */
    fun attach(sink: Sink, maxTargets: Int): Boolean

    /**
     * Attempt removal, returning the typed [DetachOutcome]. A failed
     * removal MUST retain the reference for retry and report
     * [hasUnconfirmed] = true.
     */
    fun detach(): DetachOutcome

    /** True while a possibly-still-registered listener is retained. */
    fun hasUnconfirmed(): Boolean = false

    /** Last failure as a fixed code; null when none/unknown. */
    fun lastReason(): ProbeReason? = null

    /** Callback entry the driver invokes on an SDK thread. Must never throw. */
    interface Sink {
        fun onReport(report: Report)
    }
}

/** Pure bound for conversion work: how many entries to materialize. */
internal fun boundTake(total: Int, cap: Int): Pair<Int, Boolean> {
    val safeCap = cap.coerceAtLeast(1)
    val take = total.coerceAtLeast(0).coerceAtMost(safeCap)
    return take to (total > take)
}

/** Defensive clamp so callers cannot defeat conversion limits. */
internal fun effectiveCap(requested: Int): Int = requested.coerceIn(1, 256)

/**
 * Production DJI MSDK v5 driver for [AutoSensingProbe] (device gate).
 *
 * DEBUG SOURCE SET ONLY: compiled into debug builds, excluded from release
 * builds (see AUTOSENSING_PROBE.md §6). No production caller exists; the
 * probe is the sole owner of its driver instance.
 *
 * Scope — the ONLY SDK calls this driver ever makes:
 * - a guarded read of the SDK registration flag (no bridge instance, no
 *   side effects, no key read, no init/register). An unreadable flag and a
 *   negative flag are both reported as unconfirmed — never as proof of an
 *   unregistered SDK;
 * - `IntelligentFlightManager.getInstance()`;
 * - `addAutoSensingInfoListener` / `removeAutoSensingInfoListener`.
 *
 * Explicitly NOT called: `init`, `destroy`, `initAutoSensingSource`,
 * `startAutoSensing`, `stopAutoSensing`, `selectIntelligentModel`, mission
 * managers, `registerApp`, any `KeyManager.setValue`/`performAction`, any
 * command, video, or upload API.
 *
 * Concurrency: a private vendor lock serializes whole SDK calls, so
 * concurrent attach/detach attempts cannot overwrite each other's listener
 * candidates. The gate reads ([hasUnconfirmed], [lastReason]) are lock-free
 * volatile reads, so the probe never waits on the vendor lock while holding
 * its state lock. The only nesting is vendor-lock → probe-state-lock inside
 * a synchronous SDK callback (the sink never re-enters this driver); the
 * reverse order never occurs. The SDK's own internal blocking behaviour is
 * unknown and unclaimed — this guarantee covers only this driver's locks.
 * An add exception keeps the candidate as unconfirmed at once; a failed
 * removal keeps the reference for retry; an attach issued while a
 * registration is already held is denied (false + ATTACH_DENIED), never
 * shares or replaces the held listener.
 *
 * Host safety: DJI types appear ONLY inside method bodies, so loading this
 * class never resolves the SDK runtime. On a host JVM the registration read
 * degrades into SDK_REGISTRATION_UNCONFIRMED via the LinkageError / Exception
 * catch — never a throw, never a message, never a claim.
 */
class DjiAutoSensingDriver : AutoSensingDriver {

    /** Serializes whole vendor calls; never taken by the callback path. */
    private val vendorLock = Any()

    @Volatile
    private var registered: Any? = null

    @Volatile
    private var addConfirmed: Boolean = false

    @Volatile
    private var removalFailed: Boolean = false

    @Volatile
    private var reason: ProbeReason? = null

    override fun lastReason(): ProbeReason? = reason

    /** Lock-free: volatile field reads only, never waits on [vendorLock]. */
    override fun hasUnconfirmed(): Boolean {
        val held = registered
        return held != null && (!addConfirmed || removalFailed)
    }

    override fun attach(sink: AutoSensingDriver.Sink, maxTargets: Int): Boolean =
        synchronized(vendorLock) {
            if (registered != null) {
                // Single-registration driver: deny, never share/overwrite.
                reason = ProbeReason.ATTACH_DENIED
                return false
            }
            // Guarded direct read (not the collapsing helper): an unreadable
            // flag and a negative flag are both "unconfirmed", never proof.
            val registrationConfirmed = try {
                dji.v5.manager.SDKManager.getInstance().isRegistered
            } catch (_: LinkageError) {
                reason = ProbeReason.SDK_REGISTRATION_UNCONFIRMED
                return false
            } catch (_: Exception) {
                reason = ProbeReason.SDK_REGISTRATION_UNCONFIRMED
                return false
            }
            if (!registrationConfirmed) {
                reason = ProbeReason.SDK_REGISTRATION_UNCONFIRMED
                return false
            }
            val cap = effectiveCap(maxTargets)
            val manager: dji.v5.manager.interfaces.IIntelligentFlightManager = try {
                dji.v5.manager.intelligent.IntelligentFlightManager.getInstance()
                    ?: run {
                        reason = ProbeReason.MANAGER_UNAVAILABLE
                        return false
                    }
            } catch (_: LinkageError) {
                reason = ProbeReason.MANAGER_UNAVAILABLE
                return false
            } catch (_: Exception) {
                reason = ProbeReason.MANAGER_UNAVAILABLE
                return false
            }
            // NOTE (Kotlin 2.0.21/K2): an explicit
            // `object : AutoSensingInfoListener { override fun ... }` fails here
            // with "overrides nothing", while this SAM-constructor form compiles
            // against the same 5.18.0 stubs (cross-checked with javac). Keep.
            val added: dji.v5.manager.intelligent.AutoSensingInfoListener =
                dji.v5.manager.intelligent.AutoSensingInfoListener { info ->
                    val report = try {
                        convert(info, cap)
                    } catch (_: LinkageError) {
                        AutoSensingDriver.Report.Unreadable(ProbeReason.TARGETS_UNREADABLE)
                    } catch (_: Exception) {
                        AutoSensingDriver.Report.Unreadable(ProbeReason.TARGETS_UNREADABLE)
                    }
                    try {
                        sink.onReport(report)
                    } catch (_: LinkageError) {
                        // Never propagate into the SDK.
                    } catch (_: Exception) {
                        // Never propagate into the SDK.
                    }
                }
            // The vendor lock is held across this call. A synchronous SDK
            // callback reaches only the sink above, which never re-enters
            // this driver — but the SDK's own internals are unclaimed.
            try {
                manager.addAutoSensingInfoListener(added)
            } catch (_: LinkageError) {
                // Ambiguous: the listener MAY be registered. Retain at once.
                registered = added
                addConfirmed = false
                reason = ProbeReason.ATTACH_UNCONFIRMED
                return false
            } catch (_: Exception) {
                registered = added
                addConfirmed = false
                reason = ProbeReason.ATTACH_UNCONFIRMED
                return false
            }
            registered = added
            addConfirmed = true
            removalFailed = false
            reason = null
            true
        }

    override fun detach(): AutoSensingDriver.DetachOutcome = synchronized(vendorLock) {
        val current = registered ?: return AutoSensingDriver.DetachOutcome.NOTHING_HELD
        val manager: dji.v5.manager.interfaces.IIntelligentFlightManager = try {
            dji.v5.manager.intelligent.IntelligentFlightManager.getInstance()
                ?: run {
                    reason = ProbeReason.DETACH_FAILED
                    removalFailed = true
                    return AutoSensingDriver.DetachOutcome.CALL_FAILED
                }
        } catch (_: LinkageError) {
            reason = ProbeReason.DETACH_FAILED
            removalFailed = true
            return AutoSensingDriver.DetachOutcome.CALL_FAILED
        } catch (_: Exception) {
            reason = ProbeReason.DETACH_FAILED
            removalFailed = true
            return AutoSensingDriver.DetachOutcome.CALL_FAILED
        }
        // The vendor lock is held across this call (same reasoning as
        // above). On failure the reference is RETAINED — never cleared —
        // so removal stays unconfirmed and retryable.
        try {
            manager.removeAutoSensingInfoListener(
                current as dji.v5.manager.intelligent.AutoSensingInfoListener,
            )
        } catch (_: LinkageError) {
            reason = ProbeReason.DETACH_FAILED
            removalFailed = true
            return AutoSensingDriver.DetachOutcome.CALL_FAILED
        } catch (_: Exception) {
            reason = ProbeReason.DETACH_FAILED
            removalFailed = true
            return AutoSensingDriver.DetachOutcome.CALL_FAILED
        }
        if (registered === current) {
            registered = null
            addConfirmed = false
        }
        removalFailed = false
        reason = null
        AutoSensingDriver.DetachOutcome.CALL_RETURNED
    }

    /**
     * Boundary conversion. Reads ONLY the enable flag, the list reference,
     * each entry's enum type name, and rect null-vs-present — bounded to
     * [cap] entries (never materializes the whole list). NEVER calls
     * `getLabel()` or `getTargetIndex()`; NEVER reads rect fields.
     * Every failure mode maps to [AutoSensingDriver.Report.Unreadable] with
     * a fixed code — an unreadable payload is never reported as empty.
     */
    private fun convert(
        info: dji.v5.manager.intelligent.AutoSensingInfo?,
        cap: Int,
    ): AutoSensingDriver.Report {
        if (info == null) return AutoSensingDriver.Report.Unreadable(ProbeReason.PAYLOAD_NULL)
        val raw: List<dji.v5.manager.intelligent.AutoSensingTarget?> = try {
            info.targets
                ?: return AutoSensingDriver.Report.Unreadable(ProbeReason.TARGETS_UNREADABLE)
        } catch (_: Exception) {
            return AutoSensingDriver.Report.Unreadable(ProbeReason.TARGETS_UNREADABLE)
        }
        val total: Int = try {
            raw.size
        } catch (_: Exception) {
            return AutoSensingDriver.Report.Unreadable(ProbeReason.TARGETS_UNREADABLE)
        }
        val take: Int
        val truncated: Boolean
        boundTake(total, cap).let { (n, t) -> take = n; truncated = t }
        val views = ArrayList<AutoSensingDriver.TargetView>(take)
        var skipped = 0
        // Bounded loop: at most `take` SDK touches, regardless of list size.
        for (i in 0 until take) {
            val target: dji.v5.manager.intelligent.AutoSensingTarget? = try {
                raw[i]
            } catch (_: Exception) {
                null
            }
            if (target == null) {
                skipped += 1
                continue
            }
            val typeName: String? = try {
                target.targetType?.name
            } catch (_: Exception) {
                null
            }
            // Tri-state: true = confirmed present, false = confirmed null,
            // null = unreadable. An exception NEVER becomes false.
            val hasRect: Boolean? = try {
                if (target.rect != null) true else false
            } catch (_: Exception) {
                null
            }
            views.add(AutoSensingDriver.TargetView(typeName = typeName, hasRect = hasRect))
        }
        val enable: Boolean? = try {
            info.isAutoSensingEnable
        } catch (_: Exception) {
            null
        }
        return AutoSensingDriver.Report.Valid(
            enable = enable,
            totalReported = total.coerceAtLeast(0),
            views = views,
            skipped = skipped,
            truncated = truncated,
        )
    }
}
