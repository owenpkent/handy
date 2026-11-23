package com.handyvoice.v2.ui

import android.app.Activity
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Keeps an activity's content clear of the status bar, navigation bar and
 * display cutout.
 *
 * With targetSdk 35, Android 15+ draws every activity edge to edge, so without
 * this the top of the layout sits under the status bar and the bottom under the
 * navigation bar. On older releases the system already insets the content and
 * hands us zero insets, so this is a no-op there. In Picture-in-Picture the
 * insets are zero as well.
 *
 * Call once, after `setContentView`.
 */
fun Activity.padForSystemBars() {
    val content = findViewById<View>(android.R.id.content)
    ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
        )
        v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(content)
}
