package com.handyvoice.v2.api

import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Handy V2 REST transport. Code moved from HandyV2ApiClient; behaviour is
 * unchanged. Tests inject a fake [HandyV2ApiService].
 */
class HandyRestTransport(
    private val api: HandyV2ApiService = createDefaultService(),
) : DeviceTransport {

    companion object {
        private const val BASE_URL = "https://www.handyfeeling.com/api/handy/v2/"

        fun createDefaultService(): HandyV2ApiService {
            val okHttpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
                .build()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(HandyV2ApiService::class.java)
        }
    }

    override val kind = "handy"
    override var listener: TransportListener? = null

    private var connectionKey: String = ""

    private fun log(m: String) { listener?.onLog(m) }

    override fun setConnectionKey(key: String) {
        connectionKey = key.trim()
    }

    override fun preflightError(): String? =
        if (connectionKey.isBlank()) "Connection key required" else null

    override suspend fun connect(): TransportResult {
        val response = api.checkConnection(connectionKey)
        if (response.isSuccessful && response.body()?.connected == true) {
            log("Connected.")
            val modeResponse = api.setMode(connectionKey, ModeRequest(0))
            return if (modeResponse.isSuccessful) {
                log("HAMP mode set.")
                TransportResult.OK
            } else {
                log("Failed to set mode: ${modeResponse.code()}")
                TransportResult(false, "Failed to set mode")
            }
        }
        log("Device not connected")
        return TransportResult(false, "Device not connected. Make sure your Handy is online.")
    }

    override suspend fun disconnect() {}

    override suspend fun start(): TransportResult {
        val response = api.hampStart(connectionKey)
        if (response.isSuccessful) return TransportResult.OK
        val body = response.errorBody()?.string() ?: "no body"
        return TransportResult(
            false,
            "Start failed: ${response.code()}",
            "Start failed: code=${response.code()} body=$body",
        )
    }

    override suspend fun stop(): TransportResult {
        val response = api.hampStop(connectionKey)
        return TransportResult(response.isSuccessful)
    }

    override suspend fun setVelocity(velocity: Int): TransportResult {
        val response = api.setVelocity(connectionKey, VelocityRequest(velocity.toDouble()))
        if (response.isSuccessful) return TransportResult.OK
        val body = response.errorBody()?.string() ?: "no body"
        return TransportResult(
            false,
            "Set speed failed: ${response.code()}",
            "Speed failed: code=${response.code()} body=$body",
        )
    }

    override suspend fun setSlide(min: Int, max: Int): TransportResult {
        val response = api.setSlide(connectionKey, SlideRangeRequest(min, max))
        if (response.isSuccessful) return TransportResult.OK
        val body = response.errorBody()?.string() ?: "no body"
        return TransportResult(
            false,
            "Set stroke failed (code=${response.code()})",
            "Stroke failed: code=${response.code()} body=$body",
        )
    }

    override suspend fun readVelocity(): Int? {
        val r = api.getVelocity(connectionKey)
        return if (r.isSuccessful) r.body()?.velocity?.toInt() else null
    }

    override suspend fun park(devMin: Int, devMax: Int, target: Int): Boolean {
        log("Parking to user-coord 0 (device $target, band $devMin..$devMax)...")
        val durationMs = 2500

        // HDSP position is clamped by the active slide range. Open the
        // range to the global safety band first so "position $target"
        // means what we expect.
        var rangeOpened = false
        runCatching {
            val openRange = api.setSlide(connectionKey, SlideRangeRequest(devMin, devMax))
            if (openRange.isSuccessful) {
                rangeOpened = true
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
        return rangeOpened
    }

    private suspend fun tryHdspPark(target: Int, durationMs: Int): Boolean {
        return try {
            val hdspMode = api.setMode(connectionKey, ModeRequest(2))
            if (!hdspMode.isSuccessful) {
                log("Park: HDSP mode switch failed code=${hdspMode.code()}")
                return false
            }
            log("HDSP mode set; sending xpt position=$target duration=${durationMs}ms.")
            // Small breath after the mode switch; some firmwares need a
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
     * range at the target end (0,0 or 100,100). Velocity is dropped to 0 so
     * we never pump even briefly.
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
            api.setVelocity(connectionKey, VelocityRequest(0.0))
            log("Parked via HAMP slide($target,$target).")
            true
        } catch (e: Exception) {
            log("Park HAMP error: ${e.message}")
            false
        }
    }
}
