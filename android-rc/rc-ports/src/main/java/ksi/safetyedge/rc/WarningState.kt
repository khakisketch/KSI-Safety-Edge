package ksi.safetyedge.rc

/**
 * Warning-state model (operation screen contract v1 §3, AT-01).
 *
 * Three axes are independent and are NEVER collapsed into one
 * "발생 → 확인 → 해제" sequence: [PolicyMode], [RiskState], and the
 * acknowledgement ledger ([WarningAcknowledgements]). Acknowledging an alert
 * never resolves the risk; risk resolution never erases the acknowledgement;
 * observation loss is neither confirmation nor resolution.
 */
enum class PolicyMode { OFF, SHADOW, ACTIVE }

enum class RiskState {
    /** Evidence currently supports the risk (위험 지속). */
    ONGOING,

    /** Evidence supports that the condition ended (해소 확인). */
    RESOLVED_CONFIRMED,

    /** Observation is gone; the risk is neither confirmed nor resolved. */
    OBSERVATION_LOST,
}

enum class AckState { UNACKNOWLEDGED, SAVING, ACKNOWLEDGED }

/**
 * Per-alert acknowledgement ledger (operator review history only).
 *
 * Transitions: UNACKNOWLEDGED → SAVING → ACKNOWLEDGED on save success;
 * save failure returns to UNACKNOWLEDGED with the reason kept for display.
 * The risk axis is not an input here and cannot be changed by these calls.
 */
class WarningAcknowledgements {

    private val states = LinkedHashMap<String, AckState>()
    private val lastErrors = HashMap<String, String>()

    fun state(id: String): AckState = states[id] ?: AckState.UNACKNOWLEDGED

    /** Starts an ack save. Only valid from UNACKNOWLEDGED. */
    fun acknowledge(id: String) {
        if (state(id) != AckState.UNACKNOWLEDGED) return
        states[id] = AckState.SAVING
        lastErrors.remove(id)
    }

    /**
     * Save result. Failure puts the alert back to UNACKNOWLEDGED (reason kept
     * for display); success records ACKNOWLEDGED. Acknowledged history is
     * independent from the risk state.
     */
    fun onSaved(id: String, ok: Boolean, reason: String? = null) {
        if (state(id) != AckState.SAVING) return
        if (ok) {
            states[id] = AckState.ACKNOWLEDGED
        } else {
            states[id] = AckState.UNACKNOWLEDGED
            reason?.let { lastErrors[id] = it }
        }
    }

    fun lastError(id: String): String? = lastErrors[id]
}
