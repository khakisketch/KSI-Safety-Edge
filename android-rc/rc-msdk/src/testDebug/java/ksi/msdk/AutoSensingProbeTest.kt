package ksi.msdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Host tests for the debug-only [AutoSensingProbe].
 *
 * No DJI SDK runtime is loaded: a [FakeDriver] stands in for the MSDK and a
 * fake clock replaces `SystemClock`. Tests touching the real
 * [DjiAutoSensingDriver] assert fail-closed behaviour on a host JVM only
 * (registration gate first, fixed codes, no throws) — mirroring the
 * [SdkAircraftConnectionTest] precedent. Real callbacks, enable-state
 * meaning, and device support stay DEVICE GATE facts; host tests simulate
 * only and prove lifecycle, fencing, serialization, sanitization,
 * bounding, and codes.
 */
class AutoSensingProbeTest {

    private class FakeDriver(
        var attachResult: Boolean = true,
        var detachResult: Boolean = true,
        var throwOnAttach: Throwable? = null,
        var throwOnDetach: Throwable? = null,
        /** Simulate a throw that may have partially registered. */
        var orphanOnFailedAttach: Boolean = false,
        /** Report emitted synchronously from inside attach(). */
        var syncReport: AutoSensingDriver.Report? = null,
        var attachGate: CountDownLatch? = null,
        var enteredAttach: CountDownLatch? = null,
        var detachGate: CountDownLatch? = null,
        var enteredDetach: CountDownLatch? = null,
    ) : AutoSensingDriver {
        var attachCalls = 0
            private set
        var detachCalls = 0
            private set
        var lastMaxTargets: Int = -1
            private set
        var sink: AutoSensingDriver.Sink? = null
            private set
        /** Last sink removed by detach (to prove old generations stay dead). */
        var lastDetachedSink: AutoSensingDriver.Sink? = null
            private set
        private var orphan: Boolean = false
        var reason: ProbeReason? = null

        override fun attach(sink: AutoSensingDriver.Sink, maxTargets: Int): Boolean {
            attachCalls += 1
            lastMaxTargets = maxTargets
            enteredAttach?.countDown()
            attachGate?.await(5, TimeUnit.SECONDS)
            // Single-registration denial, like the production driver: never
            // share or overwrite a held listener.
            if (this.sink != null) {
                reason = ProbeReason.ATTACH_DENIED
                return false
            }
            throwOnAttach?.let {
                if (orphanOnFailedAttach) orphan = true
                reason = if (orphanOnFailedAttach) ProbeReason.ATTACH_UNCONFIRMED else ProbeReason.ATTACH_FAILED
                throw it
            }
            if (!attachResult) {
                if (orphanOnFailedAttach) orphan = true
                reason = ProbeReason.ATTACH_FAILED
                return false
            }
            this.sink = sink
            syncReport?.let { sink.onReport(it) }
            return true
        }

        override fun detach(): AutoSensingDriver.DetachOutcome {
            detachCalls += 1
            enteredDetach?.countDown()
            detachGate?.await(5, TimeUnit.SECONDS)
            throwOnDetach?.let { throw it }
            if (!detachResult) return AutoSensingDriver.DetachOutcome.CALL_FAILED
            // Mirror the production driver: nothing held means no removal
            // call was attempted — a no-op, never a completed removal.
            // An orphan flag means something may be held: proceed.
            if (sink == null && !orphan) return AutoSensingDriver.DetachOutcome.NOTHING_HELD
            lastDetachedSink = sink
            sink = null
            orphan = false
            return AutoSensingDriver.DetachOutcome.CALL_RETURNED
        }

        override fun hasUnconfirmed(): Boolean = orphan
        override fun lastReason(): ProbeReason? = reason

        fun emit(report: AutoSensingDriver.Report) {
            sink?.onReport(report)
        }
    }

    private var now: Long = 1_000L

    private fun probe(
        driver: FakeDriver,
        maxRetained: Int = AutoSensingProbe.MAX_RETAINED,
        maxTargets: Int = AutoSensingProbe.MAX_TARGETS_PER_CALLBACK,
    ) = AutoSensingProbe(driver = driver, clock = { now }, maxRetained = maxRetained, maxTargetsPerCallback = maxTargets)

    private fun view(type: String?, rect: Boolean? = false) =
        AutoSensingDriver.TargetView(typeName = type, hasRect = rect)

    private fun valid(
        enable: Boolean? = true,
        total: Int = 1,
        views: List<AutoSensingDriver.TargetView> = listOf(view("PERSON", true)),
        skipped: Int = 0,
        truncated: Boolean = false,
    ) = AutoSensingDriver.Report.Valid(
        enable = enable,
        totalReported = total,
        views = views,
        skipped = skipped,
        truncated = truncated,
    )

    // -- lifecycle: explicit, user-initiated, idempotent --------------------

    @Test
    fun construction_attaches_nothing() {
        val driver = FakeDriver()
        probe(driver)
        assertEquals(0, driver.attachCalls)
    }

    @Test
    fun start_attaches_once_with_configured_cap_and_second_start_is_a_noop() {
        val driver = FakeDriver()
        val p = probe(driver, maxTargets = 16)
        val first = p.start()
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, first.state)
        assertTrue(first.attached)
        assertEquals(1, driver.attachCalls)
        assertEquals(16, driver.lastMaxTargets)
        val second = p.start()
        assertEquals(1, driver.attachCalls)
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, second.state)
    }

    @Test
    fun failed_attach_is_failed_never_attached_with_fixed_code() {
        val driver = FakeDriver(attachResult = false)
        val p = probe(driver)
        val status = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, status.state)
        assertFalse(status.attached)
        assertEquals(0L, status.callbackCount)
        assertNull(status.last)
        assertEquals(ProbeReason.ATTACH_FAILED, status.reasonCode)
        assertEquals(RemovalState.NOT_ATTEMPTED, status.removalState)
    }

    @Test
    fun failed_then_successful_start_begins_a_fresh_session() {
        val driver = FakeDriver(attachResult = false)
        val p = probe(driver)
        assertEquals(AutoSensingProbe.State.FAILED, p.start().state)
        driver.attachResult = true
        val status = p.start()
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, status.state)
        assertNull(status.reasonCode)
    }

    @Test
    fun constructor_rejects_caps_that_would_defeat_limits() {
        val driver = FakeDriver()
        for (bad in listOf(0, -1, 129, Int.MAX_VALUE)) {
            var threw = false
            try {
                AutoSensingProbe(driver = driver, maxRetained = bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("maxRetained=$bad must be rejected", threw)
            threw = false
            try {
                AutoSensingProbe(driver = driver, maxTargetsPerCallback = bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue("maxTargetsPerCallback=$bad must be rejected", threw)
        }
        assertEquals(0, driver.attachCalls)
    }

    @Test
    fun stray_callbacks_while_detached_are_ignored() {
        val driver = FakeDriver()
        val p = probe(driver)
        // Generation 0 never belongs to a live session: ignored.
        p.deliver(0L, valid())
        assertEquals(AutoSensingProbe.State.IDLE, p.status().state)
        assertEquals(0L, p.status().callbackCount)
        p.start()
        p.stop()
        driver.emit(valid())
        assertEquals(AutoSensingProbe.State.STOPPED, p.status().state)
        assertEquals(0L, p.status().callbackCount)
    }

    @Test
    fun stop_without_start_is_safe_and_detach_after_stop_is_once() {
        val driver = FakeDriver()
        val p = probe(driver)
        val idle = p.stop()
        assertEquals(AutoSensingProbe.State.STOPPED, idle.state)
        assertEquals(RemovalState.NOT_ATTEMPTED, idle.removalState)
        assertEquals(0, driver.detachCalls)
        p.start()
        p.stop()
        p.stop()
        assertEquals(1, driver.detachCalls)
    }

    @Test
    fun stop_after_failed_start_rests_to_stopped_keeping_the_failure_code_visible() {
        val driver = FakeDriver(attachResult = false)
        val p = probe(driver)
        p.start()
        val status = p.stop()
        assertEquals(AutoSensingProbe.State.STOPPED, status.state)
        // The stop itself was a no-op (nothing was attached): no removal
        // call was attempted, and the start failure stays visible.
        assertEquals(RemovalState.NOT_ATTEMPTED, status.removalState)
        assertEquals(ProbeReason.ATTACH_FAILED, status.reasonCode)
        assertEquals(0, driver.detachCalls)
    }

    @Test
    fun detach_failure_is_reported_not_hidden() {
        val driver = FakeDriver(detachResult = false)
        val p = probe(driver)
        p.start()
        val status = p.stop()
        assertEquals(AutoSensingProbe.State.STOPPED, status.state)
        assertFalse(status.attached)
        assertEquals(RemovalState.CALL_FAILED, status.removalState)
        assertEquals(ProbeReason.DETACH_FAILED, status.reasonCode)
    }

    @Test
    fun failed_detach_blocks_restart_until_explicit_successful_cleanup() {
        // The fake reports NO orphan here; the probe's own CALL_FAILED
        // record must still block a restart (no bypass via a silent driver).
        val driver = FakeDriver(detachResult = false)
        val p = probe(driver)
        p.start()
        assertEquals(RemovalState.CALL_FAILED, p.stop().removalState)
        assertFalse(driver.hasUnconfirmed())
        val refused = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, refused.state)
        assertEquals(ProbeReason.ORPHAN_PRESENT, refused.reasonCode)
        assertEquals("blocked start must not touch the vendor", 1, driver.attachCalls)
        // Explicit retry still failing stays blocked.
        assertEquals(RemovalState.CALL_FAILED, p.stop().removalState)
        assertEquals(2, driver.detachCalls)
        assertEquals(ProbeReason.ORPHAN_PRESENT, p.start().reasonCode)
        // Explicit successful cleanup clears the way.
        driver.detachResult = true
        val cleared = p.stop()
        assertEquals(RemovalState.CALL_RETURNED, cleared.removalState)
        assertNull(cleared.reasonCode)
        assertEquals(3, driver.detachCalls)
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
        assertEquals(2, driver.attachCalls)
    }

    @Test
    fun restart_after_stop_begins_a_fresh_session() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        driver.emit(valid())
        assertEquals(1, p.status().recent.size)
        val stopped = p.stop()
        assertEquals(RemovalState.CALL_RETURNED, stopped.removalState)
        p.start()
        val status = p.status()
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, status.state)
        assertEquals(0L, status.callbackCount)
        assertTrue(status.recent.isEmpty())
    }

    @Test
    fun removal_state_distinguishes_no_call_in_flight_and_call_returned() {
        val driver = FakeDriver()
        val p = probe(driver)
        // IDLE stop: no vendor call attempted.
        assertEquals(RemovalState.NOT_ATTEMPTED, p.stop().removalState)
        assertEquals(0, driver.detachCalls)
        p.start()
        val stopped = p.stop()
        assertEquals(RemovalState.CALL_RETURNED, stopped.removalState)
        assertNull(stopped.reasonCode)
        assertEquals(1, driver.detachCalls)
    }

    // -- serialization: schedules A and B -----------------------------------

    @Test
    fun scheduleA_stop_then_racing_start_during_attach_is_refused_and_cleaned_up() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val driver = FakeDriver(attachGate = release, enteredAttach = entered)
        val p = probe(driver)
        // A: start blocks inside the vendor attach.
        val starter = thread { p.start() }
        assertTrue("attach must be entered", entered.await(5, TimeUnit.SECONDS))
        // B: stop fences but issues NO vendor call (attach owns the slot);
        // it must report pending, never confirmed.
        val stopped = p.stop()
        assertEquals(AutoSensingProbe.State.STOPPED, stopped.state)
        assertEquals(RemovalState.IN_FLIGHT, stopped.removalState)
        assertNull("pending cleanup is not a result", stopped.reasonCode)
        assertEquals(0, driver.detachCalls)
        // C: racing start is refused WITHOUT touching the vendor — no
        // duplicate attach can race the in-flight one.
        val refused = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, refused.state)
        assertEquals(ProbeReason.OPERATION_IN_FLIGHT, refused.reasonCode)
        assertEquals(1, driver.attachCalls)
        // A returns: its own flow performs the single cleanup detach.
        release.countDown()
        starter.join(5_000)
        assertFalse("start must return after fenced cleanup", starter.isAlive)
        assertEquals("exactly one cleanup detach, no duplicate", 1, driver.detachCalls)
        val settled = p.status()
        assertEquals(AutoSensingProbe.State.STOPPED, settled.state)
        assertEquals(RemovalState.CALL_RETURNED, settled.removalState)
        assertFalse(settled.attached)
        assertEquals(0L, settled.callbackCount)
        // The removed old sink can never affect anything again.
        val oldSink = driver.lastDetachedSink ?: error("expected a removed sink")
        oldSink.onReport(valid())
        assertEquals(0L, p.status().callbackCount)
        // A fresh start afterwards owns a new, live registration.
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
        assertEquals(2, driver.attachCalls)
        driver.emit(valid())
        assertEquals(1L, p.status().callbackCount)
        assertEquals(1L, p.status().last?.seq)
    }

    @Test
    fun scheduleB_start_during_detach_is_refused_and_new_session_unaffected() {
        val enteredD = CountDownLatch(1)
        val releaseD = CountDownLatch(1)
        val driver = FakeDriver(detachGate = releaseD, enteredDetach = enteredD)
        val p = probe(driver)
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
        // A: stop fences and blocks inside the vendor detach.
        val stopper = thread { p.stop() }
        assertTrue("detach must be entered", enteredD.await(5, TimeUnit.SECONDS))
        // B: start is refused while detach owns the slot; no vendor attach,
        // and the pending removal is reported as in-flight, not success.
        val refused = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, refused.state)
        assertEquals(ProbeReason.OPERATION_IN_FLIGHT, refused.reasonCode)
        assertEquals(RemovalState.IN_FLIGHT, refused.removalState)
        assertEquals(1, driver.attachCalls)
        releaseD.countDown()
        stopper.join(5_000)
        assertFalse("stop must complete", stopper.isAlive)
        val settled = p.status()
        assertEquals(AutoSensingProbe.State.STOPPED, settled.state)
        assertEquals(RemovalState.CALL_RETURNED, settled.removalState)
        assertEquals(1, driver.detachCalls)
        // Fresh start afterwards is unaffected by the fenced session.
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
        assertEquals(2, driver.attachCalls)
        driver.emit(valid())
        assertEquals(1L, p.status().callbackCount)
    }

    @Test
    fun concurrent_start_during_attach_sees_pending_not_idle() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val driver = FakeDriver(attachGate = release, enteredAttach = entered)
        val p = probe(driver)
        val starter = thread { p.start() }
        assertTrue("attach must be entered", entered.await(5, TimeUnit.SECONDS))
        // The second start must observe the in-flight operation (ATTACHING),
        // never a stale IDLE/STOPPED, and must not touch the vendor.
        val concurrent = p.start()
        assertEquals(AutoSensingProbe.State.ATTACHING, concurrent.state)
        assertEquals("no duplicate vendor attach", 1, driver.attachCalls)
        assertFalse(concurrent.attached)
        release.countDown()
        starter.join(5_000)
        assertFalse(starter.isAlive)
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.status().state)
    }

    @Test
    fun new_pending_session_does_not_show_previous_attach_failure() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val driver = FakeDriver(attachResult = false)
        val p = probe(driver)
        assertEquals(ProbeReason.ATTACH_FAILED, p.start().reasonCode)

        driver.attachResult = true
        driver.enteredAttach = entered
        driver.attachGate = release
        val starter = thread { p.start() }
        assertTrue("new attach must be entered", entered.await(5, TimeUnit.SECONDS))
        val pending = p.status()
        assertEquals(AutoSensingProbe.State.ATTACHING, pending.state)
        assertNull("previous failure must not describe a pending session", pending.reasonCode)
        release.countDown()
        starter.join(5_000)
        assertFalse(starter.isAlive)
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.status().state)
    }

    @Test
    fun removal_history_resets_each_session_partial_failure_after_success() {
        val driver = FakeDriver()
        val p = probe(driver)
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
        assertEquals(RemovalState.CALL_RETURNED, p.stop().removalState)
        // A new session partially registers, then fails.
        driver.throwOnAttach = RuntimeException("boom")
        driver.orphanOnFailedAttach = true
        val failed = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, failed.state)
        assertEquals(ProbeReason.ATTACH_UNCONFIRMED, failed.reasonCode)
        assertEquals(
            "stale CALL_RETURNED from the prior session must not survive",
            RemovalState.CALL_FAILED,
            failed.removalState,
        )
        assertTrue(driver.hasUnconfirmed())
        // Restart denied until explicit cleanup.
        assertEquals(ProbeReason.ORPHAN_PRESENT, p.start().reasonCode)
        assertEquals(2, driver.attachCalls)
        driver.throwOnAttach = null
        assertEquals(RemovalState.CALL_RETURNED, p.stop().removalState)
        assertFalse(driver.hasUnconfirmed())
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
    }

    @Test
    fun stop_during_attach_with_clean_attach_failure_reports_no_call_attempted() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val driver = FakeDriver(
            attachGate = release,
            enteredAttach = entered,
            attachResult = false,
        )
        val p = probe(driver)
        val starter = thread { p.start() }
        assertTrue("attach must be entered", entered.await(5, TimeUnit.SECONDS))
        val stopped = p.stop()
        assertEquals(AutoSensingProbe.State.STOPPED, stopped.state)
        assertEquals(RemovalState.IN_FLIGHT, stopped.removalState)
        release.countDown()
        starter.join(5_000)
        assertFalse(starter.isAlive)
        // The attach failed cleanly with nothing registered: the cleanup
        // detach is a no-op typed NOTHING_HELD — reported as NOT_ATTEMPTED,
        // never as a completed removal.
        assertEquals(1, driver.detachCalls)
        val settled = p.status()
        assertEquals(AutoSensingProbe.State.STOPPED, settled.state)
        assertEquals(RemovalState.NOT_ATTEMPTED, settled.removalState)
        assertNull(settled.reasonCode)
        assertEquals(0L, settled.callbackCount)
    }

    @Test
    fun stop_during_attach_with_partial_registration_cleans_up_to_confirmed() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val driver = FakeDriver(
            attachGate = release,
            enteredAttach = entered,
            throwOnAttach = RuntimeException("boom"),
            orphanOnFailedAttach = true,
        )
        val p = probe(driver)
        val starter = thread { p.start() }
        assertTrue("attach must be entered", entered.await(5, TimeUnit.SECONDS))
        val stopped = p.stop()
        assertEquals(RemovalState.IN_FLIGHT, stopped.removalState)
        release.countDown()
        starter.join(5_000)
        assertFalse(starter.isAlive)
        // The attach threw with a possible partial registration: the single
        // cleanup detach removes it and confirms.
        assertEquals(1, driver.detachCalls)
        val settled = p.status()
        assertEquals(AutoSensingProbe.State.STOPPED, settled.state)
        assertEquals(RemovalState.CALL_RETURNED, settled.removalState)
        assertNull(settled.reasonCode)
        assertFalse(driver.hasUnconfirmed())
        // A fresh session proceeds afterwards.
        driver.throwOnAttach = null
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
    }

    @Test
    fun second_attach_while_registered_is_denied_never_shared() {
        val driver = FakeDriver()
        val first: AutoSensingDriver.Sink = object : AutoSensingDriver.Sink {
            override fun onReport(report: AutoSensingDriver.Report) = Unit
        }
        val second: AutoSensingDriver.Sink = object : AutoSensingDriver.Sink {
            override fun onReport(report: AutoSensingDriver.Report) = Unit
        }
        assertTrue(driver.attach(first, 64))
        assertFalse(driver.attach(second, 64))
        assertEquals(ProbeReason.ATTACH_DENIED, driver.lastReason())
    }

    // -- synchronous callbacks and teardown fencing -------------------------

    @Test
    fun synchronous_callback_inside_attach_is_accepted_not_dropped() {
        val driver = FakeDriver(syncReport = valid(enable = true, total = 2, views = listOf(view("CAR", true), view("BOAT", false))))
        val p = probe(driver)
        now = 7_000L
        val status = p.start()
        // The callback arrived before attach returned: it must be preserved,
        // and success must not clobber OBSERVED back to WAITING.
        assertEquals(AutoSensingProbe.State.OBSERVED, status.state)
        assertEquals(1L, status.callbackCount)
        val last = status.last ?: error("expected an observation")
        assertEquals(1L, last.seq)
        assertEquals(7_000L, last.receiveElapsedMs)
        assertEquals(2, last.targetCount)
    }

    @Test
    fun concurrent_callbacks_during_stop_complete_without_deadlock() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        val stopper = thread { p.stop() }
        repeat(50) { driver.emit(valid(total = 0, views = emptyList())) }
        stopper.join(5_000)
        assertFalse("stop must not deadlock against callback teardown", stopper.isAlive)
        val frozen = p.status().callbackCount
        driver.emit(valid())
        assertEquals("post-stop callbacks must be ignored", frozen, p.status().callbackCount)
        assertEquals(AutoSensingProbe.State.STOPPED, p.status().state)
    }

    @Test
    fun old_session_callbacks_after_restart_are_ignored() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        val oldSink = driver.sink ?: error("expected a sink")
        p.stop()
        p.start()
        val newSink = driver.sink ?: error("expected a new sink")
        oldSink.onReport(valid())
        assertEquals("previous-session callback must die by generation", 0L, p.status().callbackCount)
        newSink.onReport(valid())
        val status = p.status()
        assertEquals(1L, status.callbackCount)
        assertEquals(1L, status.last?.seq)
    }

    // -- partial registration / orphan handling ------------------------------

    @Test
    fun attach_throw_with_possible_partial_registration_blocks_restart_until_cleared() {
        val driver = FakeDriver(
            throwOnAttach = RuntimeException("boom"),
            orphanOnFailedAttach = true,
        )
        val p = probe(driver)
        val failed = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, failed.state)
        assertEquals(ProbeReason.ATTACH_UNCONFIRMED, failed.reasonCode)
        assertTrue(driver.hasUnconfirmed())
        // New attach refused WITHOUT another vendor call.
        val refused = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, refused.state)
        assertEquals(ProbeReason.ORPHAN_PRESENT, refused.reasonCode)
        assertEquals(1, driver.attachCalls)
        // Explicit stop retries removal; success clears the orphan.
        val cleared = p.stop()
        assertEquals(AutoSensingProbe.State.STOPPED, cleared.state)
        assertEquals(RemovalState.CALL_RETURNED, cleared.removalState)
        assertFalse(driver.hasUnconfirmed())
        driver.throwOnAttach = null
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, p.start().state)
    }

    @Test
    fun refused_start_while_orphan_never_touches_the_vendor() {
        val driver = FakeDriver(attachResult = false, orphanOnFailedAttach = true)
        val p = probe(driver)
        p.start()
        assertTrue(driver.hasUnconfirmed())
        val calls = driver.attachCalls
        repeat(3) {
            val refused = p.start()
            assertEquals(ProbeReason.ORPHAN_PRESENT, refused.reasonCode)
        }
        assertEquals(calls, driver.attachCalls)
    }

    // -- data honesty: confirmed vs unreadable --------------------------------

    @Test
    fun first_callback_moves_to_observed_with_receive_time_only() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        now = 4_321L
        driver.emit(valid(enable = true, total = 2, views = listOf(view("PERSON", true), view("CAR", false))))
        val status = p.status()
        assertEquals(AutoSensingProbe.State.OBSERVED, status.state)
        assertEquals(1L, status.callbackCount)
        val last = status.last ?: error("expected an observation")
        assertTrue(last.payloadOk)
        assertNull(last.reason)
        assertEquals(1L, last.seq)
        assertEquals(4_321L, last.receiveElapsedMs)
        assertEquals(true, last.enableReported)
        assertEquals(2, last.targetCount)
        assertEquals(listOf("PERSON", "CAR"), last.types)
        assertEquals(1, last.rectPresentCount)
        assertEquals(0, last.rectUnknownCount)
        assertFalse(last.truncated)
        assertEquals(0, last.skippedEntries)
    }

    @Test
    fun type_vocabulary_is_allowlisted_and_never_free_form() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        driver.emit(
            valid(
                enable = false,
                total = 9,
                views = listOf(
                    view("PERSON"), view("CAR"), view("BOAT"), view("CUSTOM"), view("UNKNOWN"),
                    view("HUMAN"), view(null),
                    view("person"), view("PILOT'S FACE <script>"),
                ),
            ),
        )
        val last = p.status().last ?: error("expected an observation")
        assertEquals(
            listOf(
                "PERSON", "CAR", "BOAT", "CUSTOM", "UNKNOWN",
                AutoSensingProbe.UNRECOGNIZED, AutoSensingProbe.UNRECOGNIZED,
                AutoSensingProbe.UNRECOGNIZED, AutoSensingProbe.UNRECOGNIZED,
            ),
            last.types,
        )
    }

    @Test
    fun confirmed_empty_list_is_reported_empty_not_unknown() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        driver.emit(valid(enable = false, total = 0, views = emptyList()))
        val last = p.status().last ?: error("expected an observation")
        assertTrue(last.payloadOk)
        assertEquals(0, last.targetCount)
        assertEquals(0, last.totalReported)
        // ...while zero CALLBACKS stays a different, unknown state:
        val waiting = probe(FakeDriver()).start()
        assertEquals(AutoSensingProbe.State.ATTACHED_WAITING, waiting.state)
        assertNull(waiting.last)
    }

    @Test
    fun null_payload_is_unreadable_never_empty() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        driver.emit(AutoSensingDriver.Report.Unreadable(ProbeReason.PAYLOAD_NULL))
        val status = p.status()
        // The callback WAS received (counted), but nothing about content is claimed.
        assertEquals(AutoSensingProbe.State.OBSERVED, status.state)
        assertEquals(1L, status.callbackCount)
        val last = status.last ?: error("expected an observation")
        assertFalse(last.payloadOk)
        assertEquals(ProbeReason.PAYLOAD_NULL, last.reason)
        assertNull("unreadable payload must not report a count", last.targetCount)
        assertNull(last.enableReported)
        assertTrue(last.types.isEmpty())
    }

    @Test
    fun unreadable_target_list_is_unreadable_never_empty() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        driver.emit(AutoSensingDriver.Report.Unreadable(ProbeReason.TARGETS_UNREADABLE))
        val last = p.status().last ?: error("expected an observation")
        assertFalse(last.payloadOk)
        assertEquals(ProbeReason.TARGETS_UNREADABLE, last.reason)
        assertNull(last.targetCount)
    }

    @Test
    fun unreadable_enable_and_rect_stay_unknown_not_false() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        driver.emit(
            valid(
                enable = null,
                total = 2,
                views = listOf(view("CAR", null), view("CAR", false)),
            ),
        )
        val last = p.status().last ?: error("expected an observation")
        assertTrue(last.payloadOk)
        assertNull("unreadable enable must stay null, never false", last.enableReported)
        assertEquals(0, last.rectPresentCount)
        assertEquals(1, last.rectUnknownCount)
        assertEquals(2, last.targetCount)
    }

    @Test
    fun skipped_entries_are_counted_not_silently_dropped() {
        val driver = FakeDriver()
        val p = probe(driver)
        p.start()
        driver.emit(valid(total = 5, views = listOf(view("CAR", true)), skipped = 4))
        val last = p.status().last ?: error("expected an observation")
        assertEquals(5, last.targetCount)
        assertEquals(1, last.types.size)
        assertEquals(4, last.skippedEntries)
    }

    @Test
    fun unreadable_clock_records_null_time_not_a_fabricated_value() {
        val driver = FakeDriver()
        val p = AutoSensingProbe(driver = driver, clock = { throw RuntimeException("no clock") })
        p.start()
        driver.emit(valid())
        val last = p.status().last ?: error("expected an observation")
        assertTrue(last.payloadOk)
        assertNull(last.receiveElapsedMs)
    }

    // -- boundedness ----------------------------------------------------------

    @Test
    fun recent_window_is_bounded_oldest_dropped_count_kept() {
        val driver = FakeDriver()
        val p = probe(driver, maxRetained = 3)
        p.start()
        repeat(5) { i ->
            now = 1_000L + i
            driver.emit(valid(total = 0, views = emptyList()))
        }
        val status = p.status()
        assertEquals(5L, status.callbackCount)
        assertEquals(3, status.recent.size)
        assertEquals(listOf(3L, 4L, 5L), status.recent.map { it.seq })
        assertEquals(1_002L, status.recent.first().receiveElapsedMs)
    }

    @Test
    fun huge_reported_totals_are_represented_without_materializing() {
        val driver = FakeDriver()
        val p = probe(driver, maxTargets = 4)
        p.start()
        val capped = List(4) { view("CAR", true) }
        driver.emit(valid(total = 1_000_000, views = capped, truncated = true))
        val last = p.status().last ?: error("expected an observation")
        assertEquals(1_000_000, last.targetCount)
        assertEquals(1_000_000, last.totalReported)
        assertEquals(4, last.types.size)
        assertTrue(last.truncated)
    }

    @Test
    fun conversion_bound_math_is_capped_and_honest() {
        assertEquals(64 to false, boundTake(64, 64))
        assertEquals(64 to true, boundTake(65, 64))
        assertEquals(64 to true, boundTake(1_000_000, 64))
        assertEquals(0 to false, boundTake(0, 64))
        assertEquals(0 to false, boundTake(-5, 64))
        assertEquals(1 to true, boundTake(10, 1))
        assertEquals(1 to true, boundTake(10, 0))
        assertEquals(256, effectiveCap(10_000))
        assertEquals(1, effectiveCap(-3))
        assertEquals(64, effectiveCap(64))
    }

    // -- fixed codes, hostile text --------------------------------------------

    @Test
    fun hostile_exception_text_never_reaches_probe_state() {
        val hostile = "secret /data/user/0/ksi token=abc"
        val driver = FakeDriver(throwOnAttach = RuntimeException(hostile))
        val p = probe(driver)
        val status = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, status.state)
        assertEquals(ProbeReason.ATTACH_FAILED, status.reasonCode)
        val rendered = status.toString() + status.recent.toString()
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("token=abc"))
    }

    @Test
    fun hostile_detach_text_never_reaches_probe_state() {
        val driver = FakeDriver(throwOnDetach = RuntimeException("path=/sdcard/pii.jpg id=123"))
        val p = probe(driver)
        p.start()
        val status = p.stop()
        assertEquals(ProbeReason.DETACH_FAILED, status.reasonCode)
        assertEquals(RemovalState.CALL_FAILED, status.removalState)
        assertFalse(status.toString().contains("pii.jpg"))
    }

    @Test
    fun fixed_display_texts_are_distinct_fixed_strings() {
        val texts = ProbeReason.values().map { it.display() }
        assertEquals(ProbeReason.values().size, texts.distinct().size)
        texts.forEach { assertTrue(it.isNotEmpty()) }
    }

    // -- real driver: fail-closed on host, never throws -----------------------

    @Test
    fun real_driver_fail_closes_on_host_without_sdk() {
        val driver = DjiAutoSensingDriver()
        val p = AutoSensingProbe(driver = driver, clock = { now })
        val status = p.start()
        assertEquals(AutoSensingProbe.State.FAILED, status.state)
        assertFalse(status.attached)
        assertEquals(0L, status.callbackCount)
        // Unreadable and negative reads are both "unconfirmed" — never a
        // definitive claim about the SDK.
        assertEquals(ProbeReason.SDK_REGISTRATION_UNCONFIRMED, status.reasonCode)
        assertEquals(ProbeReason.SDK_REGISTRATION_UNCONFIRMED, driver.lastReason())
        assertFalse(driver.hasUnconfirmed())
    }

    @Test
    fun real_driver_detach_without_attach_is_a_no_op_not_a_removal() {
        assertEquals(
            AutoSensingDriver.DetachOutcome.NOTHING_HELD,
            DjiAutoSensingDriver().detach(),
        )
    }
}
