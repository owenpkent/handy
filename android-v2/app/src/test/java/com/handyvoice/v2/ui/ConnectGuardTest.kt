@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.handyvoice.v2.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectGuardTest {

    /**
     * Regression for the double Connect tap: the second tap arrives while the
     * first attempt is still suspended persisting the key (before the client
     * leaves DISCONNECTED). It must be dropped, so only one connect runs.
     */
    @Test
    fun secondTapDuringKeyPersistIsDropped() = runTest {
        val guard = ConnectGuard()
        val persistKey = CompletableDeferred<Unit>()
        var connects = 0
        val attempt: suspend () -> Unit = {
            persistKey.await()
            connects++
        }

        assertTrue(guard.launch(this, block = attempt))
        runCurrent()
        assertFalse(guard.launch(this, block = attempt))

        persistKey.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, connects)
        assertFalse(guard.inFlight)
    }

    @Test
    fun slotFreesAfterAttemptSoRetryWorks() = runTest {
        val guard = ConnectGuard()
        var done = 0
        var connects = 0
        assertTrue(guard.launch(this, onDone = { done++ }) { connects++ })
        advanceUntilIdle()
        assertEquals(1, done)
        assertTrue(guard.launch(this) { connects++ })
        advanceUntilIdle()
        assertEquals(2, connects)
    }
}
