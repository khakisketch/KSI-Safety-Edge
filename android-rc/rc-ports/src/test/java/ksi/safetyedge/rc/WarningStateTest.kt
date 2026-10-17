package ksi.safetyedge.rc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WarningStateTest {

    @Test
    fun ack_save_failure_returns_to_unacknowledged_with_reason() {
        val acks = WarningAcknowledgements()
        acks.acknowledge("w1")
        assertEquals(AckState.SAVING, acks.state("w1"))
        acks.onSaved("w1", ok = false, reason = "저장 실패")
        assertEquals(AckState.UNACKNOWLEDGED, acks.state("w1"))
        assertEquals("저장 실패", acks.lastError("w1"))
    }

    @Test
    fun ack_save_success_records_history_and_clears_error() {
        val acks = WarningAcknowledgements()
        acks.acknowledge("w1")
        acks.onSaved("w1", ok = false, reason = "일시 오류")
        acks.acknowledge("w1")
        acks.onSaved("w1", ok = true)
        assertEquals(AckState.ACKNOWLEDGED, acks.state("w1"))
        assertNull(acks.lastError("w1"))
    }

    @Test
    fun acknowledge_is_ignored_while_saving_or_already_acknowledged() {
        val acks = WarningAcknowledgements()
        acks.acknowledge("w1")
        acks.acknowledge("w1")
        assertEquals(AckState.SAVING, acks.state("w1"))
        acks.onSaved("w1", ok = true)
        acks.acknowledge("w1")
        assertEquals(AckState.ACKNOWLEDGED, acks.state("w1"))
    }

    @Test
    fun save_result_without_saving_is_ignored() {
        val acks = WarningAcknowledgements()
        acks.onSaved("w1", ok = true)
        assertEquals(AckState.UNACKNOWLEDGED, acks.state("w1"))
    }

    @Test
    fun acknowledgement_ledger_is_per_alert() {
        val acks = WarningAcknowledgements()
        acks.acknowledge("w1")
        acks.onSaved("w1", ok = true)
        assertEquals(AckState.ACKNOWLEDGED, acks.state("w1"))
        assertEquals(AckState.UNACKNOWLEDGED, acks.state("w2"))
    }

    @Test
    fun risk_axis_values_are_independent_from_ack_states() {
        // 위험 지속 중에도 확인 이력은 유지될 수 있어야 한다 (축 분리의 구조적 표현).
        val ongoingRisk = RiskState.ONGOING
        val lost = RiskState.OBSERVATION_LOST
        val acks = WarningAcknowledgements()
        acks.acknowledge("w1")
        acks.onSaved("w1", ok = true)
        assertEquals(RiskState.ONGOING, ongoingRisk)
        assertEquals(RiskState.OBSERVATION_LOST, lost)
        assertEquals(AckState.ACKNOWLEDGED, acks.state("w1"))
    }
}
