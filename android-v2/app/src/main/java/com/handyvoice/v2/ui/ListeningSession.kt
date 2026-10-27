package com.handyvoice.v2.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Lifecycle of one voice-listening session: a capture loop plus the
 * recognition jobs it spawns, held under a single [Job].
 *
 * Why it exists: a command captured just before the mic is toggled off (or the
 * idle auto-stop fires) can still be mid-dispatch when the stop begins.
 * HandyV2ApiClient catches Exception, which swallows CancellationException, so
 * cancelling the session does not actually halt that dispatch; it keeps
 * issuing velocity writes. If the stop's rampStop() wind-down runs at the same
 * time, the stale write bumps the client's velocityEpoch, rampStop bails before
 * hamp/stop, and the device keeps moving with listening off.
 *
 * So recognition jobs must be launched as children of the session (the
 * `CoroutineScope` receiver of the [start] body), and a stop must go through
 * [cancelThen], which joins the old session before running the stop. Join
 * waits for children, so any stale command lands before the stop, never
 * during its wind-down.
 *
 * Android-free so the ordering can be unit tested on the JVM.
 */
class ListeningSession(private val scope: CoroutineScope) {

    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive == true

    /**
     * Launch [body] as the session. Returns false (and does nothing) if a
     * session is already active. Launch recognition work with `launch { }` on
     * the body's receiver so it becomes a child of the session.
     */
    fun start(
        context: CoroutineContext = EmptyCoroutineContext,
        body: suspend CoroutineScope.() -> Unit,
    ): Boolean {
        if (isActive) return false
        job = scope.launch(context, block = body)
        return true
    }

    /**
     * Cancel the session and its child recognition jobs, and clear state.
     * Returns the cancelled job (null if none was active) so a caller can
     * join it; cancellation is only a request, children may still be running.
     */
    fun cancel(): Job? {
        val old = job?.takeIf { it.isActive }
        old?.cancel()
        job = null
        return old
    }

    /**
     * [cancel] synchronously, then in the owning scope wait for the old
     * session (children included) to finish before running [then]. Use this
     * for any "stop the device because listening ended" path.
     */
    fun cancelThen(then: suspend () -> Unit): Job {
        val old = cancel()
        return scope.launch {
            old?.join()
            then()
        }
    }
}
