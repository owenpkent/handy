package com.handyvoice.v2.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single-flight guard for user-initiated connect attempts, shared by every
 * Connect affordance (the setup panel's Connect button and the home screen's
 * big button). Android-free so it's unit-testable.
 *
 * The slot is claimed synchronously, before the attempt's first suspension
 * (persisting the key to DataStore), so a second tap that lands while the
 * client is still DISCONNECTED is dropped. Without this, two overlapping
 * connects could finish out of order and a late failure would knock a good
 * connection back to DISCONNECTED (which also tears listening down).
 */
class ConnectGuard {
    var inFlight = false
        private set

    /**
     * Run [block] in [scope] unless an attempt is already in flight. [onDone]
     * runs after the slot is released, so callers can refresh their UI.
     * Returns whether the attempt started.
     */
    fun launch(
        scope: CoroutineScope,
        onDone: () -> Unit = {},
        block: suspend () -> Unit,
    ): Boolean {
        if (inFlight) return false
        inFlight = true
        scope.launch {
            try {
                block()
            } finally {
                inFlight = false
                onDone()
            }
        }
        return true
    }
}
