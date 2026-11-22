package com.handyvoice.v2.api

/** Outcome of one wire-level call. [error] feeds the client's lastError; [detail] goes to the debug log. */
data class TransportResult(
    val ok: Boolean,
    val error: String? = null,
    val detail: String? = null,
) {
    companion object {
        val OK = TransportResult(true)
    }
}

/** Callbacks a transport uses to reach the client (log, battery, unsolicited disconnect). */
interface TransportListener {
    fun onLog(message: String)
    fun onBattery(percent: Int?)
    fun onDeviceName(name: String?)

    /** A non-fatal problem the user should see (e.g. link-loss protection not confirmed). */
    fun onWarning(message: String) {}

    /** The link dropped without a disconnect() call. [reason] is user-facing. */
    fun onUnexpectedDisconnect(reason: String)
}

/**
 * Wire-level device access below [HandyV2ApiClient]. All policy (ramps, epochs,
 * invert, caps, state flows) stays in the client; a transport only moves bytes.
 * Methods may throw; the client wraps them with its own error handling.
 */
interface DeviceTransport {
    /** Stable id: "handy" or "lovense". */
    val kind: String

    var listener: TransportListener?

    fun setConnectionKey(key: String) {}

    /** Non-null means connecting cannot even start (e.g. no key); shown as lastError. */
    fun preflightError(): String? = null

    suspend fun connect(): TransportResult
    suspend fun disconnect()

    suspend fun start(): TransportResult
    suspend fun stop(): TransportResult

    /** Velocity 0..100 in design space. */
    suspend fun setVelocity(velocity: Int): TransportResult

    /** Stroke window in device coordinates (invert already applied). */
    suspend fun setSlide(min: Int, max: Int): TransportResult

    /** Current device velocity if the device can report it, else null. */
    suspend fun readVelocity(): Int? = null

    /**
     * Connect-time park to device position [target] within [devMin]..[devMax].
     * Returns true if the slide range was applied (so the client may cache it).
     */
    suspend fun park(devMin: Int, devMax: Int, target: Int): Boolean = false
}
