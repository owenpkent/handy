package com.handyvoice.v2.api

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PUT
import java.util.concurrent.TimeUnit

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

class HandyV2ApiClient {

    companion object {
        private const val BASE_URL = "https://www.handyfeeling.com/api/handy/v2/"
        private const val MAX_LOG_LINES = 50
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

    private var connectionKey: String = ""

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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()

    private val api: HandyV2ApiService = retrofit.create(HandyV2ApiService::class.java)

    fun setConnectionKey(key: String) {
        connectionKey = key.trim()
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
        if (connectionKey.isBlank()) {
            _lastError.value = "Connection key required"
            return false
        }

        _connectionState.value = ConnectionState.CONNECTING
        log("Connecting...")

        return try {
            val response = api.checkConnection(connectionKey)
            if (response.isSuccessful && response.body()?.connected == true) {
                log("Connected.")
                val modeResponse = api.setMode(connectionKey, ModeRequest(0))
                if (modeResponse.isSuccessful) {
                    log("HAMP mode set.")
                    _connectionState.value = ConnectionState.CONNECTED
                    _lastError.value = null
                    refreshState()
                    parkAtRetracted()
                    true
                } else {
                    log("Failed to set mode: ${modeResponse.code()}")
                    _connectionState.value = ConnectionState.DISCONNECTED
                    _lastError.value = "Failed to set mode"
                    false
                }
            } else {
                log("Device not connected")
                _connectionState.value = ConnectionState.DISCONNECTED
                _lastError.value = "Device not connected. Make sure your Handy is online."
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
        _connectionState.value = ConnectionState.DISCONNECTED
        log("Disconnected")
    }

    suspend fun start(): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }

        return try {
            log("Starting...")
            val response = api.hampStart(connectionKey)
            if (response.isSuccessful) {
                _isRunning.value = true
                _lastError.value = null
                log("Started.")
                true
            } else {
                val body = response.errorBody()?.string() ?: "no body"
                _lastError.value = "Start failed: ${response.code()}"
                log("Start failed: code=${response.code()} body=$body")
                false
            }
        } catch (e: Exception) {
            _lastError.value = "Start error: ${e.message}"
            log("Start error: ${e.message}")
            false
        }
    }

    suspend fun stop(): Boolean {
        return try {
            log("Stopping...")
            val response = api.hampStop(connectionKey)
            _isRunning.value = false
            _lastError.value = null
            log("Stopped.")
            response.isSuccessful
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

        return try {
            log("Setting speed to $velocity%")
            val response = api.setVelocity(connectionKey, VelocityRequest(velocity.toDouble()))
            if (response.isSuccessful) {
                _currentSpeed.value = velocity
                _lastError.value = null
                true
            } else {
                val body = response.errorBody()?.string() ?: "no body"
                _lastError.value = "Set speed failed: ${response.code()}"
                log("Speed failed: code=${response.code()} body=$body")
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
            val response = api.setSlide(connectionKey, SlideRangeRequest(devMin, devMax))
            if (response.isSuccessful) {
                _strokeMin.value = userMin
                _strokeMax.value = userMax
                _lastError.value = null
                true
            } else {
                val body = response.errorBody()?.string() ?: "no body"
                _lastError.value = "Set stroke failed (code=${response.code()})"
                log("Stroke failed: code=${response.code()} body=$body")
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
        log("Parking to user-coord 0 (device $target, band $devMin..$devMax)...")
        val durationMs = 2500

        // HDSP position is clamped by the active slide range. Open the
        // range to the global safety band first so "position $target"
        // means what we expect.
        runCatching {
            val openRange = api.setSlide(connectionKey, SlideRangeRequest(devMin, devMax))
            if (openRange.isSuccessful) {
                _strokeMin.value = userMin
                _strokeMax.value = userMax
                log("Slide range set to device $devMin..$devMax for park.")
            } else {
                log("Park: set-range failed code=${openRange.code()}")
            }
        }

        val parked = tryHdspPark(target, durationMs)
        if (!parked) {
            log("HDSP path failed; trying HAMP slide($target,$target) fallback.")
            tryHampZeroPark(target)
        }
        // Ensure we leave the device in HAMP for the rest of the app.
        val backToHamp = runCatching { api.setMode(connectionKey, ModeRequest(0)) }
        backToHamp.getOrNull()?.let {
            if (!it.isSuccessful) log("Park: restore HAMP failed code=${it.code()}")
        }
        _isRunning.value = false
        _currentSpeed.value = 0
    }

    private suspend fun tryHdspPark(target: Int, durationMs: Int): Boolean {
        return try {
            val hdspMode = api.setMode(connectionKey, ModeRequest(2))
            if (!hdspMode.isSuccessful) {
                log("Park: HDSP mode switch failed code=${hdspMode.code()}")
                return false
            }
            log("HDSP mode set; sending xpt position=$target duration=${durationMs}ms.")
            // Small breath after the mode switch — some firmwares need a
            // moment before they accept HDSP commands.
            delay(120)
            val resp = api.hdspXpt(
                connectionKey,
                HdspPositionRequest(position = target, duration = durationMs)
            )
            if (!resp.isSuccessful) {
                val body = resp.errorBody()?.string() ?: "no body"
                log("Park: hdsp/xpt failed code=${resp.code()} body=$body")
                return false
            }
            log("hdsp/xpt accepted; waiting ${durationMs}ms for slide.")
            delay((durationMs + 200).toLong())
            log("Parked via HDSP.")
            true
        } catch (e: Exception) {
            log("Park HDSP error: ${e.message}")
            false
        }
    }

    /**
     * Fallback for firmwares without working HDSP: ask HAMP for a zero-width
     * range at the target end (0,0 or 100,100). On the Handy this collapses
     * the stroke and the firmware parks at that position. Velocity is
     * dropped to 0 so we never pump even briefly.
     */
    private suspend fun tryHampZeroPark(target: Int): Boolean {
        return try {
            val hampMode = api.setMode(connectionKey, ModeRequest(0))
            if (!hampMode.isSuccessful) {
                log("Park: HAMP mode failed code=${hampMode.code()}")
                return false
            }
            delay(120)
            val slide = api.setSlide(connectionKey, SlideRangeRequest(target, target))
            if (!slide.isSuccessful) {
                log("Park: HAMP slide($target,$target) failed code=${slide.code()}")
                return false
            }
            // Velocity 0 means no pumping even if firmware briefly starts.
            api.setVelocity(connectionKey, VelocityRequest(0.0))
            log("Parked via HAMP slide($target,$target).")
            true
        } catch (e: Exception) {
            log("Park HAMP error: ${e.message}")
            false
        }
    }

    suspend fun adjustSpeed(delta: Int): Boolean {
        val newSpeed = (_currentSpeed.value + delta).coerceIn(0, 100)
        return setSpeed(newSpeed)
    }

    private suspend fun refreshState() {
        try {
            val velocityResponse = api.getVelocity(connectionKey)
            if (velocityResponse.isSuccessful) {
                velocityResponse.body()?.let { _currentSpeed.value = it.velocity.toInt() }
            }
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
