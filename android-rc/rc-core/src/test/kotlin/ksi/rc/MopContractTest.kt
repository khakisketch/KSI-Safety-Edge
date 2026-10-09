package ksi.rc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Cross-language contract goldens: every expected value below was produced
 * by the REAL Python modules (`libs/ksi_contracts` digest + RFC 8785, and
 * `harness/src/ksi_harness/mop.py` identifiers/envelope). A mismatch means
 * the Kotlin RC would compute different ids/digests than the server and
 * harness — the exact cross-language gap audit F9 warned about.
 */
class MopContractTest {
    private val mapper = ObjectMapper()

    @Test
    fun namespace_matches_python() {
        assertEquals(
            "bcf20008-2c56-56a4-a896-d918b7df6364",
            Mop.NAMESPACE.toString(),
        )
    }

    @Test
    fun msg_id_matches_python() {
        assertEquals(
            "f922155e-bfb5-58e5-a4ad-3006fcf1d921",
            Mop.newMsgId(
                "edge", "flt-1", "boot-1", "1",
                "CONTROL_RELIABLE", "MISSION_PREPARED", "3",
            ),
        )
    }

    @Test
    fun canonical_digests_match_python() {
        val cases = mapOf(
            """{"a":1}""" to
                "015abd7f5cc57a2dd94b7590f04ad8084273905ee33ec5cebeae62276a97f862",
            """{"a":[1.5,false,null,"x"],"b":1}""" to
                "579b623880179dc6e38e7e2cbbfada8e0a04cf74c18634df24e2be594dac59eb",
            """{"nested":{"y":"text","z":true},"scores":[0.91,0.87,1.0]}""" to
                "032cefa668fb4fa8a8fee14f20500a93dfa0331dadb16f08016f970115371940",
            """{"big":9007199254740991,"float":0.1,"int_neg":-42}""" to
                "4fd7201e5d9ec688aea6508baba8a284c782e3d35a91f1334f02b0b83ecac00b",
        )
        for ((json, expected) in cases) {
            assertEquals(
                expected,
                CanonicalJson.digestSha256(mapper.readTree(json)),
                "digest parity for $json",
            )
        }
    }

    @Test
    fun envelope_field_and_digest_parity() {
        val payload = mapper.createObjectNode()
        payload.put("bundle_sha256", "ab".repeat(32))
        payload.put("supported", true)
        payload.set<com.fasterxml.jackson.databind.JsonNode>(
            "exclusions", mapper.createArrayNode(),
        )
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId(
                "edge", "flt-1", "boot-1", "1",
                "CONTROL_RELIABLE", "MISSION_PREPARED", "3",
            ),
            channel = "CONTROL_RELIABLE",
            type = "MISSION_PREPARED",
            flightId = "flt-1",
            edgeBootId = "boot-1",
            seq = null,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 1_000_000L,
        )
        assertEquals(1, env.get("protocol_version").asInt())
        assertEquals("f922155e-bfb5-58e5-a4ad-3006fcf1d921", env.get("msg_id").asText())
        assertTrue(env.get("seq").isNull)
        assertTrue(env.get("ack_of").isNull)
        assertEquals(
            "c2a10bcfdbc43fdf3a10aab3897897c88344b2024b3e97d488e055a9fad6f13a",
            env.get("payload_sha256").asText(),
        )
        // Round-trip through verifyEnvelope (the receiver-side gate).
        val (verified, verifiedPayload) =
            Mop.verifyEnvelope(mapper.writeValueAsString(env))
        assertEquals(env.get("msg_id").asText(), verified.get("msg_id").asText())
        assertEquals(true, verifiedPayload.get("supported").asBoolean())
    }

    @Test
    fun tampered_payload_is_rejected() {
        val payload = mapper.createObjectNode()
        payload.put("a", 1)
        val env = Mop.makeEnvelope(
            msgId = Mop.newMsgId("test"),
            channel = "EVENT_RELIABLE",
            type = "OBSERVATION",
            flightId = "flt",
            edgeBootId = "boot",
            seq = 1,
            ackOf = null,
            payload = payload,
            sentMonotonicNs = 1L,
        ) as ObjectNode
        (env.get("payload") as ObjectNode).put("a", 2) // tamper after signing
        assertFailsWith<MopError> {
            Mop.verifyEnvelope(mapper.writeValueAsString(env))
        }
    }

    @Test
    fun unknown_channel_is_rejected() {
        assertFailsWith<MopError> {
            Mop.makeEnvelope(
                msgId = Mop.newMsgId("x"),
                channel = "NOT_A_CHANNEL",
                type = "OBSERVATION",
                flightId = "flt",
                edgeBootId = null,
                seq = null,
                ackOf = null,
                payload = mapper.createObjectNode(),
                sentMonotonicNs = 0L,
            )
        }
    }
}
