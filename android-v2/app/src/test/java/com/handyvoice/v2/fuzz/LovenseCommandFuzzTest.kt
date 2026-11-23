package com.handyvoice.v2.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.handyvoice.v2.api.LovenseProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Jazzer fuzz target for what the app may write to a toy: the test panel's
 * probe allow-list and the velocity to motor-level mapping. Invariants:
 *
 * - a probe is accepted only when it is exactly `<Name>:<N>;` for an
 *   allow-listed name and N in 0..20, in canonical digits, so nothing can be
 *   chained after it or smuggled in with padding or a sign;
 * - `isAllowedProbe` and `parseProbe` always agree;
 * - any velocity maps to a level in 0..20, zero exactly for velocity 0 and
 *   below, and a higher velocity never maps to a lower level;
 * - every motor command the transport can build is itself an allowed probe.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class LovenseCommandFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun onlyCanonicalCommandsAreAccepted(data: FuzzedDataProvider) {
        val v1 = data.consumeInt()
        val v2 = data.consumeInt()
        val cmd = data.consumeRemainingAsString()

        val parsed = LovenseProtocol.parseProbe(cmd)
        assertEquals(parsed != null, LovenseProtocol.isAllowedProbe(cmd), "isAllowedProbe disagrees with parseProbe")
        if (parsed != null) {
            val (name, level) = parsed
            assertTrue(name in LovenseProtocol.PROBE_NAMES, "accepted a name off the allow-list: $name")
            assertTrue(level in 0..LovenseProtocol.MAX_LEVEL, "accepted level $level")
            assertEquals("$name:$level;", cmd, "accepted a non-canonical or chained command")
            assertEquals(cmd, LovenseProtocol.probeCommand(name, level))
        }

        val l1 = LovenseProtocol.levelForVelocity(v1)
        val l2 = LovenseProtocol.levelForVelocity(v2)
        assertTrue(l1 in 0..LovenseProtocol.MAX_LEVEL, "level $l1 out of range")
        assertEquals(v1 <= 0, l1 == 0, "only velocity 0 (or below) may map to level 0")
        if (v1 <= v2) assertTrue(l1 <= l2, "a higher velocity gave a lower level")

        val written = LovenseProtocol.vibrate(v1)
        assertEquals(
            "Vibrate" to v1.coerceIn(0, LovenseProtocol.MAX_LEVEL),
            LovenseProtocol.parseProbe(written),
            "the transport built a motor command the allow-list rejects: $written",
        )
    }
}
