package ksi.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit B contract §3 parsing rules: every field optional, absent means
 * unknown (never false/0), unparsable values yield UNKNOWN + a reason and
 * never throw.
 */
class RuntimeStatusTest {
    private fun payload(): com.fasterxml.jackson.databind.node.ObjectNode =
        Mop.json.createObjectNode()

    @Test
    fun all_fields_absent_is_unknown_not_false_or_zero() {
        val status = EdgeRuntimeStatus.parse(payload(), 123L)
        assertEquals(EdgeRuntimeState.UNKNOWN, status.state)
        assertNull(status.runtimeEpoch)
        assertNull(status.configApplied)
        assertNull(status.effectiveConfigHash)
        assertNull(status.processedFrameCount)
        assertNull(status.lastProcessedFrameAgeMs)
        assertTrue(status.degradedReasons.isEmpty())
        assertTrue(status.inputState.isEmpty())
        assertEquals(123L, status.observedAtMonotonicNs)
        // Absent is not false/0: the boxed nulls must stay null.
        assertFalse(status.configApplied == false)
        assertFalse(status.processedFrameCount == 0L)
    }

    @Test
    fun full_running_payload_parses() {
        val node = payload()
        node.put("runtime_state", "RUNNING")
        node.put("runtime_epoch", "epoch-7")
        node.put("config_applied", true)
        node.put("effective_config_hash", "abc123")
        node.put("processed_frame_count", 42)
        node.put("last_processed_frame_age_ms", 33)
        val status = EdgeRuntimeStatus.parse(node, 9L)
        assertEquals(EdgeRuntimeState.RUNNING, status.state)
        assertEquals("epoch-7", status.runtimeEpoch)
        assertEquals(true, status.configApplied)
        assertEquals("abc123", status.effectiveConfigHash)
        assertEquals(42L, status.processedFrameCount)
        assertEquals(33L, status.lastProcessedFrameAgeMs)
        assertTrue(status.parseIssues.isEmpty())
        assertEquals(true, status.frameProcessingObserved)
    }

    @Test
    fun degraded_with_reasons_and_input_state() {
        val node = payload()
        node.put("runtime_state", "DEGRADED")
        node.put("processed_frame_count", 5)
        val reasons = node.putArray("degraded_reasons")
        reasons.add("video_input_stale")
        reasons.add("telemetry_absent")
        val inputs = node.putObject("input_state")
        inputs.put("video", "stale")
        inputs.put("telemetry", "absent")
        inputs.put("engine", "ok")
        val status = EdgeRuntimeStatus.parse(node, 1L)
        assertEquals(EdgeRuntimeState.DEGRADED, status.state)
        assertEquals(listOf("video_input_stale", "telemetry_absent"), status.degradedReasons)
        assertEquals(
            mapOf("video" to "stale", "telemetry" to "absent", "engine" to "ok"),
            status.inputState,
        )
        assertEquals(true, status.frameProcessingObserved)
    }

    @Test
    fun unparsable_values_are_unknown_with_reasons_not_exceptions() {
        val node = payload()
        node.put("runtime_state", 42)
        node.put("runtime_epoch", 7)
        node.put("config_applied", "yes")
        node.put("effective_config_hash", 9)
        node.put("processed_frame_count", "lots")
        node.put("last_processed_frame_age_ms", "recently")
        node.put("degraded_reasons", "video_input_lost")
        node.put("input_state", listOf("video").toString())
        val status = EdgeRuntimeStatus.parse(node, 1L)
        assertEquals(EdgeRuntimeState.UNKNOWN, status.state)
        assertNull(status.runtimeEpoch)
        assertNull(status.configApplied)
        assertNull(status.effectiveConfigHash)
        assertNull(status.processedFrameCount)
        assertNull(status.lastProcessedFrameAgeMs)
        assertTrue(status.degradedReasons.isEmpty())
        assertTrue(status.inputState.isEmpty())
        assertTrue(status.parseIssues.isNotEmpty())
    }

    @Test
    fun unknown_state_string_and_mixed_entries() {
        val node = payload()
        node.put("runtime_state", "FLYING")
        node.put("processed_frame_count", -3)
        val reasons = node.putArray("degraded_reasons")
        reasons.add("video_input_lost")
        reasons.add(7)
        val inputs = node.putObject("input_state")
        inputs.put("video", "ok")
        inputs.put("engine", 5)
        val status = EdgeRuntimeStatus.parse(node, 1L)
        assertEquals(EdgeRuntimeState.UNKNOWN, status.state)
        assertNull(status.processedFrameCount, "negative counts are corrupt, not zero")
        assertEquals(listOf("video_input_lost"), status.degradedReasons)
        assertEquals(mapOf("video" to "ok"), status.inputState)
        assertTrue(status.parseIssues.size >= 3)
    }

    @Test
    fun zero_frames_is_not_processing_but_not_an_error() {
        val node = payload()
        node.put("runtime_state", "STARTING")
        node.put("processed_frame_count", 0)
        val status = EdgeRuntimeStatus.parse(node, 1L)
        assertEquals(EdgeRuntimeState.STARTING, status.state)
        assertEquals(0L, status.processedFrameCount)
        assertEquals(false, status.frameProcessingObserved)
        assertTrue(status.parseIssues.isEmpty())
    }
}
