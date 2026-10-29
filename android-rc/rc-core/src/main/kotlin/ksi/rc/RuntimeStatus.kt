package ksi.rc

import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Edge runtime states (Unit B of the 2026-10-21 edge-operation contract
 * §2). Reported by the Edge in MISSION_STATUS, displayed by the RC.
 *
 * The legacy `running` bool keeps its old meaning only — "start requested
 * and not stopped" — and is NOT execution evidence. The real runtime state
 * comes from these fields; the RC never says "analyzing" on `running=true`
 * alone.
 */
enum class EdgeRuntimeState {
    REQUEST_ACCEPTED,
    CONFIG_APPLIED,
    STARTING,
    RUNNING,
    DEGRADED,
    STOPPED,
    UNKNOWN,
}

/**
 * Parsed MISSION_STATUS runtime facts (contract §3: every field optional,
 * absent means unknown — never false/0).
 *
 * Parsing never throws: a missing field yields [EdgeRuntimeState.UNKNOWN]
 * for the state and null/absent for the rest; a present-but-unparsable
 * value yields UNKNOWN/null plus a human-readable entry in [parseIssues],
 * never an exception that kills the link.
 */
data class EdgeRuntimeStatus(
    val state: EdgeRuntimeState,
    /** Edge process generation; the RC compares it across reconnects. */
    val runtimeEpoch: String?,
    /** Bundle digest actually installed in the engine, or null. */
    val configApplied: Boolean?,
    /** Installed settings hash, or null when unreported. */
    val effectiveConfigHash: String?,
    /** Frames actually processed in this runtime generation, or null. */
    val processedFrameCount: Long?,
    /** Age of the last processed frame, or null when unreported. */
    val lastProcessedFrameAgeMs: Long?,
    /** Degradation cause codes (empty when unreported, NOT when healthy). */
    val degradedReasons: List<String>,
    /** Per-input health (`video`/`telemetry`/`engine` -> ok|stale|...). */
    val inputState: Map<String, String>,
    val observedAtMonotonicNs: Long,
    /** Present-but-unparsable values encountered while parsing. */
    val parseIssues: List<String>,
) {
    /**
     * True only when the Edge reported actual frame processing
     * ([EdgeRuntimeState.RUNNING] or [EdgeRuntimeState.DEGRADED] with a
     * positive frame count). Null/absent counts are NOT processing.
     */
    val frameProcessingObserved: Boolean?
        get() = processedFrameCount?.let { it > 0L }

    companion object {
        fun unknown(nowNs: Long, reason: String = "no runtime fields reported"): EdgeRuntimeStatus =
            EdgeRuntimeStatus(
                state = EdgeRuntimeState.UNKNOWN,
                runtimeEpoch = null,
                configApplied = null,
                effectiveConfigHash = null,
                processedFrameCount = null,
                lastProcessedFrameAgeMs = null,
                degradedReasons = emptyList(),
                inputState = emptyMap(),
                observedAtMonotonicNs = nowNs,
                parseIssues = listOf(reason),
            )

        fun parse(payload: ObjectNode, nowNs: Long): EdgeRuntimeStatus {
            val issues = mutableListOf<String>()
            try {
                val stateNode = payload.get("runtime_state")
                val state = when {
                    stateNode == null || stateNode.isNull -> {
                        issues.add("runtime_state absent: state UNKNOWN")
                        EdgeRuntimeState.UNKNOWN
                    }
                    stateNode.isTextual -> {
                        EdgeRuntimeState.values()
                            .firstOrNull { it.name == stateNode.asText() }
                            ?: run {
                                issues.add(
                                    "unparsable runtime_state " +
                                        "'${stateNode.asText()}': state UNKNOWN",
                                )
                                EdgeRuntimeState.UNKNOWN
                            }
                    }
                    else -> {
                        issues.add("unparsable runtime_state (not a string): state UNKNOWN")
                        EdgeRuntimeState.UNKNOWN
                    }
                }

                val epochNode = payload.get("runtime_epoch")
                val runtimeEpoch = when {
                    epochNode == null || epochNode.isNull -> null
                    epochNode.isTextual -> epochNode.asText()
                    else -> {
                        issues.add("unparsable runtime_epoch (not a string): unknown")
                        null
                    }
                }

                val configNode = payload.get("config_applied")
                val configApplied = when {
                    configNode == null || configNode.isNull -> null
                    configNode.isBoolean -> configNode.asBoolean()
                    else -> {
                        issues.add("unparsable config_applied (not a bool): unknown")
                        null
                    }
                }

                val hashNode = payload.get("effective_config_hash")
                val effectiveConfigHash = when {
                    hashNode == null || hashNode.isNull -> null
                    hashNode.isTextual -> hashNode.asText()
                    else -> {
                        issues.add("unparsable effective_config_hash (not a string): unknown")
                        null
                    }
                }

                val framesNode = payload.get("processed_frame_count")
                val processedFrameCount = when {
                    framesNode == null || framesNode.isNull -> null
                    framesNode.isNumber && framesNode.asLong() >= 0L -> framesNode.asLong()
                    else -> {
                        issues.add("unparsable processed_frame_count: unknown")
                        null
                    }
                }

                val ageNode = payload.get("last_processed_frame_age_ms")
                val lastProcessedFrameAgeMs = when {
                    ageNode == null || ageNode.isNull -> null
                    ageNode.isNumber && ageNode.asLong() >= 0L -> ageNode.asLong()
                    else -> {
                        issues.add("unparsable last_processed_frame_age_ms: unknown")
                        null
                    }
                }

                val reasonsNode = payload.get("degraded_reasons")
                val degradedReasons = when {
                    reasonsNode == null || reasonsNode.isNull -> emptyList()
                    reasonsNode.isArray -> {
                        val kept = mutableListOf<String>()
                        for (entry in reasonsNode) {
                            if (entry.isTextual) kept.add(entry.asText())
                            else issues.add("skipped non-string degraded_reasons entry")
                        }
                        kept
                    }
                    else -> {
                        issues.add("unparsable degraded_reasons (not an array): unknown")
                        emptyList()
                    }
                }

                val inputNode = payload.get("input_state")
                val inputState = when {
                    inputNode == null || inputNode.isNull -> emptyMap()
                    inputNode.isObject -> {
                        val kept = linkedMapOf<String, String>()
                        for (field in inputNode.fields()) {
                            val value = field.value
                            if (value.isTextual) kept[field.key] = value.asText()
                            else issues.add("skipped non-string input_state entry '${field.key}'")
                        }
                        kept
                    }
                    else -> {
                        issues.add("unparsable input_state (not an object): unknown")
                        emptyMap()
                    }
                }

                return EdgeRuntimeStatus(
                    state = state,
                    runtimeEpoch = runtimeEpoch,
                    configApplied = configApplied,
                    effectiveConfigHash = effectiveConfigHash,
                    processedFrameCount = processedFrameCount,
                    lastProcessedFrameAgeMs = lastProcessedFrameAgeMs,
                    degradedReasons = degradedReasons,
                    inputState = inputState,
                    observedAtMonotonicNs = nowNs,
                    parseIssues = issues.toList(),
                )
            } catch (exc: Exception) {
                return unknown(nowNs, "runtime parse failure (${exc.message}): state UNKNOWN")
            }
        }
    }
}
