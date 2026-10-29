package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the A9-4 init-state defect (same key-class flaw as the
 * SDK-key fact): on the TEST_TCP route `link.sdkInitialised` is always
 * false, so a registered SDK misread as "초기화 실패". The fix ORs the
 * link value with the real static fact ([PlatformLinks.sdkInitialised])
 * and downgrades a started-but-unresolved init to 확인 중 instead of a
 * confident failure ([withSdkPendingState]).
 *
 * Host JVM has no SDK runtime: both PlatformLinks facts read false, and
 * the pure override matrix below pins the copy contract the detail
 * screen depends on.
 */
class SdkInitFactTest {

    @Test
    fun no_sdk_runtime_means_no_init_fact() {
        assertFalse(
            "no SDK runtime on host: never claim initialised",
            PlatformLinks.sdkInitialised(),
        )
        assertFalse(
            "no SDK runtime on host: nothing can be pending",
            PlatformLinks.sdkInitPending(FakeFlightTelemetrySource()),
        )
    }

    @Test
    fun pending_init_overrides_failure_with_checking() {
        val base = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = false,
            aircraftConnected = null,
            mopConnected = true,
        )
        val checking = base.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertEquals(ConnectionDiagnosis.State.DISCONNECTED, checking.state)

        val overridden = withSdkPendingState(
            diagnosis = base,
            keyPresent = true,
            initialised = false,
            pending = true,
            pendingValue = "등록 확인 중",
            pendingRemedy = "SDK 등록이 진행 중입니다. 잠시 후 다시 확인하세요.",
        )
        val sdk = overridden.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertEquals(
            "started-but-unresolved init is 확인 중, never a confident 실패",
            ConnectionDiagnosis.State.UNKNOWN,
            sdk.state,
        )
        assertEquals("등록 확인 중", sdk.value)
        assertTrue(sdk.remedy.contains("진행 중"))
        // Other steps pass through untouched.
        assertEquals(
            base.steps.first { it.stage == ConnectionDiagnosis.Stage.MOP },
            overridden.steps.first { it.stage == ConnectionDiagnosis.Stage.MOP },
        )
    }

    @Test
    fun no_override_without_key_or_when_initialised_or_settled() {
        val base = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = false,
            aircraftConnected = null,
            mopConnected = true,
        )
        // Already initialised: pass through.
        assertEquals(
            base,
            withSdkPendingState(base, true, true, true, "등록 확인 중", "remedy"),
        )
        // No key: the missing-key step stands.
        assertEquals(
            base,
            withSdkPendingState(base, false, false, true, "등록 확인 중", "remedy"),
        )
        // Not pending (terminal outcome known): pass through.
        assertEquals(
            base,
            withSdkPendingState(base, true, false, false, "등록 확인 중", "remedy"),
        )
    }

    @Test
    fun pending_copy_is_korean_and_names_no_english() {
        val overridden = withSdkPendingState(
            diagnosis = ConnectionDiagnosis.diagnose(
                sdkKeyPresent = true,
                sdkInitialised = false,
                aircraftConnected = null,
                mopConnected = null,
            ),
            keyPresent = true,
            initialised = false,
            pending = true,
            pendingValue = "등록 확인 중",
            pendingRemedy = "SDK 등록이 진행 중입니다. 잠시 후 다시 확인하세요.",
        )
        val sdk = overridden.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertFalse("no English leak: '${sdk.value}'", sdk.value.any { it in 'A'..'Z' || it in 'a'..'z' })
        assertTrue(sdk.actionable)
        assertEquals(
            "확인 중 is unknown, never a connected claim",
            ConnectionDiagnosis.State.UNKNOWN,
            sdk.state,
        )
    }
}
