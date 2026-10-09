package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Flight records (A5 groundwork): the 비행 단위 identity. The RC device id
 * is install-scoped (app config); a FLIGHT gets a new id every time the
 * operator starts one, and the record pins the mission/bundle/zone
 * revisions it was started with — those pins are immutable historical
 * facts even after the server publishes newer revisions.
 *
 * Records are append-only snapshots; the newest snapshot per flight_id
 * wins at load time (restart restore), and an unfinished flight
 * (state != ENDED) is the [activeFlight].
 */
interface MissionStore {
    /** Idempotent by flight_id (a retry must not fork the flight). */
    fun startFlight(record: ObjectNode)

    /** Append a state snapshot (STARTED -> APPLIED -> ENDED). */
    fun updateFlight(flightId: String, state: String, endedAtUtc: String?)

    fun flight(flightId: String): ObjectNode?

    /** Latest snapshot per flight, insertion order. */
    fun flights(): List<ObjectNode>

    /** Newest flight whose state is not ENDED, or null. */
    fun activeFlight(): ObjectNode?
}
