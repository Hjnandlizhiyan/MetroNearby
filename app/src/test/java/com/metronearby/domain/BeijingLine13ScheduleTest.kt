package com.metronearby.domain

import com.metronearby.data.MetroJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BeijingLine13ScheduleTest {
    private val line = MetroJson.parseLine(
        File("src/main/assets/metro/line_beijing_13.json").readText()
    )

    @Test
    fun `站序包含清河站且两端正确`() {
        assertEquals("东直门", line.stationById(line.stationOrder.first())!!.name)
        assertEquals("清河站", line.stationById("bj13_12")!!.name)
        assertEquals("西直门", line.stationById(line.stationOrder.last())!!.name)
    }

    @Test
    fun `沿途全程末班偏移采用官网逐站时间`() {
        val estimator = ArrivalEstimator(line)

        assertEquals(
            "23:07",
            TimeUtils.formatSecondsOfDay(
                estimator.serviceWindow("bj13_06", "weekday", "forward")!!.lastSecondsOfDay
            )
        )
        assertEquals(
            "23:28",
            TimeUtils.formatSecondsOfDay(
                estimator.serviceWindow("bj13_05", "weekday", "reverse")!!.lastSecondsOfDay
            )
        )
    }

    @Test
    fun `最长区间运行时间不再沿用统一130秒`() {
        val longest = line.segments.single { it.from == "bj13_05" && it.to == "bj13_06" }

        assertEquals(450, longest.runSeconds)
    }

    @Test
    fun `周末平峰间隔与工作日不同`() {
        for (pattern in line.patterns) {
            val weekday = pattern.services.single { it.serviceType == "weekday" }.headways
            val weekend = pattern.services.single { it.serviceType == "weekend" }.headways
            assertNotEquals(weekday, weekend)
        }
    }
}
