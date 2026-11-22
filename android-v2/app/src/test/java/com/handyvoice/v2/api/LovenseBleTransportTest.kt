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
        /** Drop the link while the transport reads the device name (last setup step). */
        var dropOnName = false
        override val serviceUuid: String? = "6e400001-test"
        override val deviceName: String?
            get() {
                if (dropOnName) { dropOnName = false; port.dropLink(this) }
                return "LVS-Test"
            }
        var cccdOk = true
        var dropDuringCccd = false
        var ignoreDeviceType = false

        /** Drop the link while this command is awaiting its reply. */
        var dropOnCommand: String? = null
        var silent = false // the toy answers nothing to probe commands
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
                text == LovenseProtocol.CMD_DEVICE_TYPE -> if (!ignoreDeviceType) sink.onNotify("C:11:0082059AD3BD;")
                text == LovenseProtocol.CMD_AUTO_SWITCH -> autoSwitchWriteReply?.let { sink.onNotify(it) }
                text == LovenseProtocol.CMD_GET_AUTO_SWITCH -> getAsReply?.let { sink.onNotify(it) }
                text == LovenseProtocol.CMD_BATTERY -> sink.onNotify("85;")
            }
            if (!silent) LovenseProtocol.parseProbe(text)?.let { (name, _) ->
                sink.onNotify(if (name == "Rotate") "ERR;" else "OK;")
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

        var closed = false
        override fun close() { closed = true }
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
        val transport = LovenseBleTransport(
            port, saved, { a, _ -> paired += a }, scope.backgroundScope,
            clock = { scope.testScheduler.currentTime },
        )
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

    @Test
    fun disconnectDuringNameLookup_failsConnect() = runTest {
        val e = Env(this)                        // saved address, so the name is read from the session
        e.port.onSession = { it.dropOnName = true }
        assertFalse(e.api.connect())
        assertEquals(ConnectionState.DISCONNECTED, e.api.connectionState.value)
        assertNotNull(e.api.lastError.value)
        assertFalse(e.api.start())
    }

    @Test
    fun dropAfterTransportReturnsOk_clientStillFailsConnect() = runTest {
        // A drop landing between the transport's last check and the client's
        // CONNECTED commit must not be overwritten by a successful connect.
        val t = object : DeviceTransport {
            override val kind = "fake"
            override var listener: TransportListener? = null
            override fun preflightError(): String? = null
            override suspend fun connect(): TransportResult {
                listener?.onUnexpectedDisconnect("dropped")
                return TransportResult.OK
            }
            override suspend fun disconnect() {}
            override suspend fun start() = TransportResult.OK
            override suspend fun stop() = TransportResult.OK
            override suspend fun setVelocity(velocity: Int) = TransportResult.OK
            override suspend fun setSlide(min: Int, max: Int) = TransportResult.OK
            override suspend fun park(devMin: Int, devMax: Int, target: Int) = true
        }
        val api = HandyV2ApiClient(t)
        assertFalse(api.connect())
        assertEquals(ConnectionState.DISCONNECTED, api.connectionState.value)
        assertEquals("dropped", api.lastError.value)
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

    @Test
    fun manualStartWithSliderAtZero_afterHumanize_staysStill() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        val h = HumanizeEngine(e.api, backgroundScope).also { it.setStrength(30) }
        assertTrue(h.start(60))
        advanceTimeBy(1500)
        assertTrue(e.port.motor > 0)

        // Manual slider to zero: ControlActivity cancels humanize, then stops.
        h.cancel()
        e.api.stop()
        assertEquals(0, e.port.motor)

        // START with the slider still at zero (ControlActivity's zero branch).
        e.api.setSpeed(0)
        e.api.start()
        advanceTimeBy(1000)
        assertEquals(0, e.port.motor)
    }

    // ---- 6b. silent keep-alive ------------------------------------------------

    @Test
    fun keepAlive_stopped_writesVibrateZeroEachPoll() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        runCurrent()
        val mark = e.port.writes.size
        advanceTimeBy(30_001)
        runCurrent()
        val after = e.port.writes.drop(mark)
        assertTrue("keep-alive expected: $after", after.contains("Vibrate:0;"))
        assertEquals(0, e.port.motor)
    }

    @Test
    fun keepAlive_running_doesNotStopTheMotor() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        runCurrent()
        assertTrue(e.transport.start().ok)
        assertTrue(e.transport.setVelocity(60).ok)
        assertEquals(12, e.port.motor)
        val mark = e.port.writes.size
        advanceTimeBy(30_001)
        runCurrent()
        val after = e.port.writes.drop(mark)
        assertFalse("no Vibrate:0; while running: $after", after.contains("Vibrate:0;"))
        assertEquals(12, e.port.motor)
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

    // ---- 8. test panel: probe ----------------------------------------------------

    @Test
    fun probe_sendsCommandAndReturnsRawReply() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        val ok = e.api.probe("Oscillate:5;")
        assertTrue(ok.sent)
        assertEquals("OK;", ok.reply)
        assertTrue(e.port.writes.contains("Oscillate:5;"))
        val err = e.api.probe("Rotate:10;")
        assertEquals("ERR;", err.reply)
    }

    @Test
    fun probe_unansweredCommandGivesNullReply() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        // The fake toy ignores Vibrate1 only if we remove its reply path; use a toy that never answers.
        e.port.current!!.silent = true
        val r = e.api.probe("Vibrate1:5;")
        assertTrue(r.sent)
        assertNull(r.reply)
    }

    @Test
    fun probe_rejectsAnythingNotOnTheAllowList_andSendsNothing() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        runCurrent()
        val mark = e.port.writes.size
        for (cmd in listOf("PowerOff;", "Vibrate:21;", "Battery;", "Vibrate:5;Vibrate:6;", "AutoSwith:On:Off;")) {
            val r = e.api.probe(cmd)
            assertFalse(cmd, r.sent)
        }
        assertEquals(emptyList<String>(), e.port.writes.drop(mark))
    }

    @Test
    fun probe_whenDisconnected_isNotSent() = runTest {
        val e = Env(this)
        assertFalse(e.api.probe("Vibrate:5;").sent)
        assertEquals(emptyList<String>(), e.port.writes)
    }

    @Test
    fun probeNonzero_pausesForcedVibrateZero_untilStopAll() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        runCurrent()
        assertTrue(e.api.probe("Vibrate:10;").sent)
        assertEquals(10, e.port.motor)
        var mark = e.port.writes.size
        advanceTimeBy(95_000)
        runCurrent()
        val during = e.port.writes.drop(mark)
        assertTrue("polls continue: $during", during.contains("Battery;"))
        assertFalse("no forced stop during a probe: $during", during.contains("Vibrate:0;"))
        assertEquals(10, e.port.motor)

        e.api.probeStopAll()
        val w = e.port.writes
        for (n in LovenseProtocol.PROBE_NAMES) assertTrue("$n:0; sent", w.contains("$n:0;"))
        assertEquals("Vibrate:0; goes last", "Vibrate:0;", w.last())
        assertEquals(0, e.port.motor)

        mark = e.port.writes.size
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue("keep-alive resumed", e.port.writes.drop(mark).contains("Vibrate:0;"))
    }

    // ---- 9. test panel: keep-alive modes ---------------------------------------------

    @Test
    fun keepAlive_off_sendsNothing() = runTest {
        val e = Env(this)
        e.transport.setKeepAlive(KeepAliveMode.OFF, 30)
        assertTrue(e.api.connect())
        runCurrent()
        val mark = e.port.writes.size
        advanceTimeBy(125_000)
        runCurrent()
        assertEquals(emptyList<String>(), e.port.writes.drop(mark))
        assertFalse(e.port.writes.contains("Battery;"))
    }

    @Test
    fun keepAlive_batteryOnly_neverWritesVibrate() = runTest {
        val e = Env(this)
        e.transport.setKeepAlive(KeepAliveMode.BATTERY_ONLY, 30)
        assertTrue(e.api.connect())
        runCurrent()
        val mark = e.port.writes.size
        advanceTimeBy(95_000)
        runCurrent()
        val after = e.port.writes.drop(mark)
        assertTrue(after.isNotEmpty() && after.all { it == "Battery;" })
    }

    @Test
    fun keepAlive_default_alsoWritesSilentVibrateZeroWhileStopped() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        runCurrent()
        val mark = e.port.writes.size
        advanceTimeBy(30_001)
        runCurrent()
        val after = e.port.writes.drop(mark)
        assertTrue(after.contains("Battery;") && after.contains("Vibrate:0;"))
    }

    @Test
    fun keepAlive_intervalChange_appliesWithoutReconnect() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        runCurrent()
        e.transport.setKeepAlive(KeepAliveMode.BATTERY_ONLY, 300)
        runCurrent()
        assertEquals(1, e.port.opened.size)
        var mark = e.port.writes.size
        advanceTimeBy(250_000)
        runCurrent()
        assertEquals("slow interval: nothing yet", emptyList<String>(), e.port.writes.drop(mark))
        advanceTimeBy(55_000)
        runCurrent()
        assertEquals(listOf("Battery;"), e.port.writes.drop(mark))

        e.transport.setKeepAlive(KeepAliveMode.BATTERY_ONLY, 30)
        runCurrent()
        mark = e.port.writes.size
        advanceTimeBy(65_000)
        runCurrent()
        assertTrue("fast interval: ${e.port.writes.drop(mark)}", e.port.writes.drop(mark).count { it == "Battery;" } >= 2)
        assertEquals(1, e.port.opened.size)
    }

    // ---- 11. review fixes ---------------------------------------------------------

    @Test
    fun stopAfterOscillateProbe_zeroesOscillate_clearsProbing_keepAliveResumes() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        runCurrent()
        assertTrue(e.api.probe("Oscillate:20;").sent)
        e.api.stop()
        assertTrue("Oscillate:0; written: ${e.port.writes}", e.port.writes.contains("Oscillate:0;"))
        assertEquals("Vibrate:0; goes last", "Vibrate:0;", e.port.writes.last())

        val mark = e.port.writes.size
        advanceTimeBy(30_001)
        runCurrent()
        assertTrue("keep-alive resumed", e.port.writes.drop(mark).contains("Vibrate:0;"))
    }

    @Test
    fun rampStopAfterOscillateProbe_zeroesOscillate() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        assertTrue(e.api.probe("Oscillate:20;").sent)
        e.api.rampStop()
        assertTrue(e.port.writes.contains("Oscillate:0;"))
    }

    @Test
    fun disconnectAfterOscillateProbe_zeroesOscillate() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        assertTrue(e.api.probe("Oscillate:20;").sent)
        e.api.disconnect()
        assertTrue(e.port.writes.contains("Oscillate:0;"))
    }

    @Test
    fun cancelledConnectDuringDeviceType_closesSession_andPropagatesCancellation() = runTest {
        val e = Env(this)
        e.port.onSession = { it.ignoreDeviceType = true }   // DeviceType awaits its 1 s reply timeout
        val job = launch { e.api.connect() }
        runCurrent()
        assertEquals(ConnectionState.CONNECTING, e.api.connectionState.value)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertTrue("GATT session closed", e.port.current!!.closed)
        assertEquals(ConnectionState.DISCONNECTED, e.api.connectionState.value)
    }

    @Test
    fun failedStop_retriesThenConfirmsLater_andSurfacesErrorWhileUnconfirmed() = runTest {
        val e = Env(this)
        e.transport.setKeepAlive(KeepAliveMode.OFF, 30)   // pending stop must outrank Off
        assertTrue(e.api.connect())
        runCurrent()
        assertTrue(e.api.start())
        assertTrue(e.api.setSpeed(50))
        assertEquals(10, e.port.motor)

        e.port.current!!.busy = true                       // every write is rejected
        assertFalse(e.api.stop())
        assertEquals(10, e.port.motor)
        assertEquals("Stop not confirmed by toy", e.api.lastError.value)

        e.port.current!!.busy = false                      // toy recovers; the keep-alive loop resends
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, e.port.motor)
        assertTrue(e.port.writes.last() == "Vibrate:0;")
    }

    @Test
    fun failedStop_retriesWithinTheCall() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        assertTrue(e.api.start())
        assertTrue(e.api.setSpeed(50))
        e.port.current!!.rejectNextWrite = true            // first zero is refused, retry lands
        assertTrue(e.api.stop())
        assertEquals(0, e.port.motor)
    }

    @Test
    fun preConnectHook_runsBeforeEveryConnect() = runTest {
        val e = Env(this)
        var runs = 0
        e.api.preConnect = { runs++ }
        assertTrue(e.api.connect())
        e.api.disconnect()
        assertTrue(e.api.connect())
        assertEquals(2, runs)
    }

    // ---- 10. test panel: diagnostics --------------------------------------------------

    @Test
    fun diagnostics_trackConnectIdleAndUnexpectedDrop() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        val d0 = e.api.toyDiagnostics()!!
        assertEquals("6e400001-test", d0.serviceUuid)
        assertEquals("C:11:0082059AD3BD;", d0.deviceTypeReply)
        assertEquals("OK;", d0.autoSwithReply)
        assertEquals("1:0;", d0.getAsReply)
        assertEquals(0L, d0.connectedAtMs)
        assertNull(d0.lastMotionAtMs)

        advanceTimeBy(5_000)
        assertTrue(e.api.start())
        assertTrue(e.api.setSpeed(60))
        assertEquals(5_000L, e.api.toyDiagnostics()!!.lastMotionAtMs)

        advanceTimeBy(10_000)
        e.port.dropLink(e.port.current!!)
        runCurrent()
        val d1 = e.api.toyDiagnostics()!!
        assertEquals(15_000L, d1.disconnectedAtMs)
        assertEquals(true, d1.disconnectUnexpected)
        val rec = com.handyvoice.v2.ui.ToyLabFormat.recordFrom(d1)!!
        assertEquals(15_000L, rec.connectedMs)
        assertEquals(10_000L, rec.idleMs)
        assertTrue(rec.unexpected)
    }

    @Test
    fun diagnostics_userDisconnectIsNotUnexpected() = runTest {
        val e = Env(this)
        assertTrue(e.api.connect())
        e.api.disconnect()
        assertEquals(false, e.api.toyDiagnostics()!!.disconnectUnexpected)
    }
}
