package com.handyvoice.v2.api

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

/**
 * Lovense toy over BLE. Speaks the ASCII protocol in [LovenseProtocol] through
 * a [LovenseGattPort] (Android: [AndroidLovenseGattPort]; tests: a fake toy).
 * Velocity 0..100 maps to Vibrate levels 0..20; setSlide() is a no-op.
 *
 * Motion contract (mirrors the Handy): the toy only vibrates between start()
 * and stop(). A speed change while stopped is remembered and applied by the
 * next start(); it never starts the toy by itself.
 *
 * @param savedAddress previously paired toy address, or null to scan. After a
 *        scan pairs a toy the transport keeps that address for later
 *        reconnects in the same process (no re-scan until a new transport).
 * @param onPaired called once a toy is found by scan so the caller can persist it.
 */
class LovenseBleTransport(
    private val port: LovenseGattPort,
    savedAddress: String?,
    private val onPaired: (address: String, name: String) -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: () -> Long = System::currentTimeMillis,
) : DeviceTransport {

    companion object {
        private const val OP_TIMEOUT_MS = 1_000L
        private const val DROPPED = "Toy disconnected during setup. Try again."
    }

    override val kind = "lovense"
    override var listener: TransportListener? = null

    /** Address used for reconnects; null until a toy is saved or paired. */
    @Volatile var address: String? = savedAddress?.takeIf { it.isNotBlank() }
        private set

    /** Serializes every GATT operation. */
    private val opMutex = Mutex()
    private val stateLock = Any()

    private var session: LovenseGattSession? = null
    @Volatile private var generation = 0
    @Volatile private var connected = false   // link usable for writes
    @Volatile private var ready = false       // setup finished; drops are "unexpected"
    @Volatile private var linkLost = false    // current session's link dropped
    @Volatile private var closing = false

    // Motion state, guarded by opMutex.
    private var lastLevel = -1      // last level the toy acknowledged; -1 = unknown
    private var running = false
    private var desiredLevel = 0    // level to apply while running

    private var lastBattery: Int? = null
    private var pollJob: Job? = null

    // Keep-alive config; read by the poll loop each pass, changed live by setKeepAlive.
    @Volatile private var keepAliveMode = KeepAliveMode.DEFAULT
    @Volatile private var keepAliveIntervalS = KeepAliveMode.DEFAULT_INTERVAL_S

    // Probe names currently sent at a nonzero level; guarded by opMutex. While
    // non-empty the keep-alive never forces Vibrate:0; (it would end the experiment).
    private val probing = HashSet<String>()

    // Diagnostics for the test panel.
    @Volatile private var diagService: String? = null
    @Volatile private var diagDeviceType: String? = null
    @Volatile private var diagAutoSwith: String? = null
    @Volatile private var diagGetAs: String? = null
    @Volatile private var connectedAtMs: Long? = null
    @Volatile private var lastMotionAtMs: Long? = null
    @Volatile private var lastKeepAliveAtMs: Long? = null
    @Volatile private var lastKeepAliveOk: Boolean? = null
    @Volatile private var disconnectedAtMs: Long? = null
    @Volatile private var disconnectUnexpected: Boolean? = null

    private val replyBuffer = LovenseProtocol.ReplyBuffer()
    private val replies = Channel<String>(16, BufferOverflow.DROP_OLDEST)

    private fun log(m: String) { listener?.onLog(m) }

    // ---- link events -------------------------------------------------------

    private fun onLinkDown(gen: Int) {
        var unexpected = false
        synchronized(stateLock) {
            if (gen != generation) return
            linkLost = true
            connected = false
            unexpected = ready && !closing
            if (unexpected) ready = false
        }
        replies.trySend("") // wake any reply wait
        if (unexpected) handleUnexpectedDisconnect()
    }

    private fun handleUnexpectedDisconnect() {
        pollJob?.cancel()
        closeSession()
        lastLevel = -1
        running = false
        desiredLevel = 0
        probing.clear()
        disconnectedAtMs = clock()
        disconnectUnexpected = true
        listener?.onUnexpectedDisconnect("Lovense toy disconnected.")
    }

    private fun closeSession() {
        val s = session
        session = null
        runCatching { s?.close() }
    }

    // ---- connect -----------------------------------------------------------

    override fun preflightError(): String? = null

    override suspend fun connect(): TransportResult = opMutex.withLock { connectLocked() }

    private suspend fun connectLocked(): TransportResult {
        port.preconnectError()?.let { return TransportResult(false, it) }
        resetLink()
        val myGen = synchronized(stateLock) {
            generation += 1
            linkLost = false
            ready = false
            closing = false
            generation
        }

        val saved = address
        val target: String
        var name: String?
        if (saved != null) {
            log("Lovense: connecting to saved toy $saved")
            target = saved
            name = null
        } else {
            log("Lovense: scanning for a toy...")
            val hit = port.scan() ?: return TransportResult(
                false,
                "No Lovense toy found. Turn it on (hold the button until it blinks) and try again.",
            )
            target = hit.address
            name = hit.name
            log("Lovense: found ${name ?: "toy"} $target")
        }

        val sink = object : GattSink {
            override fun onDisconnected() = onLinkDown(myGen)
            override fun onNotify(text: String) {
                if (myGen != generation) return
                for (r in replyBuffer.feed(text)) replies.trySend(r)
            }
        }
        val s = try {
            port.open(target, sink)
        } catch (e: IllegalArgumentException) {
            return TransportResult(false, "Saved toy address is invalid. Use Forget toy in Settings.")
        }
        if (s == null) {
            return TransportResult(
                false,
                if (saved == null) "Could not connect to the toy."
                else "Could not reach the saved toy. Turn it on, or use Forget toy in Settings to pair a new one.",
            )
        }
        session = s

        fun abort(msg: String): TransportResult {
            synchronized(stateLock) { connected = false; ready = false }
            closeSession()
            return TransportResult(false, msg)
        }

        when (s.discover()) {
            Discovery.FAILED -> return abort(if (linkLost) DROPPED else "Toy service discovery failed.")
            Discovery.NOT_LOVENSE -> return abort("This device does not look like a Lovense toy.")
            Discovery.OK -> Unit
        }
        if (linkLost) return abort(DROPPED)

        if (!s.enableNotifications()) {
            return abort(if (linkLost) DROPPED else "Could not enable toy notifications. Try again.")
        }
        synchronized(stateLock) { if (!linkLost) connected = true }
        if (!connected) return abort(DROPPED)

        lastLevel = -1
        lastBattery = null
        running = false
        desiredLevel = 0
        probing.clear()
        diagService = s.serviceUuid
        diagDeviceType = null
        diagAutoSwith = null
        diagGetAs = null
        lastMotionAtMs = null
        lastKeepAliveAtMs = null
        lastKeepAliveOk = null
        disconnectedAtMs = null
        disconnectUnexpected = null
        s.serviceUuid?.let { log("Lovense: service $it") }

        // Identify, then arm and verify the link-loss safety setting.
        sendLocked(LovenseProtocol.CMD_DEVICE_TYPE, awaitReply = true)?.let { reply ->
            diagDeviceType = reply
            val info = LovenseProtocol.parseDeviceType(reply)
            log("Lovense: DeviceType ${info?.let { "model ${it.model} fw ${it.firmware}" } ?: reply}")
        }
        if (linkLost) return abort(DROPPED)
        val warning = armLinkLossProtection()
        if (linkLost) return abort(DROPPED)

        // Name lookup goes before the commit so a drop during it still fails
        // this attempt (the commit below is the last point a drop can land).
        if (name == null) name = s.deviceName
        val committed = synchronized(stateLock) {
            if (linkLost) false else { ready = true; connectedAtMs = clock(); true }
        }
        if (!committed) return abort(DROPPED)

        if (saved == null) {
            address = target
            onPaired(target, name ?: "Lovense toy")
        }
        listener?.onDeviceName(name ?: "Lovense toy")
        if (warning != null) {
            log("Lovense: WARNING $warning")
            listener?.onWarning(warning)
        }
        startBatteryPoll()
        return TransportResult.OK
    }

    /**
     * Write AutoSwith:On:Off; (stop on link loss, never resume by itself),
     * require a non-ERR reply, then read it back with GetAS;. Returns null if
     * confirmed, else a user-facing warning. Never fails the connection: the
     * firmware is untested, so an unsupported toy still connects, flagged.
     */
    private suspend fun armLinkLossProtection(): String? {
        val unconfirmed = "Link-loss protection not confirmed: the toy may keep vibrating if Bluetooth drops."
        val reply = sendLocked(LovenseProtocol.CMD_AUTO_SWITCH, awaitReply = true)
        diagAutoSwith = reply
        log("Lovense: AutoSwith reply ${reply ?: "(none)"}")
        if (reply == null || LovenseProtocol.isErrorReply(reply)) return unconfirmed
        val check = sendLocked(LovenseProtocol.CMD_GET_AUTO_SWITCH, awaitReply = true)
        val state = check?.let { LovenseProtocol.parseAutoSwitch(it) }
        diagGetAs = check
        log("Lovense: GetAS reply ${check ?: "(none)"}")
        return if (state != null && state.isLinkLossSafe) null else unconfirmed
    }

    private fun resetLink() {
        pollJob?.cancel()
        closeSession()
        synchronized(stateLock) { connected = false; ready = false }
        replyBuffer.clear()
        while (replies.tryReceive().isSuccess) { /* drain */ }
    }

    // ---- writes ------------------------------------------------------------

    /** Write one command. Caller must hold [opMutex]. Returns the reply if [awaitReply]. */
    private suspend fun sendLocked(cmd: String, awaitReply: Boolean = false): String? {
        val s = session
        if (s == null || !connected) return null
        while (replies.tryReceive().isSuccess) { /* drop stale replies */ }
        if (s.write(cmd) != WriteResult.OK) return null
        if (!awaitReply) return null
        val r = withTimeoutOrNull(OP_TIMEOUT_MS) { replies.receive() }
        return r?.takeIf { it.isNotEmpty() }
    }

    /**
     * Send one vibrate level. Caller must hold [opMutex]. The acknowledged level
     * is only recorded on success; any failure invalidates it so the next
     * command (a retried stop included) is always sent, never deduplicated.
     */
    private suspend fun sendLevel(level: Int, force: Boolean = false): TransportResult {
        val s = session
        if (!connected || s == null) return TransportResult(false, "Lovense toy not connected.")
        if (!force && level == lastLevel) return TransportResult.OK
        while (replies.tryReceive().isSuccess) { /* drop stale replies */ }
        val cmd = LovenseProtocol.vibrate(level)
        return when (s.write(cmd)) {
            WriteResult.OK -> {
                lastLevel = level
                if (level > 0) lastMotionAtMs = clock()
                TransportResult.OK
            }
            else -> {
                lastLevel = -1
                TransportResult(false, "Lovense write failed.", "Lovense: write failed for $cmd")
            }
        }
    }

    // ---- keep-alive ----------------------------------------------------------

    private fun startBatteryPoll() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && connected) {
                val mode = keepAliveMode
                if (mode != KeepAliveMode.OFF) {
                    val reply = opMutex.withLock {
                        val r = sendLocked(LovenseProtocol.CMD_BATTERY, awaitReply = true)
                        // Silent keep-alive: while the motor is stopped, also write
                        // Vibrate:0; so the link never idles (the toy sleeps on a
                        // quiet link). Decided inside the mutex so it can never
                        // race a start() and stop a running toy, and never while a
                        // probe holds a nonzero level; forced past the level
                        // dedupe; sendLevel keeps lastLevel correct.
                        if (mode == KeepAliveMode.BATTERY_AND_VIBRATE0 &&
                            connected && !running && probing.isEmpty()
                        ) sendLevel(0, force = true)
                        r
                    }
                    lastKeepAliveAtMs = clock()
                    lastKeepAliveOk = reply != null && !LovenseProtocol.isErrorReply(reply)
                    val pct = reply?.let { LovenseProtocol.parseBattery(it) }
                    if (pct != null && pct != lastBattery) {
                        lastBattery = pct
                        log("Lovense: battery $pct%")
                        listener?.onBattery(pct)
                    }
                }
                delay(keepAliveIntervalS * 1_000L)
            }
        }
    }

    override fun setKeepAlive(mode: KeepAliveMode, intervalS: Int) {
        keepAliveMode = mode
        keepAliveIntervalS = intervalS.coerceIn(5, 3_600)
        log("Lovense: keep-alive ${mode.id} every ${keepAliveIntervalS}s")
        // Applies live: restart the loop so a shorter interval does not wait out the old delay.
        if (ready && connected) startBatteryPoll()
    }

    override fun diagnostics() = ToyDiagnostics(
        serviceUuid = diagService,
        deviceTypeReply = diagDeviceType,
        autoSwithReply = diagAutoSwith,
        getAsReply = diagGetAs,
        connectedAtMs = connectedAtMs,
        lastMotionAtMs = lastMotionAtMs,
        lastKeepAliveAtMs = lastKeepAliveAtMs,
        lastKeepAliveOk = lastKeepAliveOk,
        keepAliveMode = keepAliveMode,
        keepAliveIntervalS = keepAliveIntervalS,
        disconnectedAtMs = disconnectedAtMs,
        disconnectUnexpected = disconnectUnexpected,
    )

    // ---- probe ---------------------------------------------------------------

    private fun stamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(clock()))

    override suspend fun probe(cmd: String): ProbeResult = opMutex.withLock {
        val (name, level) = LovenseProtocol.parseProbe(cmd)
            ?: return@withLock ProbeResult(false, null, "Command not allowed.")
        if (!connected) return@withLock ProbeResult(false, null, "Lovense toy not connected.")
        log("${stamp()} probe >> $cmd")
        // Registered before the write: even a failed or unanswered write may have moved the toy.
        if (level > 0) { probing += name; lastMotionAtMs = clock() } else probing -= name
        lastLevel = -1 // the toy's level is no longer what the dedupe cache thinks
        val reply = sendLocked(cmd, awaitReply = true)
        log("${stamp()} probe << ${reply ?: "(no reply)"}")
        ProbeResult(true, reply)
    }

    override suspend fun probeStopAll() = opMutex.withLock {
        if (!connected) { probing.clear(); return@withLock }
        log("${stamp()} probe stop all")
        // Vibrate last, so lastLevel ends up honestly 0. Replies are ignored.
        val names = LovenseProtocol.PROBE_NAMES.filter { it != "Vibrate" } + "Vibrate"
        for (n in names) LovenseProtocol.probeCommand(n, 0)?.let { sendLocked(it) }
        probing.clear()
        lastLevel = if (connected) 0 else -1
    }

    // ---- DeviceTransport ---------------------------------------------------

    override suspend fun disconnect() {
        synchronized(stateLock) { closing = true }
        pollJob?.cancel()
        opMutex.withLock {
            if (connected) {
                // Best effort: make sure it is not left vibrating.
                runCatching { session?.write(LovenseProtocol.CMD_STOP) }
            }
            synchronized(stateLock) { connected = false; ready = false }
            closeSession()
            lastLevel = -1
            running = false
            desiredLevel = 0
            probing.clear()
            disconnectedAtMs = clock()
            disconnectUnexpected = false
        }
        listener?.onBattery(null)
    }

    override suspend fun start(): TransportResult = opMutex.withLock {
        if (!connected) return@withLock TransportResult(false, "Lovense toy not connected.")
        running = true
        val r = if (desiredLevel > 0) sendLevel(desiredLevel) else TransportResult.OK
        if (!r.ok) running = false
        r
    }

    override suspend fun stop(): TransportResult = opMutex.withLock {
        running = false
        // Always written (never deduplicated): a stop is safety-critical.
        sendLevel(0, force = true)
    }

    override suspend fun setVelocity(velocity: Int): TransportResult = opMutex.withLock {
        val level = LovenseProtocol.levelForVelocity(velocity)
        desiredLevel = level
        if (running) return@withLock sendLevel(level)
        // Stopped: remember the level, do not move. If the toy might still be
        // moving (unknown level after a failed write, or never confirmed), make
        // sure it is stopped; this is also what makes a failed stop retryable.
        if (lastLevel != 0) sendLevel(0) else TransportResult.OK
    }

    override suspend fun setSlide(min: Int, max: Int): TransportResult = TransportResult.OK

    override suspend fun park(devMin: Int, devMax: Int, target: Int): Boolean = true
}
