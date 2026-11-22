package com.handyvoice.v2

import android.app.Application
import com.handyvoice.v2.api.HandyRestTransport
import com.handyvoice.v2.api.HandyV2ApiClient
import com.handyvoice.v2.api.LovenseBle
import com.handyvoice.v2.api.ConnectionState
import com.handyvoice.v2.data.PreferencesManager
import com.handyvoice.v2.dispatch.HumanizeEngine
import com.handyvoice.v2.dispatch.PatternEngine
import com.handyvoice.v2.service.ToyConnectionService
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
    val api: HandyV2ApiClient by lazy { HandyV2ApiClient() }

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
     * The one stop path for callers with no activity (the toy notification's
     * Stop action): cancel humanize + pattern, then the rampStop wind-down.
     */
    suspend fun stopMotion() {
        humanizeEngine.cancel()
        patternEngine.cancel()
        api.rampStop()
    }

    override fun onCreate() {
        super.onCreate()
        // Foreground service while a Lovense toy is connected: keeps the BLE
        // link and its keep-alive alive with the screen off or the app in the
        // background. Driven from the shared client's state so it follows the
        // connection however it was made (splash, Control, any activity).
        appScope.launch {
            api.connectionState.collectLatest { state ->
                val toy = api.transportKind == "lovense"
                if (state == ConnectionState.CONNECTED && toy) ToyConnectionService.start(this@HandyVoiceApp)
                else if (state != ConnectionState.CONNECTING) ToyConnectionService.stop(this@HandyVoiceApp)
            }
        }
    }

    // Identifies the wire transport currently installed on [api]: "handy" or
    // "lovense:<saved address>". A different signature means a new transport.
    private var transportSig = "handy"

    private fun sigFor(snap: PreferencesManager.Snapshot) =
        if (snap.isLovense) "lovense:${snap.lovenseAddress}" else "handy"

    /** True if the saved device settings no longer match the installed transport. */
    fun deviceChanged(snap: PreferencesManager.Snapshot): Boolean = sigFor(snap) != transportSig

    /**
     * Install the transport the prefs ask for (Handy REST or Lovense BLE).
     * Only possible while the client is disconnected; returns true if the
     * installed transport now matches the prefs.
     */
    fun configureDevice(snap: PreferencesManager.Snapshot): Boolean {
        val sig = sigFor(snap)
        if (sig == transportSig) return true
        val t = if (snap.isLovense) {
            LovenseBle.createTransport(this, snap.lovenseAddress.ifBlank { null }) { address, name ->
                transportSig = "lovense:$address"
                appScope.launch { PreferencesManager(applicationContext).setLovenseToy(address, name) }
            }
        } else {
            HandyRestTransport()
        }
        if (!api.useTransport(t)) return false
        transportSig = sig
        return true
    }
}
