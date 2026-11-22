package com.handyvoice.v2.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.handyvoice.v2.HandyVoiceApp
import com.handyvoice.v2.R
import com.handyvoice.v2.ui.ControlActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that exists only while a Lovense toy is connected. It
 * keeps the process (and so the BLE link and its 30 s keep-alive) from being
 * frozen with the screen off or the app in the background: a connectedDevice
 * foreground service plus a PARTIAL_WAKE_LOCK ("HandyVoiceV2:toy"), separate
 * from the listening wake lock.
 *
 * Started / stopped by [HandyVoiceApp] from the shared client's connection
 * state; it owns no connection logic. The notification's Stop action comes
 * back here as [ACTION_STOP] and runs [HandyVoiceApp.stopMotion] (the same
 * cancel-loops-then-rampStop path as every stop), so it works with no
 * activity alive. If the notification permission is denied the service still
 * runs; only the shade entry is hidden.
 */
class ToyConnectionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HandyVoiceV2:toy").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HandyVoiceApp
        // Must reach startForeground promptly after startForegroundService().
        enterForeground(buildNotification(app))
        if (intent?.action == ACTION_STOP) {
            app.appScope.launch { app.stopMotion() }
        }
        if (observer == null) {
            observer = app.appScope.launch {
                combine(app.api.deviceName, app.api.battery) { n, b -> n to b }.collect {
                    runCatching {
                        if (canNotify()) NotificationManagerCompat.from(this@ToyConnectionService)
                            .notify(NOTIFICATION_ID, buildNotification(app))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        observer?.cancel()
        observer = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun enterForeground(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
    }

    private fun canNotify() = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Toy connection", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a Bluetooth toy is connected"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(app: HandyVoiceApp): Notification {
        val name = app.api.deviceName.value ?: "toy"
        val battery = app.api.battery.value
        val text = buildString {
            append("Connected to ").append(name)
            if (battery != null) append(", battery ").append(battery).append("%")
        }
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) flags = flags or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ControlActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            flags,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ToyConnectionService::class.java).setAction(ACTION_STOP),
            flags,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_splash_logo)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(open)
            .addAction(R.drawable.ic_pip_stop, getString(R.string.pip_stop), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "toy_connection"
        private const val NOTIFICATION_ID = 41
        private const val ACTION_STOP = "com.handyvoice.v2.TOY_STOP"

        /** Start (or refresh) the service. Never throws: a refused background start just leaves the app as before. */
        fun start(context: Context) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ToyConnectionService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ToyConnectionService::class.java)) }
        }
    }
}
