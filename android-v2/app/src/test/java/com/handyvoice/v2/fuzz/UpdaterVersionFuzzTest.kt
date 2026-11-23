package com.handyvoice.v2.fuzz

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.code_intelligence.jazzer.junit.FuzzTest
import com.handyvoice.v2.update.Updater
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Jazzer fuzz target for the update gate, [Updater.isNewer]. The remote side is
 * a release tag read off the network, so it is untrusted, and a wrong "newer"
 * is what makes the app offer an install. Invariants:
 *
 * - it never throws, whatever the two strings are;
 * - no version is newer than itself, two versions are never each newer than
 *   the other, and newer-than is transitive;
 * - for well-formed versions the answer equals a plain component-by-component
 *   integer comparison (missing components count as 0), with or without a
 *   leading `v`.
 *
 * Without `JAZZER_FUZZ=1` this only replays the seed corpus as a JUnit test.
 */
class UpdaterVersionFuzzTest {

    @FuzzTest(maxDuration = "10m")
    fun newerThanIsAStrictOrder(data: FuzzedDataProvider) {
        // Well-formed versions, so the fuzzer spends time in the comparison too.
        val x = components(data)
        val y = components(data)
        val xs = (if (data.consumeBoolean()) "v" else "") + x.joinToString(".")
        val ys = (if (data.consumeBoolean()) "v" else "") + y.joinToString(".")
        assertEquals(compare(x, y) > 0, Updater.isNewer(xs, ys), "isNewer($xs, $ys)")
        assertEquals(compare(y, x) > 0, Updater.isNewer(ys, xs), "isNewer($ys, $xs)")
        assertFalse(Updater.isNewer("$xs.0", xs), "a trailing .0 made a version newer")
        assertFalse(Updater.isNewer(xs, "$xs.0"), "a trailing .0 made a version older")

        // Arbitrary text, as a hostile or broken release tag would be.
        val a = data.consumeString(40)
        val b = data.consumeString(40)
        val c = data.consumeRemainingAsString()
        for (s in listOf(a, b, c)) {
            assertFalse(Updater.isNewer(s, s), "a version newer than itself: $s")
            Updater.isNewer(s, xs)
            Updater.isNewer(xs, s)
        }
        val ab = Updater.isNewer(a, b)
        val bc = Updater.isNewer(b, c)
        if (ab) assertFalse(Updater.isNewer(b, a), "each newer than the other: $a, $b")
        if (ab && bc) assertTrue(Updater.isNewer(a, c), "not transitive: $a, $b, $c")
    }

    private fun components(data: FuzzedDataProvider): List<Int> =
        List(data.consumeInt(1, 5)) { data.consumeInt(0, 9999) }

    private fun compare(x: List<Int>, y: List<Int>): Int {
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }
}
