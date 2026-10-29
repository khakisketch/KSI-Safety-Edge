package ksi.rc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Per-event receipt linkage (app closeout support): [EvidenceStore.eventReceipt]
 * exposes the ACTUAL indexed event's flight/boot linkage, receipt clock,
 * and stored policy revision — never a fallback to the latest boot or
 * receipt. Null when the event was never stored.
 */
class EventReceiptTest {
    @Test
    fun receipt_returns_actual_indexed_linkage_not_latest() {
        val root = Files.createTempDirectory("rc-event-receipt")
        val store = FileRcStore(
            root,
            wallClock = ClockQueue(
                listOf("2026-10-20T02:10:00Z", "2026-10-20T02:11:00Z"),
            ),
        )
        val spec = uploadSpec()
        val bootA = Fixture.uuid(12)
        val bootB = Fixture.uuid(13)
        val eventA = Fixture.uuid(301)
        val eventB = Fixture.uuid(302)

        store.storeEvent(
            detectionEventRecord(
                spec.flightId, eventA, Fixture.uuid(1001),
                Fixture.uuid(4001), 1500, emptyList(),
            ),
            bootA,
        )
        val eventBNode = detectionEventRecord(
            spec.flightId, eventB, Fixture.uuid(1002),
            Fixture.uuid(4002), 2500, emptyList(),
        )
        eventBNode.remove("policy_revision_id")
        store.storeEvent(eventBNode, bootB)

        // Each event resolves to ITS OWN boot and receipt clock — the older
        // event never inherits the newest boot.
        assertEquals(
            EventReceipt(
                eventId = eventA,
                flightId = spec.flightId,
                bootId = bootA,
                receivedUtc = "2026-10-20T02:10:00Z",
                policyRevisionId = Fixture.uuid(7000),
            ),
            store.eventReceipt(eventA),
        )
        assertEquals(
            EventReceipt(
                eventId = eventB,
                flightId = spec.flightId,
                bootId = bootB,
                receivedUtc = "2026-10-20T02:11:00Z",
                policyRevisionId = null,
            ),
            store.eventReceipt(eventB),
        )

        // Unknown events resolve to nothing — never the latest.
        assertNull(store.eventReceipt(Fixture.uuid(999)))
    }

    @Test
    fun receipt_survives_restart_with_same_metadata() {
        val root = Files.createTempDirectory("rc-event-receipt-restart")
        val spec = uploadSpec()
        val boot = Fixture.uuid(12)
        val event = Fixture.uuid(301)
        run {
            val store = FileRcStore(
                root,
                wallClock = ClockQueue(listOf("2026-10-20T02:10:00Z")),
            )
            store.storeEvent(
                detectionEventRecord(
                    spec.flightId, event, Fixture.uuid(1001),
                    Fixture.uuid(4001), 1500, emptyList(),
                ),
                boot,
            )
            assertEquals(boot, store.eventReceipt(event)?.bootId)
        }

        // Restart: fresh store over the same directory, same metadata back.
        val restarted = FileRcStore(root)
        assertEquals(
            EventReceipt(
                eventId = event,
                flightId = spec.flightId,
                bootId = boot,
                receivedUtc = "2026-10-20T02:10:00Z",
                policyRevisionId = Fixture.uuid(7000),
            ),
            restarted.eventReceipt(event),
        )
        assertNull(restarted.eventReceipt(Fixture.uuid(999)))
    }
}
