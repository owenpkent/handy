@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.handyvoice.v2.api

import com.handyvoice.v2.dispatch.HumanizeEngine
import com.handyvoice.v2.dispatch.PatternEngine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives the real [LovenseBleTransport] and [HandyV2ApiClient] against a fake
 * toy behind the [LovenseGattPort] seam, on virtual time.
 */
class LovenseBleTransportTest {

    /** One GATT link to the fake toy. */
    private class FakeSession(val port: FakePort, val sink: GattSink) : LovenseGattSession {
        override val deviceName: String? = "LVS-Test"
        var cccdOk = true
        var dropDuringCccd = false

        /** Drop the link while this command is awaiting its reply. */
        var dropOnCommand: String? = null
        var autoSwitchWriteReply: String? = "OK;"
        var getAsReply: String? = "1:0;"

        /** Next write is accepted but its callback is held (platform write outstanding). */
        var holdNextWrite = false
        var busy = false
        var rejectNextWrite = false
        private var gate: CompletableDeferred<Unit>? = null

        /** Complete the held platform write. */
        fun completeOutstanding() {
            busy = false
            gate?.complete(Unit)
        }

        override suspend fun discover() = Discovery.OK

        override suspend fun enableNotifications(): Boolean {
            if (dropDuringCccd) {
                port.dropLink(this)
                return true // the stale "success" must not win
            }
            return cccdOk
        }

        override suspend fun write(text: String): WriteResult {
            if (rejectNextWrite) { rejectNextWrite = false; return WriteResult.REJECTED }
            if (busy) return WriteResult.REJECTED
            port.writes += text
            if (text == dropOnCommand) {
                port.dropLink(this)
                return WriteResult.OK
            }
            when {
                text.startsWith("Vibrate:") -> port.motor = text.removePrefix("Vibrate:").removeSuffix(";").toInt()
                text == LovenseProtocol.CMD_DEVICE_TYPE -> sink.onNotify("C:11:0082059AD3BD;")
                text == LovenseProtocol.CMD_AUTO_SWITCH -> autoSwitchWriteReply?.let { sink.onNotify(it) }
                text == LovenseProtocol.CMD_GET_AUTO_SWITCH -> getAsReply?.let { sink.onNotify(it) }
                text == LovenseProtocol.CMD_BATTERY -> sink.onNotify("85;")
            }
            if (holdNextWrite && text.startsWith("Vibrate:")) {
                holdNextWrite = false
                busy = true
                val g = CompletableDeferred<Unit>()
                gate = g
                g.await()
            }
            return WriteResult.OK
        }

        override fun close() {}
    }

    private class FakePort : LovenseGattPort {
        val scanQueue = ArrayDeque<ScanHit>()
        var scanCount = 0
        val opened = mutableListOf<String>()
        val writes = mutableListOf<String>()
        var motor = 0
        var current: FakeSession? = null

        /** Configure each new session before the transport uses it. */
        var onSession: (FakeSession) -> Unit = {}

        override fun preconnectError(): String? = null

        override suspend fun scan(): ScanHit? {
            scanCount++
            return scanQueue.removeFirstOrNull()
        }

        override suspend fun open(address: String, sink: GattSink): LovenseGattSession {
            opened += address
            return FakeSession(this, sink).also { current = it; onSession(it) }
        }

        /** The toy loses the link: it stops (safety setting) and the callback fires. */
        fun dropLink(s: FakeSession) {
            motor = 0
            s.sink.onDisconnected()
        }
    }

    private class Env(scope: TestScope, saved: String? = "AA:01", val port: FakePort = FakePort()) {
        val paired = mutableListOf<String>()
        val transport = LovenseBleTransport(port, saved, { a, _ -> paired += a }, scope.backgroundScope)
        val api = HandyV2ApiClient(transport)
    }

    // ---- 1. AutoSwith --------------------------------------------------------

    @Test
    fun autoSwith_sendsWordSyntaxThenReadsBack_noWarning() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        val w = e.port.writes
        assertTrue(w.contains("AutoSwith:On:Off;"))
        assertFalse(w.contains("AutoSwith:1:0;"))
        assertTrue(w.indexOf("GetAS;") > w.indexOf("AutoSwith:On:Off;"))
        assertNull(e.api.warning.value)
    }

    @Test
    fun autoSwith_errReply_connectsButWarns() = runTest {
        val e = Env(this)
        e.port.onSession = { it.autoSwitchWriteReply = "ERR;" }
        assertTrue(e.api.connect())
        assertEquals(ConnectionState.CONNECTED, e.api.connectionState.value)
        assertNotNull(e.api.warning.value)
        assertFalse(e.port.writes.contains("GetAS;"))
    }

    @Test
    fun autoSwith_readbackNotSafe_warns() = runTest {
        val e = Env(this)
        e.port.onSession = { it.getAsReply = "0:1;" }
        assertTrue(e.api.connect())
        assertNotNull(e.api.warning.value)
    }

    // ---- 2. reconnect must not restart motion ---------------------------------

    private suspend fun TestScope.reconnectScenario(startProducer: suspend (HandyV2ApiClient) -> Unit) {
        val e = Env(this)
        assertTrue(e.api.connect())
        startProducer(e.api)
        advanceTimeBy(1500)
        assertTrue(
            "producer should be driving the toy",
            e.port.writes.any { it.startsWith("Vibrate:") && it != "Vibrate:0;" },
        )

        e.port.dropLink(e.port.current!!)       // Bluetooth lost, producer NOT cancelled
        runCurrent()
        assertEquals(ConnectionState.DISCONNECTED, e.api.connectionState.value)
        assertEquals(0, e.port.motor)

        assertTrue(e.api.connect())             // reconnect, no fresh start()
        val mark = e.port.writes.size
        advanceTimeBy(5000)
        val after = e.port.writes.drop(mark).filter { it.startsWith("Vibrate:") && it != "Vibrate:0;" }
        assertEquals("no positive velocity before a fresh start: $after", emptyList<String>(), after)
        assertEquals(0, e.port.motor)
        assertFalse(e.api.isRunning.value)
    }

    @Test
    fun reconnect_survivingHumanizeLoop_doesNotRestartToy() = runTest {
        reconnectScenario { api ->
            val h = HumanizeEngine(api, backgroundScope).also { it.setStrength(30) }
            assertTrue(h.start(60))
        }
    }

    @Test
    fun reconnect_survivingPatternLoop_doesNotRestartToy() = runTest {
        reconnectScenario { api ->
            assertTrue(PatternEngine(api, backgroundScope).start("tick", 80))
        }
    }

    // ---- 4. rejected write ---------------------------------------------------

    @Test
    fun rejectedZeroWrite_isNotCachedAndRetrySends() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        val t = e.transport
        assertTrue(t.start().ok)

        // An accepted nonzero write held at its callback, then cancelled.
        e.port.current!!.holdNextWrite = true
        val job = launch { t.setVelocity(70) }
        runCurrent()
        assertEquals(14, e.port.motor)
        job.cancelAndJoin()

        // The platform write is still outstanding, so the stop is rejected as busy.
        val stop = t.stop()
        assertFalse(stop.ok)
        assertEquals(14, e.port.motor)

        // Once the outstanding operation completes, a zero must be sent, not deduplicated.
        e.port.current!!.completeOutstanding()
        assertTrue(t.setVelocity(0).ok)
        assertEquals(0, e.port.motor)
    }

    // ---- 5. setup-time disconnect ----------------------------------------------

    @Test
    fun disconnectDuringCccd_failsConnect() = runTest {
        val e = Env(this)
        e.port.onSession = { it.dropDuringCccd = true }
        assertFalse(e.api.connect())
        assertEquals(ConnectionState.DISCONNECTED, e.api.connectionState.value)
    }

    @Test
    fun disconnectDuringInitCommand_failsConnect() = runTest {
        val e = Env(this)
        e.port.onSession = { it.dropOnCommand = "AutoSwith:On:Off;" }
        assertFalse(e.api.connect())
        assertEquals(ConnectionState.DISCONNECTED, e.api.connectionState.value)
    }

    @Test
    fun failedCccdWrite_failsConnect() = runTest {
        val e = Env(this)
        e.port.onSession = { it.cccdOk = false }
        assertFalse(e.api.connect())
        assertEquals(ConnectionState.DISCONNECTED, e.api.connectionState.value)
    }

    // ---- 6. stopped speed edits --------------------------------------------------

    @Test
    fun speedEditAfterFreshConnect_doesNotMoveToy() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        e.api.setSpeed(50)
        assertEquals(0, e.port.motor)
        assertFalse(e.api.isRunning.value)
    }

    @Test
    fun fasterAfterStop_doesNotMoveToy_andStartAppliesRememberedLevel() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        assertTrue(e.api.start())
        assertTrue(e.api.setSpeed(40))
        assertEquals(8, e.port.motor)
        e.api.stop()
        assertEquals(0, e.port.motor)

        e.api.rampSpeed(60)                      // Faster tap while stopped
        assertEquals(0, e.port.motor)
        assertFalse(e.api.isRunning.value)

        assertTrue(e.api.start())                // explicit start applies the remembered level
        assertTrue(e.port.motor > 0)
        assertTrue(e.api.isRunning.value)
    }

    // ---- 7. paired address kept ---------------------------------------------------

    @Test
    fun reconnectInSameProcess_usesPairedAddress_withoutScanning() = runTest {
        val e = Env(this, saved = null)
        e.port.scanQueue += ScanHit("TOY:A", "LVS-A")
        assertTrue(e.api.connect())
        assertEquals(listOf("TOY:A"), e.paired)
        assertEquals(1, e.port.scanCount)

        e.api.disconnect()
        e.port.scanQueue.addFirst(ScanHit("TOY:B", "LVS-B")) // another toy advertises first
        assertTrue(e.api.connect())
        assertEquals(1, e.port.scanCount)
        assertEquals(listOf("TOY:A", "TOY:A"), e.port.opened)
        assertEquals(listOf("TOY:A"), e.paired)
    }
}
