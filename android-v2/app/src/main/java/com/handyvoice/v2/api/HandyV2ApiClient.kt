package com.handyvoice.v2.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import java.util.concurrent.atomic.AtomicLong

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

/**
 * Transport-agnostic device client: ramps, epochs, invert, band, state flows.
 * Wire access goes through a [DeviceTransport] (Handy REST by default, or
 * Lovense BLE); swap it with [useTransport] while disconnected.
 *
 * @param transport the active wire transport.
 */
class HandyV2ApiClient(
    transport: DeviceTransport = HandyRestTransport(),
) {

    /**
     * Convenience for tests: wrap an in-memory fake REST service so the
     * client's ramp / epoch logic runs without network.
     */
    constructor(api: HandyV2ApiService) : this(HandyRestTransport(api))

    companion object {
        private const val MAX_LOG_LINES = 50

        // Smooth-ramp tuning for rampSpeed (faster / slower). The transition
        // is broken into several PUT /hamp/velocity legs so the change is
        // felt as a glide instead of a hard jump. Duration scales with how
        // far we're moving (a bigger change takes a little longer), clamped
        // to a sane window. Interval is floored so we never hammer the API.
        private const val RAMP_MS_PER_POINT = 45      // velocity point → ms
        private const val RAMP_MIN_MS = 250L
        private const val RAMP_MAX_MS = 900L
        private const val MAX_RAMP_STEPS = 12
        private const val MIN_RAMP_INTERVAL_MS = 55L

        // Stop wind-down (rampStop). A "stop" eases velocity to 0 over a
        // *fixed* window rather than slamming hamp/stop, so it feels like a
        // wind-down. Fixed-duration (not distance-scaled like rampSpeed) so
        // the glide is still felt at the low device velocities Owen's speed
        // cap produces — e.g. from a device velocity of 7, distance-scaling
        // would give a ~300 ms ramp that reads as instant.
        private const val STOP_RAMP_MS = 1500L
        private const val STOP_RAMP_STEPS = 12
    }

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _currentSpeed = MutableStateFlow(0)
    val currentSpeed: StateFlow<Int> = _currentSpeed.asStateFlow()

    private val _strokeMin = MutableStateFlow(0)
    val strokeMin: StateFlow<Int> = _strokeMin.asStateFlow()

    private val _strokeMax = MutableStateFlow(100)
    val strokeMax: StateFlow<Int> = _strokeMax.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _debugLog = MutableStateFlow<List<String>>(emptyList())
    val debugLog: StateFlow<List<String>> = _debugLog.asStateFlow()

    private val _battery = MutableStateFlow<Int?>(null)
    /** Battery percent when the active transport reports one (Lovense), else null. */
    val battery: StateFlow<Int?> = _battery.asStateFlow()

    private val _deviceName = MutableStateFlow<String?>(null)
    /** Friendly name of the connected device when the transport knows it (Lovense), else null. */
    val deviceName: StateFlow<String?> = _deviceName.asStateFlow()

    private val _warning = MutableStateFlow<String?>(null)
    /** Non-fatal warning from the transport for the current connection, else null. */
    val warning: StateFlow<String?> = _warning.asStateFlow()

    /**
     * True from connect / disconnect until the next successful [start]. While
     * set, positive velocity writes are refused, so a motion loop that outlived
     * a lost connection (or a speed edit before any start) can never restart
     * the device on its own after a reconnect.
     */
    @Volatile private var startRequired = true

    private var connectionKey: String = ""

    private var transport: DeviceTransport = transport

    /**
     * Orders a link drop against connect()'s success commit. A drop reported
     * while CONNECTING sets [droppedDuringConnect]; connect() then fails
     * instead of overwriting the DISCONNECTED it published with CONNECTED.
     */
    private val connectLock = Any()
    private var droppedDuringConnect = false

    private val transportListener = object : TransportListener {
        override fun onLog(message: String) = log(message)
        override fun onBattery(percent: Int?) { _battery.value = percent }
        override fun onDeviceName(name: String?) { _deviceName.value = name }
        override fun onWarning(message: String) { _warning.value = message }
        override fun onUnexpectedDisconnect(reason: String) {
            bumpVelocityEpoch()
            startRequired = true
            _warning.value = null
            _isRunning.value = false
            _currentSpeed.value = 0
            _battery.value = null
            _deviceName.value = null
            _lastError.value = reason
            log(reason)
            synchronized(connectLock) {
                droppedDuringConnect = true
                _connectionState.value = ConnectionState.DISCONNECTED
            }
        }
    }

    init {
        this.transport.listener = transportListener
    }

    /** "handy" or "lovense". */
    val transportKind: String get() = transport.kind

    /**
     * Swap the wire transport. Only allowed while DISCONNECTED; returns false
     * (and changes nothing) otherwise.
     */
    fun useTransport(t: DeviceTransport): Boolean {
        if (_connectionState.value != ConnectionState.DISCONNECTED) return false
        if (t === transport) return true
        transport.listener = null
        transport = t
        t.listener = transportListener
        t.setConnectionKey(connectionKey)
        _battery.value = null
        _deviceName.value = null
        _lastError.value = null
        return true
    }

    /**
     * When true, "user-coord 0" is the device's fully-extended end and
     * "user-coord 100" is the retracted end. We apply this transform inside
     * [setStrokeRange] so callers always think in user coords; the device
     * sees `(100 - userMax, 100 - userMin)` when invert is on.
     */
    private var invertStroke: Boolean = false

    /**
     * Global safety band for stroke position. The connect-time park uses
     * the retracted edge of this band as its target so it doesn't drive
     * past the user's configured floor / ceiling. Activities push this in
     * after reading prefs.
     */
    private var globalStrokeMin: Int = 0
    private var globalStrokeMax: Int = 100

    /**
     * Monotonic token bumped by every single-shot velocity write
     * (start / stop / setSpeed / setSpeedModulated) and captured by the
     * looping ramps (rampSpeed / rampStop). A ramp abandons its remaining
     * legs — and skips its terminal hamp/stop — the instant a newer command
     * bumps the token. This is what lets a "begin" spoken while a "stop" is
     * still winding down win, instead of being stomped back to 0 by the
     * draining stop ramp a beat later. (A running ramp spends nearly all its
     * time in delay() between legs, so the check-then-write window is a few
     * instructions wide; at worst one stale leg lands, never the full drain.)
     */
    private val velocityEpoch = AtomicLong(0L)

    /** Invalidate any in-flight ramp and return the new epoch. Single-shot
     *  writes call this so a fresh command supersedes a running wind-down /
     *  glide; the ramps call it to claim their own epoch. */
    private fun bumpVelocityEpoch(): Long = velocityEpoch.incrementAndGet()

    fun setConnectionKey(key: String) {
        connectionKey = key.trim()
        transport.setConnectionKey(connectionKey)
    }

    fun getConnectionKey(): String = connectionKey

    fun setInvertStroke(value: Boolean) {
        invertStroke = value
    }

    fun isInverted(): Boolean = invertStroke

    fun setGlobalStrokeBand(min: Int, max: Int) {
        val safeMin = min.coerceIn(0, 100)
        val safeMax = max.coerceIn(safeMin, 100)
        globalStrokeMin = safeMin
        globalStrokeMax = safeMax
    }

    private fun log(message: String) {
        val current = _debugLog.value.toMutableList()
        current.add(0, message)
        if (current.size > MAX_LOG_LINES) current.removeLast()
        _debugLog.value = current
    }

    suspend fun connect(): Boolean {
        transport.preflightError()?.let {
            _lastError.value = it
            return false
        }

        synchronized(connectLock) {
            droppedDuringConnect = false
            _connectionState.value = ConnectionState.CONNECTING
        }
        _warning.value = null
        startRequired = true
        log("Connecting...")

        return try {
            val result = transport.connect()
            val committed = result.ok && synchronized(connectLock) {
                if (droppedDuringConnect) false
                else { _connectionState.value = ConnectionState.CONNECTED; true }
            }
            if (result.ok && !committed) {
                // The link dropped after the transport's last check; the drop
                // handler already published DISCONNECTED and the reason.
                log("Connection dropped during setup.")
                _warning.value = null
                false
            } else if (result.ok) {
                startRequired = true
                _lastError.value = null
                refreshState()
                parkAtRetracted()
                true
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                _warning.value = null
                _lastError.value = result.error
                false
            }
        } catch (e: Exception) {
            log("Connection error: ${e.message}")
            _connectionState.value = ConnectionState.DISCONNECTED
            _lastError.value = "Connection error: ${e.message}"
            false
        }
    }

    suspend fun disconnect() {
        stop()
        runCatching { transport.disconnect() }
        startRequired = true
        _warning.value = null
        _connectionState.value = ConnectionState.DISCONNECTED
        _battery.value = null
        _deviceName.value = null
        log("Disconnected")
    }

    suspend fun start(): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }
        // Supersede a draining stop-ramp so a "begin" mid-wind-down wins.
        bumpVelocityEpoch()

        return try {
            log("Starting...")
            val result = transport.start()
            if (result.ok) {
                startRequired = false
                _isRunning.value = true
                _lastError.value = null
                log("Started.")
                true
            } else {
                _lastError.value = result.error
                result.detail?.let { log(it) }
                false
            }
        } catch (e: Exception) {
            _lastError.value = "Start error: ${e.message}"
            log("Start error: ${e.message}")
            false
        }
    }

    suspend fun stop(): Boolean {
        // A plain stop also supersedes any running ramp (e.g. a slider-to-0).
        bumpVelocityEpoch()
        return try {
            log("Stopping...")
            val result = transport.stop()
            _isRunning.value = false
            _lastError.value = null
            log("Stopped.")
            result.ok
        } catch (e: Exception) {
            _isRunning.value = false
            _lastError.value = "Stop error: ${e.message}"
            log("Stop error: ${e.message}")
            false
        }
    }

    /**
     * Set device velocity. NOTE: per HANDY_API_NOTES, velocity must be set
     * AFTER hamp/start or the device resets it. Callers running preset flows
     * (slide -> start -> velocity) must follow that order.
     */
    suspend fun setSpeed(percent: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }

        val velocity = percent.coerceIn(0, 100)
        if (velocity > 0 && startRequired) {
            log("Ignoring speed $velocity%: start() first.")
            return false
        }
        // A direct speed set (e.g. begin's velocity write) supersedes a ramp.
        bumpVelocityEpoch()
        log("Setting speed to $velocity%")
        return putVelocity(velocity)
    }

    /**
     * Glide device velocity from its current value to [target], emitting a
     * handful of intermediate velocities so the change is felt as a smooth
     * ramp instead of a hard jump. Backs the faster / slower commands.
     *
     * The ramp duration scales with the distance travelled (a bigger jump
     * takes a little longer) and is clamped to [RAMP_MIN_MS]..[RAMP_MAX_MS].
     * Each leg moves ~1 velocity point (capped at [MAX_RAMP_STEPS] legs so a
     * large jump doesn't flood the API). Suspends for the full ramp.
     *
     * NB: a newer velocity command (another ramp, a stop, a begin) bumps
     * [velocityEpoch] and this ramp abandons its remaining legs on the next
     * check — so rapid faster/faster taps and a "begin" landing mid-ramp both
     * resolve to the newest intent instead of interleaving writes.
     */
    suspend fun rampSpeed(target: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }
        if (target > 0 && startRequired) {
            log("Ignoring ramp to $target%: start() first.")
            return false
        }
        val myEpoch = bumpVelocityEpoch()
        val from = _currentSpeed.value
        val to = target.coerceIn(0, 100)
        val distance = kotlin.math.abs(to - from)
        if (distance == 0) return true

        val steps = distance.coerceIn(2, MAX_RAMP_STEPS)
        val durationMs = (distance.toLong() * RAMP_MS_PER_POINT)
            .coerceIn(RAMP_MIN_MS, RAMP_MAX_MS)
        val interval = (durationMs / steps).coerceAtLeast(MIN_RAMP_INTERVAL_MS)
        log("Ramping speed $from% → $to% over ~${durationMs}ms ($steps steps)")

        var ok = true
        for (i in 1..steps) {
            // A newer command (another ramp, a stop) took over — abandon this one.
            if (velocityEpoch.get() != myEpoch) return false
            val v = from + (to - from) * i / steps
            ok = putVelocity(v) && ok
            if (i < steps) delay(interval)
        }
        return ok
    }

    /**
     * Ease the device to a stop instead of halting instantly: glide velocity
     * from its current value down to 0 over ~[STOP_RAMP_MS], then send
     * hamp/stop to fully halt. Backs every "stop" affordance (voice, tap
     * shortcut, PiP, the Manual STOP button) so a stop feels like a
     * wind-down, not a slam.
     *
     * Unlike [rampSpeed] the duration is fixed, not distance-scaled, so the
     * glide is felt even at the low device velocities Owen's speed cap
     * produces. Callers cancel any running pattern first (its loop would
     * otherwise keep writing velocity over the ramp); after cancel the
     * device is still moving at its last velocity, which is exactly what we
     * ramp down from. No-ops the glide (still sends hamp/stop) if already
     * stopped or not connected.
     *
     * Runs in the caller's coroutine; the always-on capture loop dispatches
     * this off-thread so the ~1.5 s suspend never blocks listening.
     */
    suspend fun rampStop(): Boolean {
        val myEpoch = bumpVelocityEpoch()
        val from = _currentSpeed.value
        if (_connectionState.value == ConnectionState.CONNECTED && from > 0) {
            val interval = STOP_RAMP_MS / STOP_RAMP_STEPS
            log("Easing to stop from $from% over ~${STOP_RAMP_MS}ms.")
            for (i in 1..STOP_RAMP_STEPS) {
                // If a "begin" / faster came in mid-wind-down, abandon the
                // drain so we don't fight it back toward 0.
                if (velocityEpoch.get() != myEpoch) return false
                val v = from - from * i / STOP_RAMP_STEPS  // reaches 0 at final leg
                putVelocity(v)
                if (i < STOP_RAMP_STEPS) delay(interval)
            }
        }
        // Superseded after the last leg but before the halt? Skip hamp/stop.
        if (velocityEpoch.get() != myEpoch) return false
        return stop()
    }

    /**
     * Quiet velocity write for continuous modulation ([HumanizeEngine]): fires
     * a single PUT /hamp/velocity WITHOUT logging or touching [currentSpeed].
     * The modulation loop writes ~5×/s, so logging each one would flood the
     * debug panel, and the momentary modulated value isn't the number to show
     * the user — [reflectBaseSpeed] keeps the readout on the steady base while
     * the device velocity itself wanders around it.
     */
    suspend fun setSpeedModulated(velocity: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        if (velocity > 0 && startRequired) return false
        // The humanize loop owns velocity once running; its first write here
        // supersedes a draining stop-ramp (begin-with-humanize doesn't call
        // start() when the device is still flagged running mid-wind-down).
        bumpVelocityEpoch()
        return try {
            transport.setVelocity(velocity.coerceIn(0, 100)).ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Reflect an intended *base* speed in [currentSpeed] for the UI, with no
     * device call. [HumanizeEngine] calls this when the user changes the base
     * (begin / faster / slower) so the speed readout shows the steady target
     * rather than jittering with the modulated device velocity.
     */
    fun reflectBaseSpeed(percent: Int) {
        _currentSpeed.value = percent.coerceIn(0, 100)
    }

    /**
     * Quiet stroke-range write for continuous depth drift ([HumanizeEngine]):
     * PUTs /slide (same invert transform as [setStrokeRange]) but does NOT
     * update the cached [strokeMin]/[strokeMax] and does not log. The cache
     * keeps reflecting the user's chosen band, so the Manual sliders stay put
     * while the device window breathes within it.
     */
    suspend fun setStrokeRangeTransient(min: Int, max: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) return false
        val userMin = min.coerceIn(0, 100)
        val userMax = max.coerceIn(userMin, 100)
        val devMin = if (invertStroke) 100 - userMax else userMin
        val devMax = if (invertStroke) 100 - userMin else userMax
        return try {
            transport.setSlide(devMin, devMax).ok
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Single PUT /hamp/velocity. Updates [currentSpeed] on success. Shared by
     * [setSpeed] (logs the intent) and [rampSpeed] (logs once, then steps).
     */
    private suspend fun putVelocity(velocity: Int): Boolean {
        return try {
            val result = transport.setVelocity(velocity)
            if (result.ok) {
                _currentSpeed.value = velocity
                _lastError.value = null
                true
            } else {
                _lastError.value = result.error
                result.detail?.let { log(it) }
                false
            }
        } catch (e: Exception) {
            _lastError.value = "Set speed error: ${e.message}"
            log("Speed error: ${e.message}")
            false
        }
    }

    /**
     * V2 stroke range uses a single PUT /slide with {min, max}. The legacy
     * /slide/min + /slide/max endpoints 404.
     *
     * Args are in *user coords* (0 = retracted, 100 = extended in the
     * orientation the user has chosen). If [invertStroke] is on, we map
     * to device coords via `(100 - userMax, 100 - userMin)` so callers
     * (Manual sliders, presets, voice dispatch) never need to know.
     * The cached [strokeMin] / [strokeMax] always reflect user coords.
     */
    suspend fun setStrokeRange(min: Int, max: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }

        val userMin = min.coerceIn(0, 100)
        val userMax = max.coerceIn(0, 100)
        val devMin: Int
        val devMax: Int
        if (invertStroke) {
            devMin = 100 - userMax
            devMax = 100 - userMin
        } else {
            devMin = userMin
            devMax = userMax
        }

        return try {
            val tag = if (invertStroke) " (inv → dev $devMin-$devMax)" else ""
            log("Setting stroke: $userMin-$userMax%$tag")
            val result = transport.setSlide(devMin, devMax)
            if (result.ok) {
                _strokeMin.value = userMin
                _strokeMax.value = userMax
                _lastError.value = null
                true
            } else {
                _lastError.value = result.error
                result.detail?.let { log(it) }
                false
            }
        } catch (e: Exception) {
            _lastError.value = "Set stroke error: ${e.message}"
            log("Stroke error: ${e.message}")
            false
        }
    }

    /**
     * Drive the device to the user-coord retracted end and stop it there.
     *
     * Primary path uses HDSP (direct position) so the move is a single
     * slow slide. If the HDSP call fails — older firmware, transient error,
     * etc. — fall back to HAMP with a degenerate `slide(0, 0)` range so the
     * device parks without an oscillating stroke. Invert flips which end
     * the user considers retracted: device position 0 by default, 100 when
     * inverted.
     */
    private suspend fun parkAtRetracted() {
        if (_connectionState.value != ConnectionState.CONNECTED) return
        // "Retracted" is the user-facing retracted edge of the configured
        // global stroke band — NOT a hard 0. Respect the safety floor.
        // In user coords that's globalStrokeMin (or globalStrokeMax when
        // inverted); the slide-range / HDSP target below are in device
        // coords, so apply the invert flip the same way setStrokeRange does.
        val userMin = globalStrokeMin
        val userMax = globalStrokeMax
        val devMin: Int
        val devMax: Int
        val target: Int
        if (invertStroke) {
            devMin = 100 - userMax
            devMax = 100 - userMin
            target = devMax  // user's "retracted" = device's extended end
        } else {
            devMin = userMin
            devMax = userMax
            target = devMin  // user's "retracted" = device's retracted end
        }
        val rangeOpened = transport.park(devMin, devMax, target)
        if (rangeOpened) {
            _strokeMin.value = userMin
            _strokeMax.value = userMax
        }
        _isRunning.value = false
        _currentSpeed.value = 0
    }

    suspend fun adjustSpeed(delta: Int): Boolean {
        val newSpeed = (_currentSpeed.value + delta).coerceIn(0, 100)
        return setSpeed(newSpeed)
    }

    private suspend fun refreshState() {
        try {
            transport.readVelocity()?.let { _currentSpeed.value = it }
        } catch (_: Exception) {
            // best-effort
        }
    }

    fun clearLog() {
        _debugLog.value = emptyList()
    }
}

interface HandyV2ApiService {
    @GET("connected")
    suspend fun checkConnection(
        @Header("X-Connection-Key") connectionKey: String
    ): Response<ConnectedResponse>

    @PUT("mode")
    suspend fun setMode(
        @Header("X-Connection-Key") connectionKey: String,
        @Body request: ModeRequest
    ): Response<Unit>

    @PUT("hamp/start")
    suspend fun hampStart(
        @Header("X-Connection-Key") connectionKey: String
    ): Response<Unit>

    @PUT("hamp/stop")
    suspend fun hampStop(
        @Header("X-Connection-Key") connectionKey: String
    ): Response<Unit>

    @PUT("hamp/velocity")
    suspend fun setVelocity(
        @Header("X-Connection-Key") connectionKey: String,
        @Body request: VelocityRequest
    ): Response<Unit>

    @GET("hamp/velocity")
    suspend fun getVelocity(
        @Header("X-Connection-Key") connectionKey: String
    ): Response<VelocityInfo>

    @PUT("slide")
    suspend fun setSlide(
        @Header("X-Connection-Key") connectionKey: String,
        @Body request: SlideRangeRequest
    ): Response<Unit>

    /**
     * HDSP "go to position over duration" command. Used by parkAtRetracted
     * so the device slides smoothly to position 0 without HAMP's pumping.
     * See HANDY_API_NOTES.md for mode semantics.
     */
    @PUT("hdsp/xpt")
    suspend fun hdspXpt(
        @Header("X-Connection-Key") connectionKey: String,
        @Body request: HdspPositionRequest
    ): Response<Unit>
}

data class ConnectedResponse(val connected: Boolean)
data class ModeRequest(val mode: Int)
data class VelocityRequest(val velocity: Double)
data class VelocityInfo(val velocity: Double)
data class SlideRangeRequest(val min: Int, val max: Int)
data class HdspPositionRequest(
    val position: Int,
    val duration: Int,
    val stopOnTarget: Boolean = true,
    // Production Handy clients (MultiFunPlayer, defucilis/thehandy,
    // iwakan/teledong) all send true here. Without it some firmwares
    // appear to accept the response but never trigger the move.
    val immediateResponse: Boolean = true,
)
