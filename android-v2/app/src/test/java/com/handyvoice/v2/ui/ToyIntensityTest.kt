package com.handyvoice.v2.ui

import org.junit.Test
import org.junit.Assert.assertEquals

class ToyIntensityTest {
    @Test fun stepMovesByFiveAndClamps() {
        assertEquals(25, ToyIntensity.step(20, 1))
        assertEquals(15, ToyIntensity.step(20, -1))
        assertEquals(100, ToyIntensity.step(100, 1))
        assertEquals(0, ToyIntensity.step(0, -1))
    }

    @Test fun stepSnapsOffGridValues() {
        assertEquals(25, ToyIntensity.step(22, 1))
        assertEquals(20, ToyIntensity.step(22, -1))
        assertEquals(0, ToyIntensity.step(-7, -1))
        assertEquals(100, ToyIntensity.step(250, 1))
    }

    @Test fun toDeviceScalesByCap() {
        assertEquals(20, ToyIntensity.toDevice(40, 50))
        assertEquals(100, ToyIntensity.toDevice(100, 100))
        assertEquals(0, ToyIntensity.toDevice(0, 30))
        assertEquals(1, ToyIntensity.toDevice(100, 1))
        // A bogus cap is clamped into 1..100 rather than dividing oddly.
        assertEquals(40, ToyIntensity.toDevice(40, 500))
        assertEquals(0, ToyIntensity.toDevice(40, 0))
    }

    @Test fun sliderRoundTrips() {
        for (p in 0..20) assertEquals(p, ToyIntensity.toSlider(ToyIntensity.fromSlider(p)))
        assertEquals(100, ToyIntensity.fromSlider(20))
    }
}
