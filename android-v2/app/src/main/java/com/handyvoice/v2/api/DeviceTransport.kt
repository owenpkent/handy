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

/** Keep-alive traffic a toy transport sends while connected (Lovense test panel setting). */
enum class KeepAliveMode(val id: String) {
    /** Poll Battery; only. */
    BATTERY_ONLY("battery"),

    /** Battery; plus a silent Vibrate:0; while stopped (default). */
    BATTERY_AND_VIBRATE0("battery_vibrate0"),

    /** Send nothing. */
    OFF("off");

    companion object {
        val DEFAULT = BATTERY_AND_VIBRATE0
        const val DEFAULT_INTERVAL_S = 30
        fun fromId(id: String?): KeepAliveMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Outcome of a raw probe command: [sent] false means nothing went on the wire ([error] says why). */
data class ProbeResult(val sent: Boolean, val reply: String?, val error: String? = null)

/** Point-in-time facts about a toy link, for the test panel. Times are epoch millis. */
data class ToyDiagnostics(
    val serviceUuid: String?,
    val deviceTypeReply: String?,
    val autoSwithReply: String?,
    val getAsReply: String?,
    val connectedAtMs: Long?,
    val lastMotionAtMs: Long?,
    val lastKeepAliveAtMs: Long?,
    val lastKeepAliveOk: Boolean?,
    val keepAliveMode: KeepAliveMode,
    val keepAliveIntervalS: Int,
    /** Set when the last link ended; null while connected or never connected. */
    val disconnectedAtMs: Long?,
    val disconnectUnexpected: Boolean?,
)

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

    /** Send one allow-listed raw command and await its reply (Lovense only). */
    suspend fun probe(cmd: String): ProbeResult = ProbeResult(false, null, "Not supported by this device.")

    /** Zero every probe candidate (Lovense only) and resume the normal keep-alive. */
    suspend fun probeStopAll() {}

    /** Change keep-alive behaviour live, no reconnect (Lovense only). */
    fun setKeepAlive(mode: KeepAliveMode, intervalS: Int) {}

    fun diagnostics(): ToyDiagnostics? = null
}
