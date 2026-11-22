package com.handyvoice.v2.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class LovenseProtocolTest {

    @Test
    fun levelMapping_endpointsAndRounding() {
        assertEquals(0, LovenseProtocol.levelForVelocity(0))
        assertEquals(20, LovenseProtocol.levelForVelocity(100))
        assertEquals(10, LovenseProtocol.levelForVelocity(50))
        assertEquals(6, LovenseProtocol.levelForVelocity(30))
        // Out of range is clamped.
        assertEquals(0, LovenseProtocol.levelForVelocity(-5))
        assertEquals(20, LovenseProtocol.levelForVelocity(250))
    }

    @Test
    fun levelMapping_anyPositiveVelocityIsAtLeastOne() {
        for (v in 1..2) assertEquals("v=$v", 1, LovenseProtocol.levelForVelocity(v))
        // Monotonic non-decreasing across the range.
        var prev = 0
        for (v in 0..100) {
            val l = LovenseProtocol.levelForVelocity(v)
            assertTrue(l >= prev)
            prev = l
        }
    }

    @Test
    fun commandStrings() {
        assertEquals("Vibrate:7;", LovenseProtocol.vibrate(7))
        assertEquals("Vibrate:20;", LovenseProtocol.vibrate(99))
        assertEquals("Vibrate:0;", LovenseProtocol.CMD_STOP)
        assertEquals("Battery;", LovenseProtocol.CMD_BATTERY)
        assertEquals("DeviceType;", LovenseProtocol.CMD_DEVICE_TYPE)
        assertEquals("AutoSwith:1:0;", LovenseProtocol.CMD_AUTO_SWITCH)
    }

    @Test
    fun parseBattery() {
        assertEquals(85, LovenseProtocol.parseBattery("85;"))
        assertEquals(0, LovenseProtocol.parseBattery("0;"))
        assertNull(LovenseProtocol.parseBattery("OK;"))
        assertNull(LovenseProtocol.parseBattery(";"))
        assertNull(LovenseProtocol.parseBattery("250;"))
    }

    @Test
    fun parseDeviceType() {
        val info = LovenseProtocol.parseDeviceType("C:11:0082059AD3BD;")
        assertNotNull(info)
        assertEquals("C", info!!.model)
        assertEquals("11", info.firmware)
        assertEquals("0082059AD3BD", info.address)
        assertNull(LovenseProtocol.parseDeviceType("OK;"))
        assertNull(LovenseProtocol.parseDeviceType("C::0082059AD3BD;"))
    }

    @Test
    fun gen3UuidDerivation() {
        val svc = "5a300001-0024-4bd4-bbd5-a6920e4c5653"
        assertTrue(LovenseProtocol.isGen3Service(svc))
        assertEquals("5a300002-0024-4bd4-bbd5-a6920e4c5653", LovenseProtocol.gen3Tx(svc))
        assertEquals("5a300003-0024-4bd4-bbd5-a6920e4c5653", LovenseProtocol.gen3Rx(svc))
        assertTrue(LovenseProtocol.isGen3Service(svc.uppercase()))
        // Wrong third nibble group / wrong tail.
        assertFalse(LovenseProtocol.isGen3Service("5a300001-0034-4bd4-bbd5-a6920e4c5653"))
        assertFalse(LovenseProtocol.isGen3Service("5a300001-0024-4bd4-bbd5-a6920e4c5654"))
        assertFalse(LovenseProtocol.isGen3Service(LovenseProtocol.GEN2_SERVICE))
    }

    @Test
    fun channelSelection() {
        val g1 = LovenseProtocol.channelFor(UUID.fromString(LovenseProtocol.GEN1_SERVICE))!!
        assertEquals(UUID.fromString(LovenseProtocol.GEN1_TX), g1.second)
        assertEquals(UUID.fromString(LovenseProtocol.GEN1_RX), g1.third)
        val g2 = LovenseProtocol.channelFor(UUID.fromString(LovenseProtocol.GEN2_SERVICE))!!
        assertEquals(UUID.fromString(LovenseProtocol.GEN2_TX), g2.second)
        assertEquals(UUID.fromString(LovenseProtocol.GEN2_RX), g2.third)
        val g3 = LovenseProtocol.channelFor(UUID.fromString("5a300001-0024-4bd4-bbd5-a6920e4c5653"))!!
        assertEquals(UUID.fromString("5a300002-0024-4bd4-bbd5-a6920e4c5653"), g3.second)
        assertEquals(UUID.fromString("5a300003-0024-4bd4-bbd5-a6920e4c5653"), g3.third)
        assertNull(LovenseProtocol.channelFor(UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")))
    }

    @Test
    fun nameFilter() {
        assertTrue(LovenseProtocol.isLovenseName("LVS-Gush2"))
        assertTrue(LovenseProtocol.isLovenseName("LOVE-Diamo"))
        assertFalse(LovenseProtocol.isLovenseName("Handy"))
        assertFalse(LovenseProtocol.isLovenseName(null))
    }

    @Test
    fun replyBuffer_reassemblesSplitAndBatchedReplies() {
        val b = LovenseProtocol.ReplyBuffer()
        assertEquals(emptyList<String>(), b.feed("C:1"))
        assertEquals(listOf("C:11:0082059AD3BD;"), b.feed("1:0082059AD3BD;"))
        assertEquals(listOf("OK;", "85;"), b.feed("OK;85;"))
        assertEquals(emptyList<String>(), b.feed("7"))
        assertEquals(listOf("7;"), b.feed(";"))
    }
}
