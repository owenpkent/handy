package com.handyvoice.v2.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class MfccDtwEngineTest {

    private fun signal(freq: Double, dur: Double, rate: Int = 16_000): ShortArray {
        val n = (dur * rate).toInt()
        return ShortArray(n) { i ->
            (sin(2.0 * PI * freq * i / rate) * 10_000).toInt().toShort()
        }
    }

    @Test fun no_templates_returns_null() {
        val engine = MfccDtwEngine()
        assertNull(engine.match(signal(440.0, 0.5)))
    }

    @Test fun trained_label_matches_similar_signal() {
        val engine = MfccDtwEngine()
        engine.addTake("low", signal(220.0, 0.6))
        engine.addTake("high", signal(880.0, 0.6))
        val m = engine.match(signal(220.0, 0.6))
        assertNotNull(m)
        assertEquals("low", m!!.label)
        assertTrue("score must beat runner-up", m.score < m.runnerUp)
    }

    @Test fun two_takes_per_label_does_not_break_match() {
        val engine = MfccDtwEngine()
        engine.addTake("a", signal(300.0, 0.5))
        engine.addTake("a", signal(310.0, 0.5))
        engine.addTake("b", signal(1500.0, 0.5))
        val m = engine.match(signal(305.0, 0.5))
        assertEquals("a", m?.label)
    }

    @Test fun clearAll_removes_labels() {
        val engine = MfccDtwEngine()
        engine.addTake("x", signal(440.0, 0.4))
        assertTrue(engine.labels().contains("x"))
        engine.clearAll()
        assertTrue(engine.labels().isEmpty())
    }
}
