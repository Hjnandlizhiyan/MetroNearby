package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.ServiceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartureSchedulePlannerTest {
    private val line = MetroJson.parseLine(
        javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!
            .bufferedReader().use { it.readText() }
    )

    @Test fun `系统班次按工作日与周末分别生成`() {
        val pattern = line.patternById("east_full")!!
        val weekday = DepartureSchedulePlanner.systemDepartures(line, pattern, ServiceTypes.WEEKDAY)
        val weekend = DepartureSchedulePlanner.systemDepartures(line, pattern, ServiceTypes.WEEKEND)
        assertTrue(weekday.size > weekend.size)
        assertEquals(seconds("06:00"), weekday.first())
        assertEquals(seconds("07:00"), weekday.last())
    }

    @Test fun `调整班次数保留首末班并保持严格递增`() {
        val source = listOf("06:00", "06:02", "06:04", "06:10", "06:20").map(::seconds)
        val result = DepartureSchedulePlanner.resize(source, 8) as DepartureSchedulePlanner.ResizeResult.Resized
        assertEquals(8, result.departures.size)
        assertEquals(source.first(), result.departures.first())
        assertEquals(source.last(), result.departures.last())
        assertTrue(result.departures.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun `增加班次数仍保留原高峰密度倾向`() {
        val source = listOf("06:00", "06:02", "06:04", "06:30").map(::seconds)
        val result = (DepartureSchedulePlanner.resize(source, 7) as DepartureSchedulePlanner.ResizeResult.Resized).departures
        assertTrue(result.count { it <= seconds("06:10") } > result.count { it > seconds("06:10") })
    }

    @Test fun `一分钟粒度容不下目标班次数时拒绝`() {
        val result = DepartureSchedulePlanner.resize(listOf(seconds("08:00"), seconds("08:02")), 4)
        assertTrue(result is DepartureSchedulePlanner.ResizeResult.Rejected)
    }

    @Test fun `逐班输入会去重排序`() {
        assertEquals(
            listOf(seconds("08:00"), seconds("08:10")),
            DepartureSchedulePlanner.normalize(listOf("08:10", " 08:00 ", "08:10"))
        )
    }

    @Test fun `非法逐班输入被拒绝`() {
        assertEquals(null, DepartureSchedulePlanner.normalize(listOf("08:00", "25:00")))
    }

    private fun seconds(value: String) = TimeUtils.parseToSecondsOfDay(value)
}
