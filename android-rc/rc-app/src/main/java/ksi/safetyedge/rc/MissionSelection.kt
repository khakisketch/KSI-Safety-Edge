package ksi.safetyedge.rc

/** UI selection rules; no requests or storage writes. IDs alone never establish apply proof. */
object MissionSelection {
    fun retainBundle(siteBundleIds: List<String>, selected: String?, active: String?): String? =
        active?.takeIf { it in siteBundleIds } ?: selected?.takeIf { it in siteBundleIds }

    fun canChoose(bundleId: String, selectedActive: String?, preparing: String?): Boolean =
        (selectedActive == null || selectedActive == bundleId) &&
            (preparing == null || preparing == bundleId)

    fun matches(selectedId: String?, selectedSha: String?, controllerId: String?, controllerSha: String?): Boolean =
        selectedId != null && selectedSha != null && selectedId == controllerId && selectedSha == controllerSha
}
