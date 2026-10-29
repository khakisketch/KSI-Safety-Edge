package ksi.safetyedge.rc

import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Host tests for the A9-4 main-thread send bug: tap paths queued commands
 * fine, but `pumpOutbound()` ran the blocking socket write on the UI
 * thread — Android throws NetworkOnMainThreadException while the envelope
 * still reached Edge via retry, producing "거부: null"-class banners for
 * commands that were actually sent.
 *
 * The taps now use [RcSession.pumpOutboundAsync]/[RcSession.flushOutboundAsync]:
 * state checks + queueing stay on the caller, only the socket write moves
 * to the session background executor. These pin that contract with a link
 * that records the thread of every send: no send may happen on the calling
 * (tap-simulating) thread. The MainActivity callers themselves need the
 * Android runtime and are covered by device re-tap.
 */
class TapThreadingTest {

    /** DroneLink double that records the thread of every send call. */
    private class ThreadRecordingLink(
        private val failWith: RuntimeException? = null,
    ) : DroneLink {
        val sendThreads = Collections.synchronizedList(mutableListOf<Thread>())
        override var state: DroneLink.State = DroneLink.State.CONNECTED
        override val isSimulated: Boolean = true
        override val aircraftConnected: Boolean? = null

        override fun setListener(listener: DroneLink.Listener?) = Unit
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun send(bytes: ByteArray): Boolean {
            sendThreads.add(Thread.currentThread())
            failWith?.let { throw it }
            return true
        }
    }

    private val deviceId = "00000000-0000-4000-8000-0000000000aa"

    private fun session(link: DroneLink, dir: File = Files.createTempDirectory("rc-tap-thread").toFile()): RcSession =
        RcSession(
            storeDir = dir,
            rcDeviceId = deviceId,
            wallClock = { "2026-10-24T00:00:00Z" },
            link = link,
            coreConfig = { null },
        )

    private fun bundle() = DemoFeed.demoBundle(
        "00000000-0000-4000-8000-0000000000b9",
        "00000000-0000-4000-8000-0000000000b2",
    )

    @Test
    fun pump_async_never_writes_on_the_calling_thread() {
        val link = ThreadRecordingLink()
        val sess = session(link)
        val caller = Thread.currentThread()
        // Tap body, minus the socket write: queue on the calling thread.
        val provisional = sess.prepareBundle(bundle())
        sess.controller!!.prepare()

        val done = CountDownLatch(1)
        var sent = -1
        sess.pumpOutboundAsync { result ->
            sent = result.getOrThrow()
            done.countDown()
        }
        assertTrue("pump must complete", done.await(5, TimeUnit.SECONDS))

        assertEquals(1, sent)
        assertEquals(1, link.sendThreads.size)
        assertTrue(
            "socket write must leave the calling thread: ${link.sendThreads}",
            link.sendThreads.none { it === caller },
        )
        assertEquals(provisional, sess.pendingFlightId)
    }

    @Test
    fun flush_async_retries_unacked_off_the_calling_thread() {
        val link = ThreadRecordingLink()
        val sess = session(link)
        val caller = Thread.currentThread()
        sess.prepareBundle(bundle())
        sess.controller!!.prepare()
        // First drain: the envelope is sent (and stays unacked).
        val first = CountDownLatch(1)
        sess.pumpOutboundAsync { first.countDown() }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertEquals(1, link.sendThreads.size)

        // Retry tap body: same msg_id re-send, still off the caller thread.
        val done = CountDownLatch(1)
        var resent = -1
        sess.flushOutboundAsync { result ->
            resent = result.getOrThrow()
            done.countDown()
        }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, resent)
        assertEquals(2, link.sendThreads.size)
        assertTrue(
            "no socket write on the calling thread: ${link.sendThreads}",
            link.sendThreads.none { it === caller },
        )
    }

    @Test
    fun throwing_link_surfaces_as_failure_and_the_executor_survives() {
        val link = ThreadRecordingLink(failWith = RuntimeException("boom"))
        val sess = session(link)
        sess.prepareBundle(bundle())
        sess.controller!!.prepare()

        val first = CountDownLatch(1)
        var failure: Throwable? = null
        sess.pumpOutboundAsync { result ->
            failure = result.exceptionOrNull()
            first.countDown()
        }
        assertTrue(first.await(5, TimeUnit.SECONDS))
        assertTrue(
            "a throwing link must surface, not kill the executor: $failure",
            failure?.message == "boom",
        )

        // The shared background executor still serves the next tap.
        val second = CountDownLatch(1)
        sess.pumpOutboundAsync { second.countDown() }
        assertTrue("executor must survive a throwing link", second.await(5, TimeUnit.SECONDS))
    }
}
