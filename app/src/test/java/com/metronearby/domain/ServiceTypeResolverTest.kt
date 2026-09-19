package com.metronearby.domain

import com.metronearby.data.model.ServiceTypes
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceTypeResolverTest {

    private fun calendar(
        year: Int = 2026,
        month: Int = Calendar.SEPTEMBER,
        day: Int = 14,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0
    ) = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, second)
        set(Calendar.MILLISECOND, 0)
    }

    @Test
    fun `周一到周五按工作日时刻表`() {
        // 2026-09-14 起连续五天均为工作日
        for (offset in 0 until 5) {
            val day = 14 + offset
            assertEquals(
                "day=$day 应为工作日",
                ServiceTypes.WEEKDAY,
                ServiceTypeResolver.from(calendar(day = day))
            )
        }
    }

    @Test
    fun `周六按周末时刻表`() {
        assertEquals(ServiceTypes.WEEKEND, ServiceTypeResolver.from(calendar(day = 19)))
    }

    @Test
    fun `周日按周末时刻表`() {
        assertEquals(ServiceTypes.WEEKEND, ServiceTypeResolver.from(calendar(day = 20)))
    }

    @Test
    fun `秒偏移包含时分秒`() {
        assertEquals(8 * 3600 + 5 * 60 + 30, ServiceTypeResolver.secondsOfDay(calendar(hour = 8, minute = 5, second = 30)))
    }

    @Test
    fun `零点秒偏移为零`() {
        assertEquals(0, ServiceTypeResolver.secondsOfDay(calendar(hour = 0, minute = 0, second = 0)))
    }

    @Test
    fun `秒偏移是一天中的最大值在最后一秒`() {
        assertEquals(86399, ServiceTypeResolver.secondsOfDay(calendar(hour = 23, minute = 59, second = 59)))
    }

    @Test
    fun `当前时刻解析结果始终落在合法取值内`() {
        val serviceType = ServiceTypeResolver.fromNow()
        assertTrue(
            "当前时刻应解析为工作日或周末，实际为 $serviceType",
            serviceType == ServiceTypes.WEEKDAY || serviceType == ServiceTypes.WEEKEND
        )

        val seconds = ServiceTypeResolver.currentSecondsOfDay()
        assertTrue("当天秒数应落在 [0, 86400) 内，实际为 $seconds", seconds in 0 until 24 * 3600)
    }
}