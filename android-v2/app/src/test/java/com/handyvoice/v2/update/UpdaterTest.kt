package com.handyvoice.v2.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Version-comparison gate for the in-app updater. The critical case is
 * "0.10" vs "0.9": a plain string compare reports 0.9 as newer, which would
 * silently stop updates from ever applying.
 */
class UpdaterTest {

    @Test fun higher_minor_is_newer() {
        assertTrue(Updater.isNewer("0.11", "0.10"))
    }

    @Test fun double_digit_minor_beats_single_digit() {
        // The bug that motivates integer-component comparison.
        assertTrue(Updater.isNewer("0.10", "0.9"))
        assertFalse(Updater.isNewer("0.9", "0.10"))
    }

    @Test fun equal_versions_are_not_newer() {
        assertFalse(Updater.isNewer("0.10", "0.10"))
        assertFalse(Updater.isNewer("1.2.3", "1.2.3"))
    }

    @Test fun older_is_not_newer() {
        assertFalse(Updater.isNewer("0.10", "0.11"))
        assertFalse(Updater.isNewer("1.0.0", "2.0.0"))
    }

    @Test fun leading_v_is_tolerated() {
        assertTrue(Updater.isNewer("v0.11", "0.10"))
        assertTrue(Updater.isNewer("V0.11", "v0.10"))
        assertFalse(Updater.isNewer("v0.10", "v0.10"))
    }

    @Test fun differing_component_counts_compare_by_value() {
        // "1.0" == "1.0.0", and "1.0.1" > "1.0".
        assertFalse(Updater.isNewer("1.0", "1.0.0"))
        assertTrue(Updater.isNewer("1.0.1", "1.0"))
        assertTrue(Updater.isNewer("1.1", "1.0.9"))
    }

    @Test fun malformed_versions_never_update() {
        assertFalse(Updater.isNewer("garbage", "0.10"))
        assertFalse(Updater.isNewer("0.x", "0.10"))
        assertFalse(Updater.isNewer("", "0.10"))
        assertFalse(Updater.isNewer("0.11", "nope"))
    }
}
