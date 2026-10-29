package ksi.safetyedge.rc

/** An explicit record request must never silently open a different record. */
object RecordSelection {
    fun resolve(requested: String?, availableIds: List<String>): String? =
        if (requested == null) availableIds.firstOrNull()
        else requested.takeIf { it in availableIds }

    fun shouldOpen(requested: String?, selected: String?, detailAvailable: Boolean, recordsVisible: Boolean): Boolean =
        requested != null && requested == selected && detailAvailable && recordsVisible
}
