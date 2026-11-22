package com.handyvoice.v2.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToyLabFormatTest {

    private val min = 60_000L
    private val hour = 60 * min

    @Test
    fun duration_secondsMinutesHours() {
        assertEquals("0 s", ToyLabFormat.duration(0))
        assertEquals("12 s", ToyLabFormat.duration(12_999))
        assertEquals("59 s", ToyLabFormat.duration(59_999))
        assertEquals("1 min", ToyLabFormat.duration(min))
        assertEquals("45 min", ToyLabFormat.duration(45 * min + 30_000))
        assertEquals("59 min", ToyLabFormat.duration(hour - 1))
        assertEquals("1 h 0 min", ToyLabFormat.duration(hour))
        assertEquals("1 h 12 min", ToyLabFormat.duration(hour + 12 * min))
        assertEquals("26 h 5 min", ToyLabFormat.duration(26 * hour + 5 * min))
        assertEquals("0 s", ToyLabFormat.duration(-5_000))
        assertEquals("12 s ago", ToyLabFormat.ago(20_000, 8_000))
    }

    @Test
    fun history_roundTripsAndKeepsLastFiveNewestFirst() {
        var raw = ""
        for (n in 1..7) raw = ToyLabFormat.appendHistory(raw, ToyDisconnectRecord(n * 1000L, n * 10L, n.toLong(), n % 2 == 0))
        val list = ToyLabFormat.parseHistory(raw)
        assertEquals(5, list.size)
        assertEquals(listOf(7000L, 6000L, 5000L, 4000L, 3000L), list.map { it.atMs })
        assertEquals(ToyDisconnectRecord(6000, 60, 6, true), list[1])
        assertEquals(ToyDisconnectRecord(7000, 70, 7, false), list[0])
        assertEquals(emptyList<ToyDisconnectRecord>(), ToyLabFormat.parseHistory(""))
        assertEquals(emptyList<ToyDisconnectRecord>(), ToyLabFormat.parseHistory("garbage\n1|2|x|U"))
    }

    @Test
    fun describe_namesCauseAndDurations() {
        val text = ToyLabFormat.describe(ToyDisconnectRecord(0, hour + 12 * min, 45 * min, true)) { "T" }
        assertEquals("T  dropped (unexpected), connected 1 h 12 min, idle 45 min", text)
    }

    @Test
    fun recordFrom_needsAConnectedAndDisconnectedTime() {
        assertNull(ToyLabFormat.recordFrom(null))
    }
}
