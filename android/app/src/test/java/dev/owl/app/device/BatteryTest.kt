package dev.owl.app.device

import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryTest {
    @Test
    fun percentFromCurve() {
        assertEquals(100, Battery.percent(4250))
        assertEquals(0, Battery.percent(3500))
        assertEquals(0, Battery.percent(3300))
        assertEquals(70, Battery.percent(3950))
        assertEquals(73, Battery.percent(3968))
    }

    @Test
    fun hoursLeftUsesObservedRateWhenAvailable() {
        val h = 3_600_000L
        // 75 % → 70 % over 5 h = 1 %/h, at 70 % → 70 h.
        val history = listOf(0L to 3980, 5 * h to 3950)
        assertEquals(70, Battery.hoursLeft(3950, history))
    }

    @Test
    fun hoursLeftFallsBackToNominal() {
        // 250 mAh × 70 % / 3.5 mA = 50 h
        assertEquals(50, Battery.hoursLeft(3950, emptyList()))
    }

    @Test
    fun chargingResetsTheRun() {
        val h = 3_600_000L
        val history = listOf(0L to 3700, h to 4100, 3 * h to 4020)
        // Only the run after charging counts: 88 % → 80 % in 2 h = 4 %/h → 20 h.
        assertEquals(20, Battery.hoursLeft(4020, history))
    }
}
