package com.example.optimalx.data.eidos

import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class MemoryRolloverSchedulerTest {

    @Test
    fun delayMillisUntilNextLocalMidnight_fromNoon() {
        val zone = ZoneId.of("America/New_York")
        val noon = ZonedDateTime.of(2026, 5, 2, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val delay = MemoryRolloverScheduler.delayMillisUntilNextLocalMidnight(noon, zone)
        val expected = 12L * 60L * 60L * 1000L
        assertTrue(kotlin.math.abs(delay - expected) < 2_000L)
    }

    @Test
    fun delayMillisUntilNextLocalMidnight_neverBelowOneMinute() {
        val zone = ZoneId.of("UTC")
        val almostMidnight = ZonedDateTime.of(2026, 5, 2, 23, 59, 30, 0, zone).toInstant().toEpochMilli()
        val delay = MemoryRolloverScheduler.delayMillisUntilNextLocalMidnight(almostMidnight, zone)
        assertTrue(delay >= 60_000L)
        assertTrue(delay <= 90_000L)
    }
}
