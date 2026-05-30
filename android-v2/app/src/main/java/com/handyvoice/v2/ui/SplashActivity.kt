package com.handyvoice.v2.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.handyvoice.v2.R

/**
 * Animated launch screen.
 *
 * Pure property animation (no AnimatedVectorDrawable) so it animates
 * identically on every API level we support — including the emulator and
 * pre-31 devices where the platform splash API only shows a static icon.
 *
 * Sequence (~1.7 s total):
 *   1. Logo fades in and scales up with a slight overshoot.
 *   2. Two rings expand and fade outward behind it, like sound waves, and
 *      keep pulsing for the life of the screen.
 *   3. Title + tagline fade up.
 *   4. The whole screen fades out and ControlActivity takes over.
 *
 * Hard cap [SPLASH_HANDOFF_MS] guarantees we always advance, even if an
 * animation callback is dropped.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val SPLASH_HANDOFF_MS = 1700L
        private const val RING_PERIOD_MS = 1500L
    }

    private var advanced = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val logo = findViewById<ImageView>(R.id.splashLogo)
        val title = findViewById<TextView>(R.id.splashTitle)
        val tagline = findViewById<TextView>(R.id.splashTagline)
        val ring1 = findViewById<View>(R.id.splashRing1)
        val ring2 = findViewById<View>(R.id.splashRing2)
        val root = findViewById<View>(R.id.splashRoot)

        // Logo: fade + scale-in with a gentle overshoot.
        logo.scaleX = 0.6f
        logo.scaleY = 0.6f
        logo.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(120)
            .setDuration(620)
            .setInterpolator(OvershootInterpolator(2.0f))
            .start()

        // Sound-wave rings: staggered, looping expand-and-fade.
        startRingPulse(ring1, startDelay = 250)
        startRingPulse(ring2, startDelay = 250 + RING_PERIOD_MS / 2)

        // Title + tagline: fade up after the logo lands.
        fadeUp(title, startDelay = 560)
        fadeUp(tagline, startDelay = 720)

        // Hand off: fade the whole screen out, then launch Control.
        root.postDelayed({
            root.animate()
                .alpha(0f)
                .setDuration(280)
                .setInterpolator(AccelerateDecelerateInterpolator())
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) = goToControl()
                })
                .start()
        }, SPLASH_HANDOFF_MS)
    }

    private fun fadeUp(view: View, startDelay: Long) {
        view.translationY = 24f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay(startDelay)
            .setDuration(420)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /**
     * Expand a ring from logo-size out to ~2.4x while fading from visible to
     * gone, then loop. Reset the view before each cycle so the animations
     * chain cleanly. Stops when the view detaches (activity finishes).
     */
    private fun startRingPulse(ring: View, startDelay: Long) {
        ring.postDelayed({
            if (advanced) return@postDelayed
            ring.scaleX = 0.5f
            ring.scaleY = 0.5f
            ring.alpha = 0.6f
            ring.animate()
                .scaleX(2.4f)
                .scaleY(2.4f)
                .alpha(0f)
                .setDuration(RING_PERIOD_MS)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction { if (!advanced) startRingPulse(ring, 0) }
                .start()
        }, startDelay)
    }

    // overridePendingTransition is deprecated in favour of API 34's
    // overrideActivityTransition, but that doesn't exist on our minSdk 26,
    // so the deprecated call is the right cross-version choice here.
    @Suppress("DEPRECATION")
    private fun goToControl() {
        if (advanced) return
        advanced = true
        startActivity(Intent(this, ControlActivity::class.java))
        // No reverse animation — Control fades in over the already-faded
        // splash. overridePendingTransition keeps the seam invisible.
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
