@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.handyvoice.v2.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ListeningSessionTest {

    @Test
    fun startIsNoOpWhileActive() = runTest {
        val session = ListeningSession(this)
        assertTrue(session.start { delay(1000) })
        assertFalse(session.start { })
        assertTrue(session.isActive)
        session.cancel()
        assertFalse(session.isActive)
        advanceUntilIdle()
    }

    @Test
    fun pendingResultDoesNotDispatchAfterCancel() = runTest {
        val session = ListeningSession(this)
        val matcher = CompletableDeferred<String>()
        val dispatched = mutableListOf<String>()
        session.start { launch { dispatched += matcher.await() } }
        runCurrent()

        session.cancel()
        matcher.complete("begin")
        advanceUntilIdle()

        assertTrue(dispatched.isEmpty())
    }

    @Test
    fun cancelThenWaitsForChildThatSwallowsCancellation() = runTest {
        val session = ListeningSession(this)
        val gate = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        session.start {
            launch {
                // Mimics HandyV2ApiClient: catch (e: Exception) swallows the
                // CancellationException and the command keeps going.
                try {
                    gate.await()
                } catch (_: Exception) {
                }
                withContext(NonCancellable) { delay(100) }
                events += "begin"
            }
        }
        runCurrent()

        session.cancelThen { events += "stop" }
        advanceUntilIdle()

        assertEquals(listOf("begin", "stop"), events)
    }

    @Test
    fun oldSessionResultNeverRunsAfterRestart() = runTest {
        val session = ListeningSession(this)
        val oldMatcher = CompletableDeferred<String>()
        val events = mutableListOf<String>()
        session.start { launch { events += "old:" + oldMatcher.await() } }
        runCurrent()

        session.cancel()
        assertTrue(session.start { launch { events += "new" } })
        oldMatcher.complete("begin")
        advanceUntilIdle()

        assertEquals(listOf("new"), events)
        session.cancel()
        assertNull(session.cancel())
    }
}
