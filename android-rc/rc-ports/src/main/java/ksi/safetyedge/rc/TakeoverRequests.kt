package ksi.safetyedge.rc

/**
 * Per-request outcome tracking for app-issued manual-takeover requests
 * (pure Kotlin, no Android/MSDK/PSDK).
 *
 * Operation screen contract v1
 * (docs/plans/2026-10-17-rc-operations-screen-contract.md):
 * - A response resolves ONLY the matching request id. It never rewrites a
 *   newer request and never changes the current control authority (which
 *   stays a separate report-driven model, e.g. [ControlAuthorityModel]).
 * - A pending request whose answer is lost becomes UNKNOWN — not ACCEPTED and
 *   not REJECTED. There is no automatic resend: the operator sees
 *   [needsReconcile] and an explicit status query is the only follow-up.
 * - Terminal outcomes never change again; duplicate or unknown-id responses
 *   are recorded as ignored and change nothing.
 * - Time passing and physical RC input are not inputs to this model.
 */
class TakeoverRequestTracker {

    enum class Outcome { PENDING, ACCEPTED, REJECTED, UNKNOWN }

    data class Request(val id: String, val outcome: Outcome) {
        val terminal: Boolean
            get() = outcome == Outcome.ACCEPTED || outcome == Outcome.REJECTED
    }

    data class IgnoredResponse(val id: String, val reason: String)

    private val order = ArrayList<String>()
    private val outcomes = LinkedHashMap<String, Outcome>()

    /** Responses that did not match a resolvable request (audit only). */
    val ignored = ArrayList<IgnoredResponse>()

    /** Opens a new request. Ids must be unique; a reused id is a caller bug. */
    fun begin(id: String): Request {
        require(id.isNotBlank()) { "request id must not be blank" }
        check(!outcomes.containsKey(id)) { "duplicate request id: $id" }
        order.add(id)
        outcomes[id] = Outcome.PENDING
        return Request(id, Outcome.PENDING)
    }

    /**
     * Applies a response to the matching request only (AT-04B: a late response
     * for an older request must not touch newer ones). Returns true when the
     * request reached a terminal outcome.
     */
    fun onResponse(id: String, accepted: Boolean): Boolean {
        val current = outcomes[id]
        if (current == null) {
            ignored.add(IgnoredResponse(id, "unknown request id"))
            return false
        }
        if (current != Outcome.PENDING && current != Outcome.UNKNOWN) {
            ignored.add(IgnoredResponse(id, "already terminal: $current"))
            return false
        }
        outcomes[id] = if (accepted) Outcome.ACCEPTED else Outcome.REJECTED
        return true
    }

    /** Link loss / lost answer: every pending request becomes UNKNOWN. */
    fun onLinkLost() {
        order.forEach { id ->
            if (outcomes[id] == Outcome.PENDING) outcomes[id] = Outcome.UNKNOWN
        }
    }

    fun outcome(id: String): Outcome? = outcomes[id]

    fun all(): List<Request> = order.map { Request(it, outcomes.getValue(it)) }

    fun latest(): Request? = order.lastOrNull()?.let { Request(it, outcomes.getValue(it)) }

    /**
     * True while the outcome is not confirmed (UNKNOWN). The UI must offer an
     * explicit status query — never an automatic resend.
     */
    fun needsReconcile(id: String): Boolean = outcomes[id] == Outcome.UNKNOWN

    /** True while the request awaits any answer. */
    fun awaiting(id: String): Boolean = outcomes[id] == Outcome.PENDING
}
