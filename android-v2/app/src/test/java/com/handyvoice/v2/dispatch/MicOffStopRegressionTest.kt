@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.handyvoice.v2.dispatch

import com.handyvoice.v2.api.ConnectedResponse
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.api.HandyV2ApiService
import com.handyvoice.v2.api.HdspPositionRequest
import com.handyvoice.v2.api.ModeRequest
import com.handyvoice.v2.api.SlideRangeRequest
import com.handyvoice.v2.api.VelocityInfo
import com.handyvoice.v2.api.VelocityRequest
import com.handyvoice.v2.ui.ListeningSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Regression for the mic-off race: a "begin" captured just before the mic is
 * toggled off must not land during the stop's rampStop wind-down (its velocity
 * write bumps the client's epoch, rampStop bails before hamp/stop, and the
 * device keeps moving with listening off).
 *
 * Runs the real Dispatch, PatternEngine, HumanizeEngine and HandyV2ApiClient
 * against an in-memory device, on virtual time.
 */
class MicOffStopRegressionTest {

    /** In-memory Handy: tracks motion and counts hamp/stop calls. */
    private class FakeDevice : HandyV2ApiService {
        var moving = false
        var velocity = 0.0
        var stopCalls = 0

        private val ok = Response.success(Unit)

        override suspend fun checkConnection(connectionKey: String) =
            Response.success(ConnectedResponse(true))

        override suspend fun setMode(connectionKey: String, request: ModeRequest) = ok

        override suspend fun hampStart(connectionKey: String): Response<Unit> {
            moving = true
            return ok
        }

        override suspend fun hampStop(connectionKey: String): Response<Unit> {
            moving = false
            velocity = 0.0
            stopCalls++
            return ok
        }

        override suspend fun setVelocity(connectionKey: String, request: VelocityRequest): Response<Unit> {
            velocity = request.velocity
            return ok
        }

        override suspend fun getVelocity(connectionKey: String) =
            Response.success(VelocityInfo(velocity))

        override suspend fun setSlide(connectionKey: String, request: SlideRangeRequest) = ok

        override suspend fun hdspXpt(connectionKey: String, request: HdspPositionRequest) = ok
    }

    /**
     * [childOnSession] = true is the shipped design (recognition is a child of
     * the session, the stop joins it). false simulates the old ordering: the
     * recognition job lives outside the session and the stop runs at once.
     */
    private suspend fun TestScope.micOffScenario(
        humanizeStrength: Int,
        childOnSession: Boolean,
    ): FakeDevice {
        val device = FakeDevice()
        val api = HandyV2ApiClient(device)
        api.setConnectionKey("key")
        val pattern = PatternEngine(api, backgroundScope)
        val humanize = HumanizeEngine(api, backgroundScope).also { it.setStrength(humanizeStrength) }
        val outer: CoroutineScope = this

        suspend fun dispatch(label: String) = Dispatch.dispatch(
            label = label,
            api = api,
            patternEngine = pattern,
            humanizeEngine = humanize,
        )

        assertTrue(api.connect())
        dispatch("begin")
        delay(1000)
        assertTrue(device.moving)

        val session = ListeningSession(outer)
        val matcher = CompletableDeferred<String>()
        session.start {
            // A snapshot was captured; its match is still in flight.
            val parent: CoroutineScope = if (childOnSession) this else outer
            parent.launch { dispatch(matcher.await()) }
        }
        runCurrent()

        if (childOnSession) {
            session.cancelThen { dispatch("stop") }
        } else {
            session.cancel()
            outer.launch { dispatch("stop") }
        }
        delay(500)            // mid wind-down
        matcher.complete("begin")
        delay(5000)           // well past the ~1.5 s ramp
        return device
    }

    private fun check(humanizeStrength: Int) = runTest {
        val device = micOffScenario(humanizeStrength, childOnSession = true)
        assertFalse("device must end stopped", device.moving)
        assertEquals(0.0, device.velocity, 0.0)
        assertTrue("a terminal hamp/stop must be sent", device.stopCalls >= 1)
    }

    @Test
    fun staleBeginDuringMicOffStopLeavesDeviceStopped() = check(humanizeStrength = 0)

    @Test
    fun staleBeginDuringMicOffStopLeavesDeviceStoppedWithHumanize() = check(humanizeStrength = 30)

    // Control: the old ordering must still reproduce the bug, or the two tests
    // above would pass for the wrong reason.
    @Test
    fun oldOrderingLetsStaleBeginKeepDeviceMoving() = runTest {
        val device = micOffScenario(humanizeStrength = 0, childOnSession = false)
        assertTrue("old ordering should leave the device moving", device.moving)
    }
}
