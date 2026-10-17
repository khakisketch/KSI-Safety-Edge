package ksi.safetyedge.rc

/**
 * SHADOW candidate selection view (operation screen contract v1, AT-02).
 *
 * Selection only opens the candidate detail: it never changes the policy
 * mode, ACTIVE alerts or device state. When the source observation is
 * missing or stale, the view reports that fact instead of pretending the
 * detail is available.
 */
object ShadowCandidateSelection {

    enum class SourceStatus { AVAILABLE, MISSING, STALE }

    data class View(val candidateId: String, val source: SourceStatus)

    fun select(candidateId: String, sourcePresent: Boolean, sourceFresh: Boolean): View {
        require(candidateId.isNotBlank()) { "candidate id must not be blank" }
        return View(
            candidateId = candidateId,
            source = when {
                !sourcePresent -> SourceStatus.MISSING
                !sourceFresh -> SourceStatus.STALE
                else -> SourceStatus.AVAILABLE
            },
        )
    }
}
