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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/** Runtime permissions and a factory for the Android-backed transport. */
object LovenseBle {
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

    fun createTransport(
        context: Context,
        savedAddress: String?,
        onPaired: (address: String, name: String) -> Unit,
    ) = LovenseBleTransport(AndroidLovenseGattPort(context.applicationContext), savedAddress, onPaired)
}

/** Real BLE access via [BluetoothGatt]. Not JVM-testable; the logic above it is. */
@SuppressLint("MissingPermission")
class AndroidLovenseGattPort(private val appContext: Context) : LovenseGattPort {

    private companion object {
        const val SCAN_TIMEOUT_MS = 10_000L
        const val CONNECT_TIMEOUT_MS = 10_000L
        const val OP_TIMEOUT_MS = 1_000L
        const val DISCOVER_TIMEOUT_MS = 5_000L
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private fun adapter(): BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    override fun preconnectError(): String? {
        if (!LovenseBle.hasPermissions(appContext)) {
            return "Bluetooth permission needed to connect to the toy."
        }
        val a = adapter()
        if (a == null || !a.isEnabled) return "Bluetooth is off. Turn it on and try again."
        return null
    }

    override suspend fun scan(): ScanHit? {
        val scanner = adapter()?.bluetoothLeScanner ?: return null
        val found = CompletableDeferred<ScanResult>()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val n = result.scanRecord?.deviceName ?: result.device.name
                if (LovenseProtocol.isLovenseName(n)) found.complete(result)
            }

            override fun onScanFailed(errorCode: Int) { found.cancel() }
        }
        val hit = try {
            scanner.startScan(cb)
            withTimeoutOrNull(SCAN_TIMEOUT_MS) { runCatching { found.await() }.getOrNull() }
        } finally {
            runCatching { scanner.stopScan(cb) }
        } ?: return null
        return ScanHit(hit.device.address, hit.scanRecord?.deviceName ?: hit.device.name)
    }

    override suspend fun open(address: String, sink: GattSink): LovenseGattSession? {
        val device = adapter()?.getRemoteDevice(address) ?: return null
        val session = AndroidSession(sink)
        // Until ownership passes to the caller, this method must close the
        // client on every exit, including cancellation mid-await; otherwise a
        // canceled attempt leaks a GATT client (and maybe a live link).
        var handedOff = false
        try {
            val g = device.connectGatt(appContext, false, session.callback, BluetoothDevice.TRANSPORT_LE)
            session.gatt = g
            val linked = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { session.connectWait.await() } == true
            if (!linked) return null
            handedOff = true
            return session
        } finally {
            if (!handedOff) session.close()
        }
    }

    private class CallResult(val started: Boolean, val status: Int?)

    private class AndroidSession(private val sink: GattSink) : LovenseGattSession {
        @Volatile var gatt: BluetoothGatt? = null
        val connectWait = CompletableDeferred<Boolean>()
        @Volatile private var pending: CompletableDeferred<Int>? = null
        @Volatile private var closedByUs = false
        private var tx: BluetoothGattCharacteristic? = null
        private var rx: BluetoothGattCharacteristic? = null

        override val deviceName: String? get() = gatt?.device?.name
        @Volatile override var serviceUuid: String? = null
            private set

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectWait.complete(true)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    connectWait.complete(false)
                    pending?.complete(-1)
                    if (!closedByUs) sink.onDisconnected()
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
                c.value?.let { sink.onNotify(String(it, Charsets.US_ASCII)) }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
            ) {
                sink.onNotify(String(value, Charsets.US_ASCII))
            }
        }

        /** One GATT operation. [CallResult.started] false = the stack refused to initiate it. */
        private suspend fun gattCall(timeoutMs: Long, initiate: () -> Boolean): CallResult {
            val d = CompletableDeferred<Int>()
            pending = d
            try {
                if (!initiate()) return CallResult(false, null)
                return CallResult(true, withTimeoutOrNull(timeoutMs) { d.await() })
            } finally {
                if (pending === d) pending = null
            }
        }

        override suspend fun discover(): Discovery {
            val g = gatt ?: return Discovery.FAILED
            val r = gattCall(DISCOVER_TIMEOUT_MS) { g.discoverServices() }
            if (!r.started || r.status != BluetoothGatt.GATT_SUCCESS) return Discovery.FAILED
            for (svc in g.services) {
                val ch = LovenseProtocol.channelFor(svc.uuid) ?: continue
                val t = svc.getCharacteristic(ch.second)
                val rr = svc.getCharacteristic(ch.third)
                if (t != null && rr != null) {
                    tx = t
                    rx = rr
                    serviceUuid = svc.uuid.toString()
                    return Discovery.OK
                }
            }
            return Discovery.NOT_LOVENSE
        }

        override suspend fun enableNotifications(): Boolean {
            val g = gatt ?: return false
            val rxc = rx ?: return false
            g.setCharacteristicNotification(rxc, true)
            val d = rxc.getDescriptor(CCCD) ?: return true
            val r = gattCall(OP_TIMEOUT_MS) { writeDescriptor(g, d) }
            return r.started && r.status == BluetoothGatt.GATT_SUCCESS
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

        override suspend fun write(text: String): WriteResult {
            val g = gatt ?: return WriteResult.FAILED
            val c = tx ?: return WriteResult.FAILED
            val bytes = text.toByteArray(Charsets.US_ASCII)
            val withResponse = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
            val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            val r = gattCall(OP_TIMEOUT_MS) {
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
            return when {
                !r.started -> WriteResult.REJECTED
                r.status == BluetoothGatt.GATT_SUCCESS -> WriteResult.OK
                // Write-without-response may never call back; a timeout there counts as sent.
                r.status == null && !withResponse -> WriteResult.OK
                else -> WriteResult.FAILED
            }
        }

        override fun close() {
            closedByUs = true
            val g = gatt
            gatt = null
            tx = null
            rx = null
            runCatching { g?.disconnect() }
            runCatching { g?.close() }
        }
    }
}
