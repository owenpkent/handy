package com.handyvoice.v2.api

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Lovense toy over BLE, using [BluetoothGatt] directly. Speaks the ASCII
 * protocol in [LovenseProtocol]. Velocity 0..100 maps to Vibrate levels 0..20;
 * start() and setSlide() are no-ops (Lovense has neither).
 *
 * @param savedAddress previously paired toy address, or null to scan.
 * @param onPaired called once a toy is found by scan (address, name) so the
 *        caller can persist it.
 */
@SuppressLint("MissingPermission")
class LovenseBleTransport(
    context: Context,
    private val savedAddress: String?,
    private val onPaired: (address: String, name: String) -> Unit,
) : DeviceTransport {

    companion object {
        private const val SCAN_TIMEOUT_MS = 10_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val OP_TIMEOUT_MS = 1_000L
        private const val DISCOVER_TIMEOUT_MS = 5_000L
        private const val BATTERY_POLL_MS = 30_000L
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Runtime permissions needed before connecting on this API level. */
        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= 31) {
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            }

        fun hasPermissions(context: Context): Boolean = requiredPermissions().all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    override val kind = "lovense"
    override var listener: TransportListener? = null

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializes every GATT operation. */
    private val opMutex = Mutex()
    @Volatile private var pending: CompletableDeferred<Int>? = null
    @Volatile private var connectWait: CompletableDeferred<Boolean>? = null

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    @Volatile private var connected = false
    @Volatile private var closing = false
    private var lastLevel = -1
    private var lastBattery: Int? = null
    private var pollJob: Job? = null

    private val replyBuffer = LovenseProtocol.ReplyBuffer()
    private val replies = Channel<String>(16, BufferOverflow.DROP_OLDEST)

    private fun log(m: String) { listener?.onLog(m) }

    // ---- GATT callback ----------------------------------------------------

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectWait?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectWait?.complete(false)
                pending?.complete(-1)
                if (connected && !closing) {
                    handleUnexpectedDisconnect()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { pending?.complete(status) }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            handleNotify(c.value)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            handleNotify(value)
        }
    }

    private fun handleNotify(value: ByteArray?) {
        if (value == null) return
        for (r in replyBuffer.feed(String(value, Charsets.US_ASCII))) replies.trySend(r)
    }

    private fun handleUnexpectedDisconnect() {
        connected = false
        pollJob?.cancel()
        closeGatt()
        lastLevel = -1
        listener?.onUnexpectedDisconnect("Lovense toy disconnected.")
    }

    private fun closeGatt() {
        val g = gatt
        gatt = null
        tx = null
        rx = null
        runCatching { g?.close() }
    }

    // ---- connect ----------------------------------------------------------

    override fun preflightError(): String? = null

    override suspend fun connect(): TransportResult = opMutex.withLock {
        if (!hasPermissions(appContext)) {
            return TransportResult(false, "Bluetooth permission needed to connect to the toy.")
        }
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            return TransportResult(false, "Bluetooth is off. Turn it on and try again.")
        }
        resetLink()

        val device: BluetoothDevice
        var name: String?
        if (!savedAddress.isNullOrBlank()) {
            log("Lovense: connecting to saved toy $savedAddress")
            device = try {
                adapter.getRemoteDevice(savedAddress)
            } catch (e: IllegalArgumentException) {
                return TransportResult(false, "Saved toy address is invalid. Use Forget toy in Settings.")
            }
            name = null
        } else {
            log("Lovense: scanning for a toy...")
            val found = scan(adapter) ?: return TransportResult(
                false,
                "No Lovense toy found. Turn it on (hold the button until it blinks) and try again.",
            )
            device = found.device
            name = found.scanRecord?.deviceName ?: found.device.name
            log("Lovense: found ${name ?: "toy"} ${device.address}")
        }

        closing = false
        val waiter = CompletableDeferred<Boolean>()
        connectWait = waiter
        val g = device.connectGatt(appContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        gatt = g
        val linked = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { waiter.await() } == true
        connectWait = null
        if (!linked) {
            closeGatt()
            return TransportResult(
                false,
                if (savedAddress.isNullOrBlank()) "Could not connect to the toy."
                else "Could not reach the saved toy. Turn it on, or use Forget toy in Settings to pair a new one.",
            )
        }

        val disc = gattCall(DISCOVER_TIMEOUT_MS) { g.discoverServices() }
        if (disc != BluetoothGatt.GATT_SUCCESS) {
            closeGatt()
            return TransportResult(false, "Toy service discovery failed.")
        }
        var chosen: Triple<BluetoothGattCharacteristic, BluetoothGattCharacteristic, UUID>? = null
        for (svc in g.services) {
            val ch = LovenseProtocol.channelFor(svc.uuid) ?: continue
            val t = svc.getCharacteristic(ch.second)
            val r = svc.getCharacteristic(ch.third)
            if (t != null && r != null) {
                chosen = Triple(t, r, svc.uuid)
                break
            }
        }
        if (chosen == null) {
            closeGatt()
            return TransportResult(false, "This device does not look like a Lovense toy.")
        }
        tx = chosen.first
        rx = chosen.second
        log("Lovense: service ${chosen.third}")

        // Enable notifications on RX.
        val rxc = chosen.second
        g.setCharacteristicNotification(rxc, true)
        rxc.getDescriptor(CCCD)?.let { d ->
            val st = gattCall(OP_TIMEOUT_MS) { writeDescriptor(g, d) }
            if (st != BluetoothGatt.GATT_SUCCESS) log("Lovense: enabling notifications returned $st")
        }

        connected = true
        lastLevel = -1
        lastBattery = null

        // Identify and arm the safety setting. Replies are logged.
        sendLocked(LovenseProtocol.CMD_DEVICE_TYPE, awaitReply = true)?.let { reply ->
            val info = LovenseProtocol.parseDeviceType(reply)
            log("Lovense: DeviceType ${info?.let { "model ${it.model} fw ${it.firmware}" } ?: reply}")
        }
        sendLocked(LovenseProtocol.CMD_AUTO_SWITCH, awaitReply = true)?.let { log("Lovense: AutoSwith reply $it") }

        if (name == null) name = g.device.name
        if (savedAddress.isNullOrBlank()) {
            onPaired(device.address, name ?: "Lovense toy")
        }
        listener?.onDeviceName(name ?: "Lovense toy")
        startBatteryPoll()
        return TransportResult.OK
    }

    private fun resetLink() {
        pollJob?.cancel()
        closeGatt()
        connected = false
        replyBuffer.clear()
        while (replies.tryReceive().isSuccess) { /* drain */ }
    }

    private suspend fun scan(adapter: BluetoothAdapter): ScanResult? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.scanRecord?.deviceName ?: result.device.name
                if (LovenseProtocol.isLovenseName(n)) found.complete(result)
            }

            override fun onScanFailed(errorCode: Int) {
                log("Lovense: scan failed, code $errorCode")
                found.cancel()
            }
        }
        return try {
            scanner.startScan(cb)
            withTimeoutOrNull(SCAN_TIMEOUT_MS) { runCatching { found.await() }.getOrNull() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        }
    }

    // ---- GATT helpers -----------------------------------------------------

    /** Runs one GATT operation and waits for its callback status; null if it could not start or timed out. */
    private suspend fun gattCall(timeoutMs: Long, initiate: () -> Boolean): Int? {
        val d = CompletableDeferred<Int>()
        pending = d
        if (!initiate()) {
            pending = null
            return null
        }
        val r = withTimeoutOrNull(timeoutMs) { d.await() }
        pending = null
        return r
    }

    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Boolean =
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(d)
            }
        }

    /** Write one command. Caller must hold [opMutex]. Returns the reply if [awaitReply]. */
    private suspend fun sendLocked(cmd: String, awaitReply: Boolean = false): String? {
        val g = gatt
        val c = tx
        if (g == null || c == null || !connected) return null
        while (replies.tryReceive().isSuccess) { /* drop stale replies */ }
        writeCharacteristic(g, c, cmd)
        return if (awaitReply) withTimeoutOrNull(OP_TIMEOUT_MS) { replies.receive() } else null
    }

    private suspend fun writeCharacteristic(g: BluetoothGatt, c: BluetoothGattCharacteristic, cmd: String): Boolean {
        val bytes = cmd.toByteArray(Charsets.US_ASCII)
        val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val status = gattCall(OP_TIMEOUT_MS) {
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type) == 0
            } else {
                @Suppress("DEPRECATION")
                run {
                    c.writeType = type
                    c.value = bytes
                    g.writeCharacteristic(c)
                }
            }
        }
        // Write-without-response may never call back; treat a timeout there as sent.
        return status == BluetoothGatt.GATT_SUCCESS || (!withResponse && status == null)
    }

    private suspend fun command(cmd: String): TransportResult = opMutex.withLock {
        if (!connected) return@withLock TransportResult(false, "Lovense toy not connected.")
        val g = gatt
        val c = tx
        if (g == null || c == null) return@withLock TransportResult(false, "Lovense toy not connected.")
        while (replies.tryReceive().isSuccess) { /* drop stale replies */ }
        if (writeCharacteristic(g, c, cmd)) TransportResult.OK
        else TransportResult(false, "Lovense write failed.", "Lovense: write failed for $cmd")
    }

    // ---- battery keep-awake ------------------------------------------------

    private fun startBatteryPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && connected) {
                val reply = opMutex.withLock { sendLocked(LovenseProtocol.CMD_BATTERY, awaitReply = true) }
                val pct = reply?.let { LovenseProtocol.parseBattery(it) }
                if (pct != null && pct != lastBattery) {
                    lastBattery = pct
                    log("Lovense: battery $pct%")
                    listener?.onBattery(pct)
                }
                delay(BATTERY_POLL_MS)
            }
        }
    }

    // ---- DeviceTransport ---------------------------------------------------

    override suspend fun disconnect() {
        closing = true
        pollJob?.cancel()
        opMutex.withLock {
            if (connected) {
                // Best effort: make sure it is not left vibrating.
                val g = gatt
                val c = tx
                if (g != null && c != null) runCatching { writeCharacteristic(g, c, LovenseProtocol.CMD_STOP) }
            }
            connected = false
            runCatching { gatt?.disconnect() }
            closeGatt()
            lastLevel = -1
        }
        listener?.onBattery(null)
    }

    override suspend fun start(): TransportResult =
        if (connected) TransportResult.OK else TransportResult(false, "Lovense toy not connected.")

    override suspend fun stop(): TransportResult {
        val r = command(LovenseProtocol.CMD_STOP)
        if (r.ok) lastLevel = 0
        return r
    }

    override suspend fun setVelocity(velocity: Int): TransportResult {
        val level = LovenseProtocol.levelForVelocity(velocity)
        if (level == lastLevel) return TransportResult.OK
        val r = command(LovenseProtocol.vibrate(level))
        if (r.ok) lastLevel = level
        return r
    }

    override suspend fun setSlide(min: Int, max: Int): TransportResult = TransportResult.OK

    override suspend fun park(devMin: Int, devMax: Int, target: Int): Boolean = true
}
