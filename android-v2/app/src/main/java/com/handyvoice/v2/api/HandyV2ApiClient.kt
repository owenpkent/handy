package com.handyvoice.v2.api

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
     */
    suspend fun setStrokeRange(min: Int, max: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }

        val safeMin = min.coerceIn(0, 100)
        val safeMax = max.coerceIn(0, 100)

        return try {
            log("Setting stroke: $safeMin-$safeMax%")
            val response = api.setSlide(connectionKey, SlideRangeRequest(safeMin, safeMax))
            if (response.isSuccessful) {
                _strokeMin.value = safeMin
                _strokeMax.value = safeMax
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
}

data class ConnectedResponse(val connected: Boolean)
data class ModeRequest(val mode: Int)
data class VelocityRequest(val velocity: Double)
data class VelocityInfo(val velocity: Double)
data class SlideRangeRequest(val min: Int, val max: Int)
