package com.handyvoice.v2.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/** Doze / battery-optimization exemption helpers for the toy connection. */
object BatteryOptimization {
    fun isExempt(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** The system "allow this app to run in the background" prompt for this app. */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
}
