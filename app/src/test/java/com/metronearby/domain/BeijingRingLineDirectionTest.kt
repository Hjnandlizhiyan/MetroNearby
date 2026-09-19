package com.metronearby.domain

import com.metronearby.data.MetroJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeijingRingLineDirectionTest {
    @Test
    fun `二号线晚间一个方向收车后两端仍显示内外环`() {
        assertLateNightEndpointsKeepBothDirections(2)
    }

    @Test
    fun `十号线晚间一个方向收车后两端仍显示内外环`() {
        assertLateNightEndpointsKeepBothDirections(10)
    }

    private fun assertLateNightEndpointsKeepBothDirections(number: Int) {
        val line = MetroJson.parseLine(File("src/main/assets/metro/line_beijing_$number.json").readText())
        val estimator = ArrivalEstimator(line)
        val now = TimeUtils.parseToSecondsOfDay("23:10")

        for (stationId in listOf(line.stationOrder.first(), line.stationOrder.last())) {
            val schedules = estimator.directionSchedules(stationId, "weekday", now)
            assertEquals(setOf("forward", "reverse"), schedules.map { it.directionId }.toSet())
            assertEquals(1, schedules.count { it.arrivals.isEmpty() })
            assertTrue(schedules.all { it.serviceWindow != null })
        }
    }
}
