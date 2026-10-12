package ksi.safetyedge.rc

/**
 * The ONE canonical segment file naming contract (A9-3 review): the rc-msdk
 * writer names files with [fileName] and the rc-app ingestor parses them
 * with [parseIndex]. It lives in rc-ports so both sides of the seam share a
 * single format instead of duplicating a regex.
 */
object SegmentNames {
    fun fileName(index: Long): String = "seg-%05d.mp4".format(index)

    /** Parses the segment index; null for foreign names or `.tmp` leftovers. */
    fun parseIndex(name: String): Long? =
        Regex("^seg-(\\d+)\\.mp4$").find(name)?.groupValues?.get(1)?.toLongOrNull()
}
