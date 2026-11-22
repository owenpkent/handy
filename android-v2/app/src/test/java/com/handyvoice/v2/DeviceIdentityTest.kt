package com.handyvoice.v2

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIdentityTest {

    @Test
    fun justPaired_blankSavedAddress_isNotADeviceChange() {
        // Transport paired "AA" but the prefs write has not landed: blank snapshot.
        assertTrue(deviceMatches(true, "AA", "AA", true, ""))
        assertTrue(deviceMatches(true, "AA", "AA", true, "AA"))
    }

    @Test
    fun forgetToy_afterSaveLanded_isADeviceChange() {
        // No pending save: a blank saved address while a paired transport is installed.
        assertFalse(deviceMatches(true, "AA", null, true, ""))
    }

    @Test
    fun differentAddress_isADeviceChange() {
        assertFalse(deviceMatches(true, "AA", null, true, "BB"))
        assertFalse(deviceMatches(true, "AA", "AA", true, "BB"))
    }

    @Test
    fun kindSwitch_isADeviceChange_andSameKindMatches() {
        assertFalse(deviceMatches(false, null, null, true, ""))
        assertFalse(deviceMatches(true, null, null, false, ""))
        assertTrue(deviceMatches(false, null, null, false, ""))
        assertTrue(deviceMatches(true, null, null, true, ""))
        assertTrue(deviceMatches(true, "AA", null, true, "AA"))
    }
}
