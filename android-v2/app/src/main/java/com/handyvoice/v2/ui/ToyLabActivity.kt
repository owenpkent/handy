package com.handyvoice.v2.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.handyvoice.v2.HandyVoiceApp
import com.handyvoice.v2.R
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.api.KeepAliveMode
import com.handyvoice.v2.api.LovenseBle
import com.handyvoice.v2.api.LovenseProtocol
import com.handyvoice.v2.data.PreferencesManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Lovense toy test panel: status, raw command probe, keep-alive settings, idle
 * timer + disconnect history, and a copy-report button. Opened from Settings
 * (Lovense only). Everything is tap-only. Uses the process-shared client; the
 * transport enforces the probe allow-list and the keep-alive pause.
 */
class ToyLabActivity : AppCompatActivity() {

    private val app get() = application as HandyVoiceApp
    private val api get() = app.api
    private lateinit var prefs: PreferencesManager

    private val connectGuard = ConnectGuard()

    private lateinit var statusText: TextView
    private lateinit var connectButton: MaterialButton
    private lateinit var idleText: TextView
    private lateinit var historyText: TextView
    private lateinit var kaGroup: RadioGroup
    private lateinit var intervalGroup: MaterialButtonToggleGroup

    /** Last result line per probe name, kept until that row is probed again. */
    private val probeResults = LinkedHashMap<String, String>()
    private val probeViews = HashMap<String, TextView>()

    /** True once any nonzero probe went out and no Stop all has followed. */
    private var probesLive = false
    private var history = emptyList<ToyDisconnectRecord>()
    private var ready = false

    private val requestBle = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (LovenseBle.hasPermissions(this)) onConnectTapped()
        else toast("Bluetooth permission is required to connect to the toy.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_toy_lab)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = PreferencesManager(applicationContext)

        statusText = findViewById(R.id.labStatusText)
        connectButton = findViewById(R.id.labConnectButton)
        idleText = findViewById(R.id.labIdleText)
        historyText = findViewById(R.id.labHistoryText)
        kaGroup = findViewById(R.id.labKeepAliveGroup)
        intervalGroup = findViewById(R.id.labIntervalGroup)

        findViewById<View>(R.id.labBackButton).setOnClickListener { finish() }
        connectButton.setOnClickListener { onConnectTapped() }
        findViewById<View>(R.id.labStopAllButton).setOnClickListener { stopAll() }
        findViewById<View>(R.id.labCopyButton).setOnClickListener { copyReport() }

        buildProbeRows()

        kaGroup.setOnCheckedChangeListener { _, _ -> if (ready) applyKeepAlive() }
        intervalGroup.addOnButtonCheckedListener { _, _, checked -> if (ready && checked) applyKeepAlive() }

        lifecycleScope.launch {
            // Make sure the shared client carries the Lovense transport (Settings may have just switched).
            val snap = prefs.snapshot()
            if (snap.isLovense && api.connectionState.value == ConnectionState.DISCONNECTED) app.configureDevice(snap)
            kaGroup.check(
                when (KeepAliveMode.fromId(snap.toyKeepAliveMode)) {
                    KeepAliveMode.BATTERY_ONLY -> R.id.labKaBattery
                    KeepAliveMode.BATTERY_AND_VIBRATE0 -> R.id.labKaVibrate0
                    KeepAliveMode.OFF -> R.id.labKaOff
                }
            )
            intervalGroup.check(
                when (snap.toyKeepAliveIntervalS) {
                    60 -> R.id.labInt60
                    300 -> R.id.labInt300
                    else -> R.id.labInt30
                }
            )
            history = prefs.toyDisconnects()
            ready = true
            refresh()
        }
    }

    override fun onStart() {
        super.onStart()
        // One-second tick for the live readouts; also refreshes the disconnect history.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (ready) {
                        history = prefs.toyDisconnects()
                        refresh()
                    }
                    delay(1000)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Leaving (or screen off): never leave a probe level running. On the
        // process scope so it still completes while this activity is destroyed.
        if (probesLive) {
            probesLive = false
            app.appScope.launch { api.probeStopAll() }
        }
    }

    // ---- connect -------------------------------------------------------------

    private fun onConnectTapped() {
        if (!LovenseBle.hasPermissions(this)) {
            val ask = if (Build.VERSION.SDK_INT >= 33) LovenseBle.requiredPermissions() + Manifest.permission.POST_NOTIFICATIONS
            else LovenseBle.requiredPermissions()
            requestBle.launch(ask)
            return
        }
        connectGuard.launch(lifecycleScope, onDone = { refresh() }) {
            val snap = prefs.snapshot()
            if (api.connectionState.value == ConnectionState.DISCONNECTED) app.configureDevice(snap)
            if (!api.connect()) toast(api.lastError.value ?: "Connection failed.")
        }
        refresh()
    }

    // ---- probe ---------------------------------------------------------------

    private fun buildProbeRows() {
        val container = findViewById<LinearLayout>(R.id.labProbeRows)
        val dp = resources.displayMetrics.density
        for (name in LovenseProtocol.PROBE_NAMES) {
            val title = TextView(this).apply {
                text = name
                setTextColor(color(R.color.text_primary))
                textSize = 17f
                setPadding(0, (10 * dp).toInt(), 0, 0)
            }
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, level) in listOf("Low" to 5, "Mid" to 10, "High" to 20)) {
                val b = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = label
                    textSize = 16f
                    isAllCaps = false
                    minHeight = (56 * dp).toInt()
                    minimumHeight = (56 * dp).toInt()
                    setOnClickListener { sendProbe(name, level) }
                }
                buttons.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins((2 * dp).toInt(), 0, (2 * dp).toInt(), 0)
                })
            }
            val result = TextView(this).apply {
                text = "(not probed)"
                setTextColor(color(R.color.text_secondary))
                textSize = 14f
                gravity = Gravity.START
                setPadding(0, (2 * dp).toInt(), 0, 0)
            }
            probeViews[name] = result
            container.addView(title)
            container.addView(buttons)
            container.addView(result)
        }
    }

    private fun sendProbe(name: String, level: Int) {
        val cmd = LovenseProtocol.probeCommand(name, level) ?: return
        if (level > 0) probesLive = true
        probeViews[name]?.text = "$cmd ..."
        lifecycleScope.launch {
            val r = api.probe(cmd)
            val text = when {
                !r.sent -> "$cmd not sent: ${r.error ?: "unknown"}"
                else -> "$cmd -> ${r.reply ?: "(no reply)"}"
            }
            val stamped = "${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}  $text"
            probeResults[name] = stamped
            probeViews[name]?.text = stamped
        }
    }

    private fun stopAll() {
        probesLive = false
        lifecycleScope.launch {
            api.probeStopAll()
            // If the toy page believed it was running, bring the client's view in line.
            if (api.isRunning.value) app.stopMotion()
            toast("Stopped")
        }
    }

    // ---- keep-alive ----------------------------------------------------------

    private fun currentMode(): KeepAliveMode = when (kaGroup.checkedRadioButtonId) {
        R.id.labKaBattery -> KeepAliveMode.BATTERY_ONLY
        R.id.labKaOff -> KeepAliveMode.OFF
        else -> KeepAliveMode.BATTERY_AND_VIBRATE0
    }

    private fun currentIntervalS(): Int = when (intervalGroup.checkedButtonId) {
        R.id.labInt60 -> 60
        R.id.labInt300 -> 300
        else -> 30
    }

    private fun applyKeepAlive() {
        val mode = currentMode()
        val interval = currentIntervalS()
        api.setToyKeepAlive(mode, interval)
        lifecycleScope.launch { prefs.setToyKeepAlive(mode, interval) }
    }

    // ---- readouts ------------------------------------------------------------

    private fun dateFmt(ms: Long): String = SimpleDateFormat("MMM d HH:mm", Locale.US).format(Date(ms))

    private fun statusLines(): List<String> {
        val d = api.toyDiagnostics()
        return listOf(
            "Connection: ${api.connectionState.value.name.lowercase()}",
            "Toy: ${api.deviceName.value ?: "-"}",
            "Battery: ${api.battery.value?.let { "$it%" } ?: "-"}",
            "Service UUID: ${d?.serviceUuid ?: "-"}",
            "DeviceType: ${d?.deviceTypeReply ?: "-"}",
            "AutoSwith write: ${d?.autoSwithReply ?: "-"}",
            "GetAS: ${d?.getAsReply ?: "-"}",
        ) + listOfNotNull(
            api.warning.value?.let { "Warning: $it" },
            api.lastError.value?.takeIf { api.connectionState.value != ConnectionState.CONNECTED }?.let { "Last error: $it" },
        )
    }

    private fun idleLines(now: Long): List<String> {
        val d = api.toyDiagnostics()
        val connected = api.connectionState.value == ConnectionState.CONNECTED
        val at = d?.connectedAtMs
        if (!connected || at == null) return listOf("Not connected.")
        val idleSince = d.lastMotionAtMs ?: at
        val ka = d.lastKeepAliveAtMs
        val kaLine = when {
            d.keepAliveMode == KeepAliveMode.OFF -> "Keep-alive: off"
            ka == null -> "Last keep-alive: none yet"
            else -> "Last keep-alive: ${ToyLabFormat.ago(now, ka)} (${if (d.lastKeepAliveOk == true) "OK" else "no reply"})"
        }
        return listOf(
            "Connected for ${ToyLabFormat.duration(now - at)}",
            "Idle for ${ToyLabFormat.duration(now - idleSince)}",
            kaLine,
        )
    }

    private fun historyLines(): List<String> =
        if (history.isEmpty()) listOf("None yet.") else history.map { ToyLabFormat.describe(it, ::dateFmt) }

    private fun refresh() {
        val state = api.connectionState.value
        val connecting = state == ConnectionState.CONNECTING ||
            (state == ConnectionState.DISCONNECTED && connectGuard.inFlight)
        statusText.text = statusLines().joinToString("\n")
        connectButton.visibility = if (state == ConnectionState.CONNECTED) View.GONE else View.VISIBLE
        connectButton.isEnabled = !connecting
        connectButton.text = if (connecting) "Connecting..." else "Connect"
        idleText.text = idleLines(System.currentTimeMillis()).joinToString("\n")
        historyText.text = historyLines().joinToString("\n")
    }

    // ---- report --------------------------------------------------------------

    private fun copyReport() {
        val now = System.currentTimeMillis()
        val d = api.toyDiagnostics()
        val report = buildString {
            appendLine("Toy test report ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(now))}")
            appendLine()
            appendLine("== Status ==")
            statusLines().forEach { appendLine(it) }
            appendLine()
            appendLine("== Probe results ==")
            LovenseProtocol.PROBE_NAMES.forEach { appendLine("$it: ${probeResults[it] ?: "(not probed)"}") }
            appendLine()
            appendLine("== Keep-alive ==")
            appendLine("Mode: ${d?.keepAliveMode?.id ?: currentMode().id}")
            appendLine("Interval: ${d?.keepAliveIntervalS ?: currentIntervalS()} s")
            appendLine()
            appendLine("== Idle timer ==")
            idleLines(now).forEach { appendLine(it) }
            appendLine()
            appendLine("== Last disconnects ==")
            historyLines().forEach { appendLine(it) }
            appendLine()
            appendLine("== Debug log (oldest first) ==")
            api.debugLog.value.take(50).reversed().forEach { appendLine(it) }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("toy test report", report))
        toast("Report copied")
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
