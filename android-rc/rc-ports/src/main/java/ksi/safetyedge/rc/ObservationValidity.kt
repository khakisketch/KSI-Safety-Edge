package ksi.safetyedge.rc

/**
 * Observation validity stages (operation screen contract v1 §4, AT-06).
 *
 * Three stages are validated independently — a single "valid" flag is
 * forbidden:
 * - the detection FACT exists when the raw observation and its provenance
 *   exist;
 * - the live video BOX needs correspondence between the displayed frame and
 *   the detection frame;
 * - the MAP position / zone relation needs temporal, attitude, lens and
 *   ground reconciliation PLUS a confirmed applied zone revision.
 *
 * A held stage never deletes the detection fact: "탐지는 가능하지만 지도
 * 위치는 미확인" must be expressible.
 */
object ObservationValidity {

    enum class Stage { SHOWN, HELD }

    data class Validity(
        val detection: Stage,
        val liveOverlay: Stage,
        val mapRelation: Stage,
    ) {
        val anyHeld: Boolean
            get() = detection == Stage.HELD ||
                liveOverlay == Stage.HELD ||
                mapRelation == Stage.HELD
    }

    fun evaluate(
        hasDetection: Boolean,
        hasSource: Boolean,
        frameCorrespondence: Boolean,
        geoConditionsMet: Boolean,
        appliedZoneConfirmed: Boolean,
    ): Validity {
        val detection = if (hasDetection && hasSource) Stage.SHOWN else Stage.HELD
        val overlay = if (detection == Stage.SHOWN && frameCorrespondence) Stage.SHOWN else Stage.HELD
        val map = if (detection == Stage.SHOWN && geoConditionsMet && appliedZoneConfirmed) {
            Stage.SHOWN
        } else {
            Stage.HELD
        }
        return Validity(detection = detection, liveOverlay = overlay, mapRelation = map)
    }
}
