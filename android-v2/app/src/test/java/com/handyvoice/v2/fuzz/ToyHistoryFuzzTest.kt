package com.handyvoice.v2.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.handyvoice.v2.ui.ToyDisconnectRecord
import com.handyvoice.v2.ui.ToyLabFormat
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Jazzer fuzz target for the toy test panel's stored disconnect history
 * ([ToyLabFormat]). The history is a string in prefs that the app reads back on
 * every toy disconnect, in the process that also owns the motion engines, so a
 * damaged value must never throw. Invariants:
 *
 * - any stored text parses without throwing, and writing back what was read
 *   loses nothing;
 * - appending keeps at most [ToyLabFormat.HISTORY_MAX] records, the new one
 *   first, then the previous ones in their old order;
 * - a duration always formats as seconds, minutes, or hours and minutes, for
 *   any pair of timestamps (negative and overflowing differences included).
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class ToyHistoryFuzzTest {

    private val durationShape = Regex("""\d+ s|\d+ min|\d+ h \d+ min""")

    @FuzzTest(maxDuration = "10m")
    fun damagedHistoryNeverThrowsOrGrows(data: FuzzedDataProvider) {
        val rec = ToyDisconnectRecord(
            atMs = data.consumeLong(),
            connectedMs = data.consumeLong(),
            idleMs = data.consumeLong(),
            unexpected = data.consumeBoolean(),
        )
        val now = data.consumeLong()
        val raw = data.consumeRemainingAsString()

        val before = ToyLabFormat.parseHistory(raw)
        assertEquals(before, ToyLabFormat.parseHistory(ToyLabFormat.encodeHistory(before)), "re-encoding lost a record")

        val after = ToyLabFormat.parseHistory(ToyLabFormat.appendHistory(raw, rec))
        assertTrue(after.size <= ToyLabFormat.HISTORY_MAX, "history grew to ${after.size}")
        assertEquals(rec, after.first(), "the new record is not first")
        assertEquals(before.take(ToyLabFormat.HISTORY_MAX - 1), after.drop(1), "older records changed")

        for (r in after) {
            assertTrue(durationShape.matches(ToyLabFormat.duration(r.connectedMs)))
            assertTrue(durationShape.matches(ToyLabFormat.duration(r.idleMs)))
            assertTrue(ToyLabFormat.ago(now, r.atMs).endsWith(" ago"))
            ToyLabFormat.describe(r) { it.toString() }
        }
    }
}
