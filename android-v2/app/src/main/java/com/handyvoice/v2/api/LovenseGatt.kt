package com.handyvoice.v2.api

/**
 * Android-free seam under [LovenseBleTransport]: the handful of BLE operations
 * the transport needs, so its connect/state logic runs in JVM tests against a
 * fake toy. The Android implementation is [AndroidLovenseGattPort].
 */
data class ScanHit(val address: String, val name: String?)

enum class WriteResult {
    /** Accepted and (for write-with-response) acknowledged. */
    OK,

    /** The stack refused to start the write (busy, bad state). Nothing was sent. */
    REJECTED,

    /** Started but failed, errored, or the link dropped. */
    FAILED,
}

enum class Discovery { OK, FAILED, NOT_LOVENSE }

/** Per-session callbacks from the GATT layer. */
interface GattSink {
    /** The link is gone (not called for a [LovenseGattSession.close] we made). */
    fun onDisconnected()

    /** Raw ASCII chunk from the RX notification characteristic. */
    fun onNotify(text: String)
}

interface LovenseGattSession {
    val deviceName: String?

    /** The GATT service UUID in use (which protocol generation), once discovered. */
    val serviceUuid: String? get() = null

    /** True when the channel was picked by characteristic properties, not a known UUID layout. */
    val channelGuessed: Boolean get() = false

    /** Every service UUID the toy exposed, for the log when none could be used. */
    val discoveredServices: List<String> get() = emptyList()

    /** Discover services and pick the Lovense TX/RX channel. */
    suspend fun discover(): Discovery

    /** Enable RX notifications; false if the CCCD write did not succeed. */
    suspend fun enableNotifications(): Boolean

    suspend fun write(text: String): WriteResult

    fun close()
}

interface LovenseGattPort {
    /** Non-null means a connect cannot start (permission, Bluetooth off). */
    fun preconnectError(): String?

    suspend fun scan(): ScanHit?

    /**
     * Open a GATT link to [address]. Null if it could not be established in
     * time. Throws [IllegalArgumentException] for a malformed address.
     */
    suspend fun open(address: String, sink: GattSink): LovenseGattSession?
}
