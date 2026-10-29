package ksi.safetyedge.rc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host tests for the A9-4 SDK-key fact (test route misreported "앱 키
 * 미설치" while the key was injected in the manifest): once the app reads
 * the manifest fact on every route, the diagnosis must never call an
 * installed key missing. The wording itself belongs to Design; this pins
 * the fact contract the fix relies on.
 */
class SdkKeyFactTest {

    @Test
    fun installed_key_never_reads_as_missing() {
        val diagnosis = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = false,
            aircraftConnected = null,
            mopConnected = true,
        )
        val sdk = diagnosis.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertTrue(
            "an installed key must not read as missing: '${sdk.value}'",
            !sdk.value.contains("미설치"),
        )
    }

    @Test
    fun missing_key_stays_unknown_not_disconnected() {
        val diagnosis = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = false,
            sdkInitialised = false,
            aircraftConnected = null,
            mopConnected = null,
        )
        val sdk = diagnosis.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertEquals(ConnectionDiagnosis.State.UNKNOWN, sdk.state)
        assertTrue(sdk.value.contains("미설치"))
        assertTrue(sdk.actionable)
    }

    @Test
    fun registered_key_is_ok() {
        val diagnosis = ConnectionDiagnosis.diagnose(
            sdkKeyPresent = true,
            sdkInitialised = true,
            aircraftConnected = true,
            mopConnected = true,
        )
        val sdk = diagnosis.steps.first { it.stage == ConnectionDiagnosis.Stage.SDK }
        assertEquals(ConnectionDiagnosis.State.OK, sdk.state)
        assertFalse(sdk.actionable)
    }
}
