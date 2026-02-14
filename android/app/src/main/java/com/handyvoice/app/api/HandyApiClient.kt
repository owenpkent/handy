package com.handyvoice.app.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

class HandyApiClient {
    
    companion object {
        private const val BASE_URL = "https://www.handyfeeling.com/api/handy-rest/v3/"
    }
    
    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _currentSpeed = MutableStateFlow(50)
    val currentSpeed: StateFlow<Int> = _currentSpeed.asStateFlow()
    
    private val _currentStroke = MutableStateFlow(100)
    val currentStroke: StateFlow<Int> = _currentStroke.asStateFlow()
    
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    
    private var apiKey: String = ""
    private var connectionKey: String = ""
    
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        })
        .build()
    
    private val retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
    
    private val api: HandyApiService = retrofit.create(HandyApiService::class.java)
    
    fun setCredentials(apiKey: String, connectionKey: String) {
        this.apiKey = apiKey
        this.connectionKey = connectionKey
    }
    
    suspend fun connect(): Boolean {
        if (apiKey.isBlank() || connectionKey.isBlank()) {
            _lastError.value = "API key and connection key required"
            return false
        }
        
        _connectionState.value = ConnectionState.CONNECTING
        
        return try {
            val response = api.getDeviceInfo(apiKey, connectionKey)
            if (response.isSuccessful) {
                _connectionState.value = ConnectionState.CONNECTED
                _lastError.value = null
                // Get current values
                refreshState()
                true
            } else {
                _connectionState.value = ConnectionState.DISCONNECTED
                _lastError.value = "Connection failed: ${response.code()}"
                false
            }
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.DISCONNECTED
            _lastError.value = "Connection error: ${e.message}"
            false
        }
    }
    
    suspend fun disconnect() {
        stop()
        _connectionState.value = ConnectionState.DISCONNECTED
    }
    
    suspend fun start(): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }
        
        return try {
            val response = api.startDevice(apiKey, connectionKey)
            if (response.isSuccessful) {
                _isRunning.value = true
                _lastError.value = null
                true
            } else {
                _lastError.value = "Start failed: ${response.code()}"
                false
            }
        } catch (e: Exception) {
            _lastError.value = "Start error: ${e.message}"
            false
        }
    }
    
    suspend fun stop(): Boolean {
        return try {
            val response = api.stopDevice(apiKey, connectionKey)
            _isRunning.value = false
            _lastError.value = null
            response.isSuccessful
        } catch (e: Exception) {
            _isRunning.value = false
            _lastError.value = "Stop error: ${e.message}"
            false
        }
    }
    
    suspend fun setSpeed(percent: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }
        
        val velocity = percent.coerceIn(0, 100).toDouble()
        
        return try {
            val response = api.setVelocity(apiKey, connectionKey, VelocityRequest(velocity))
            if (response.isSuccessful) {
                _currentSpeed.value = percent.coerceIn(0, 100)
                _lastError.value = null
                true
            } else {
                _lastError.value = "Set speed failed: ${response.code()}"
                false
            }
        } catch (e: Exception) {
            _lastError.value = "Set speed error: ${e.message}"
            false
        }
    }
    
    suspend fun setStroke(percent: Int): Boolean {
        if (_connectionState.value != ConnectionState.CONNECTED) {
            _lastError.value = "Not connected"
            return false
        }
        
        // Stroke is set via slide min/max positions (0.0 to 1.0)
        // For stroke percent, we adjust the range symmetrically from center
        val strokeFraction = percent.coerceIn(0, 100) / 100.0
        val min = (1.0 - strokeFraction) / 2.0
        val max = 1.0 - min
        
        return try {
            val minResponse = api.setSlideMin(apiKey, connectionKey, SlideRequest(min))
            val maxResponse = api.setSlideMax(apiKey, connectionKey, SlideRequest(max))
            
            if (minResponse.isSuccessful && maxResponse.isSuccessful) {
                _currentStroke.value = percent.coerceIn(0, 100)
                _lastError.value = null
                true
            } else {
                _lastError.value = "Set stroke failed"
                false
            }
        } catch (e: Exception) {
            _lastError.value = "Set stroke error: ${e.message}"
            false
        }
    }
    
    suspend fun adjustSpeed(delta: Int): Boolean {
        val newSpeed = (_currentSpeed.value + delta).coerceIn(0, 100)
        return setSpeed(newSpeed)
    }
    
    private suspend fun refreshState() {
        try {
            val velocityResponse = api.getVelocity(apiKey, connectionKey)
            if (velocityResponse.isSuccessful) {
                velocityResponse.body()?.let {
                    _currentSpeed.value = it.velocity.toInt()
                }
            }
            
            val slideResponse = api.getSlide(apiKey, connectionKey)
            if (slideResponse.isSuccessful) {
                slideResponse.body()?.let {
                    val strokePercent = ((it.max - it.min) * 100).toInt()
                    _currentStroke.value = strokePercent
                }
            }
        } catch (e: Exception) {
            // Ignore refresh errors
        }
    }
}

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED
}
