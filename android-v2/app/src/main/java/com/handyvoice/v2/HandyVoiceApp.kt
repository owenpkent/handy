package com.handyvoice.v2

import android.app.Application
import com.handyvoice.v2.api.HandyRestTransport
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.api.KeepAliveMode
import com.handyvoice.v2.api.LovenseBle
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.HumanizeEngine
import com.handyvoice.v2.dispatch.PatternEngine
import com.handyvoice.v2.service.ToyConnectionService
import com.handyvoice.v2.ui.ToyLabFormat
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class HandyVoiceApp : Application() {

    /**
     * One API client for the whole process, shared by SplashActivity and
     * ControlActivity. Sharing lets the splash kick off the connection while
     * its animation plays, so Control can come up already connecting (or
     * connected) instead of briefly flashing the Connect button. The client
     * holds no Android context — just OkHttp/Retrofit and state flows — so a
     * process-lifetime singleton is safe.
     */
    val api: HandyV2ApiClient by lazy {
        HandyV2ApiClient().also { it.preConnect = { awaitMotionTeardown() } }
    }

    /**
     * Process-lifetime scope for work that must outlive a single activity —
     * notably the splash-time connect, which would otherwise be cancelled
     * when SplashActivity finishes mid-network-call and leave the client
     * stuck in CONNECTING.
     */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * Motion engines live on the process (appScope), not on ControlActivity, so
     * a pattern / humanize loop survives the activity being destroyed while the
     * toy service keeps the link up, and the service's Stop action can cancel
     * them without an activity.
     */
    val patternEngine: PatternEngine by lazy { PatternEngine(api, appScope) }
    val humanizeEngine: HumanizeEngine by lazy { HumanizeEngine(api, appScope) }

    /**
     * Cancel and join both motion loops. The single teardown point: the
     * connection-state collector runs it on DISCONNECTED, and the client runs it
     * (via `api.preConnect`) at the start of every connect, so every connect
     * path (home, toy page, setup panel, splash, bootstrap, test panel) awaits
     * it and a stale loop can never drive a reconnected device. Idempotent.
     */
    suspend fun awaitMotionTeardown() {
        humanizeEngine.cancel()
        patternEngine.cancel()
    }

    /**
     * The one stop path for callers with no activity (the toy notification's
     * Stop action): cancel humanize + pattern, then the rampStop wind-down.
     */
    suspend fun stopMotion() {
        awaitMotionTeardown()
        api.rampStop()
    }

    override fun onCreate() {
        super.onCreate()
        // Foreground service while a Lovense toy is connected: keeps the BLE
        // link and its keep-alive alive with the screen off or the app in the
        // background. Driven from the shared client's state so it follows the
        // connection however it was made (splash, Control, any activity).
        appScope.launch {
            var prev = ConnectionState.DISCONNECTED
            api.connectionState.collectLatest { state ->
                val toy = api.transportKind == "lovense"
                // Remember when and after how much idle a toy link ended (test panel history).
                if (toy && prev == ConnectionState.CONNECTED && state == ConnectionState.DISCONNECTED) {
                    ToyLabFormat.recordFrom(api.toyDiagnostics())?.let { rec ->
                        appScope.launch { PreferencesManager(applicationContext).addToyDisconnect(rec) }
                    }
                }
                prev = state
                // A lost or ended connection must not leave a loop alive, even
                // with no activity around (the toy service keeps the process).
                if (state == ConnectionState.DISCONNECTED) awaitMotionTeardown()
                if (state ==ConnectionState.CONNECTED && toy) ToyConnectionService.start(this@HandyVoiceApp)
                else if (state != ConnectionState.CONNECTING) ToyConnectionService.stop(this@HandyVoiceApp)
            }
        }
    }

    // Identity of the wire transport currently installed on [api]: Handy, or a
    // Lovense toy with its address (null until one is saved or paired).
    @Volatile private var installedLovense = false
    @Volatile private var installedAddress: String? = null

    // Address just paired by a scan whose prefs write has not landed yet.
    @Volatile private var pendingAddress: String? = null

    /** True if the saved device settings no longer match the installed transport. */
    fun deviceChanged(snap: PreferencesManager.Snapshot): Boolean =
        !deviceMatches(installedLovense, installedAddress, pendingAddress, snap.isLovense, snap.lovenseAddress)

    /**
     * Install the transport the prefs ask for (Handy REST or Lovense BLE).
     * Only possible while the client is disconnected; returns true if the
     * installed transport now matches the prefs.
     */
    fun configureDevice(snap: PreferencesManager.Snapshot): Boolean {
        if (!deviceChanged(snap)) return true
        val t = if (snap.isLovense) {
            LovenseBle.createTransport(this, snap.lovenseAddress.ifBlank { null }) { address, name ->
                // Known to the transport now, saved a moment later: until the
                // save lands, a blank-address snapshot still matches.
                installedAddress = address
                pendingAddress = address
                appScope.launch {
                    try {
                        PreferencesManager(applicationContext).setLovenseToy(address, name)
                    } finally {
                        if (pendingAddress == address) pendingAddress = null
                    }
                }
            }.also { it.setKeepAlive(KeepAliveMode.fromId(snap.toyKeepAliveMode), snap.toyKeepAliveIntervalS) }
        } else {
            HandyRestTransport()
        }
        if (!api.useTransport(t)) return false
        installedLovense = snap.isLovense
        installedAddress = if (snap.isLovense) snap.lovenseAddress.ifBlank { null } else null
        pendingAddress = null
        return true
    }
}
