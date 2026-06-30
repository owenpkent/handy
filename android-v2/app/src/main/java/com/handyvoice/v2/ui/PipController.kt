package com.handyvoice.v2.ui

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import androidx.core.content.ContextCompat
import com.handyvoice.v2.R

/**
 * Owns everything Picture-in-Picture for [ControlActivity]: feature detection,
 * building the on-window control bar, registering the broadcast receiver the
 * control buttons fire, and entering PiP.
 *
 * Why a control *bar* and not real buttons: a PiP window does not allow direct
 * touch on the activity's own views. The only controls Android lets you place
 * there are system [RemoteAction]s (icon buttons in the window's control
 * overlay), each backed by a [PendingIntent]. Most phones cap the bar at
 * **3** actions (`getMaxNumPictureInPictureActions`); some allow 5. The list
 * is silently truncated past the cap.
 *
 * So we show all five controls (Slower / Faster / Stop / Deeper / Shallow)
 * where the phone permits, and fall back to the speed trio
 * (Slower / Stop / Faster, with **Stop always present**) where it doesn't.
 * Deeper / Shallow then stay reachable by tapping the window to expand back to
 * the full app.
 *
 * Each control value is a [com.handyvoice.v2.dispatch.Dispatch] label, so the
 * button fires the exact same code path as the spoken word or the in-app tap
 * shortcut — same caps, same ramp, same stop-cancels-pattern behavior.
 */
class PipController(
    private val activity: ControlActivity,
    private val onControl: (String) -> Unit,
) {

    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION) return
            val control = intent.getStringExtra(EXTRA_CONTROL) ?: return
            onControl(control)
        }
    }

    /** True only on devices that actually offer PiP. */
    val isSupported: Boolean
        get() = activity.packageManager
            .hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /**
     * Start listening for control taps. The receiver only ever fires from our
     * own (not-exported) PendingIntents, so it's harmless to keep registered
     * for the whole activity lifetime; the activity stays in the STARTED state
     * while its PiP window is visible, so this keeps working there too.
     */
    fun register() {
        if (receiverRegistered || !isSupported) return
        ContextCompat.registerReceiver(
            activity,
            receiver,
            IntentFilter(ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    fun unregister() {
        if (!receiverRegistered) return
        runCatching { activity.unregisterReceiver(receiver) }
        receiverRegistered = false
    }

    /** Float the activity into a PiP window. No-op (and no crash) if PiP is
     *  unavailable or the system refuses the transition. */
    fun enter() {
        if (!isSupported) return
        runCatching { activity.enterPictureInPictureMode(buildParams()) }
    }

    /** Re-push the params while already in PiP (e.g. if the control set ever
     *  needs to change). Safe to call any time. */
    fun update() {
        if (!isSupported) return
        runCatching { activity.setPictureInPictureParams(buildParams()) }
    }

    private fun buildParams(): PictureInPictureParams =
        PictureInPictureParams.Builder()
            .setActions(buildActions())
            // A short, wide bar suits a row of control icons over thin content.
            .setAspectRatio(Rational(16, 9))
            .build()

    /**
     * Build the control set sized to what this phone's PiP bar can show. We
     * decide explicitly (rather than letting the OS truncate a 5-item list)
     * so **Stop is guaranteed present** on a 3-slot phone — truncation would
     * keep whichever three happened to be first.
     */
    private fun buildActions(): List<RemoteAction> {
        // Documented since API 26; wrapped defensively so an odd OEM build that
        // throws just falls back to the safe 3-slot layout instead of crashing.
        val max = runCatching { activity.maxNumPictureInPictureActions }.getOrDefault(3)
        return if (max >= 5) {
            listOf(
                action(SLOWER, R.drawable.ic_pip_slower, R.string.pip_slower, 1),
                action(FASTER, R.drawable.ic_pip_faster, R.string.pip_faster, 2),
                action(STOP, R.drawable.ic_pip_stop, R.string.pip_stop, 3),
                action(DEEPER, R.drawable.ic_pip_deeper, R.string.pip_deeper, 4),
                action(SHALLOW, R.drawable.ic_pip_shallow, R.string.pip_shallow, 5),
            )
        } else {
            listOf(
                action(SLOWER, R.drawable.ic_pip_slower, R.string.pip_slower, 1),
                action(STOP, R.drawable.ic_pip_stop, R.string.pip_stop, 3),
                action(FASTER, R.drawable.ic_pip_faster, R.string.pip_faster, 2),
            )
        }
    }

    private fun action(control: String, iconRes: Int, labelRes: Int, requestCode: Int): RemoteAction {
        val label = activity.getString(labelRes)
        val intent = Intent(ACTION)
            .setPackage(activity.packageName)
            .putExtra(EXTRA_CONTROL, control)
        // NB: PendingIntent equality ignores extras, so each control needs its
        // own request code — otherwise all five buttons would collapse onto one
        // intent and fire the same action. IMMUTABLE because we never mutate it.
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = flags or PendingIntent.FLAG_IMMUTABLE
        }
        val pending = PendingIntent.getBroadcast(activity, requestCode, intent, flags)
        return RemoteAction(Icon.createWithResource(activity, iconRes), label, label, pending)
    }

    companion object {
        private const val ACTION = "com.handyvoice.v2.PIP_CONTROL"
        private const val EXTRA_CONTROL = "control"

        // Control values double as Dispatch labels.
        const val SLOWER = "slower"
        const val FASTER = "faster"
        const val STOP = "stop"
        const val DEEPER = "deeper"
        const val SHALLOW = "shallow"
    }
}
