package com.handyvoice.v2

import android.app.Application
import com.handyvoice.v2.api.HandyV2ApiClient
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
}
