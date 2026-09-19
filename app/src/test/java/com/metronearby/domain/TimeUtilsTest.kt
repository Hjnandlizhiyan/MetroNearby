package com.metronearby.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class TimeUtilsTest {

    @Test
    fun `解析 HH mm 为当天秒数`() {
        assertEquals(0, TimeUtils.parseToSecondsOfDay("00:00"))
        assertEquals(19260, TimeUtils.parseToSecondsOfDay("05:21"))
        assertEquals(23 * 3600 + 26 * 60, TimeUtils.parseToSecondsOfDay("23:26"))
    }

    @Test
    fun `容忍首尾空格`() {
        assertEquals(19260, TimeUtils.parseToSecondsOfDay("  05:21 "))
    }

    @Test
    fun `格式化当天秒数`() {
        assertEquals("05:21", TimeUtils.formatSecondsOfDay(19260))
        assertEquals("00:00", TimeUtils.formatSecondsOfDay(0))
    }

    @Test
    fun `超过一天的秒数取模`() {
        assertEquals("00:30", TimeUtils.formatSecondsOfDay(TimeUtils.SECONDS_PER_DAY + 1800))
    }

    @Test
    fun `非法输入抛异常`() {
        assertThrows(IllegalArgumentException::class.java) { TimeUtils.parseToSecondsOfDay("0521") }
        assertThrows(IllegalArgumentException::class.java) { TimeUtils.parseToSecondsOfDay("05:61") }
        assertThrows(IllegalArgumentException::class.java) { TimeUtils.parseToSecondsOfDay("aa:bb") }
    }

    @Test
    fun `校验用户输入的当天时刻`() {
        assertEquals(0, TimeUtils.parseClockTime("00:00"))
        assertEquals(19260, TimeUtils.parseClockTime("  05:21 "))
        assertEquals(8 * 3600 + 35 * 60, TimeUtils.parseClockTime("08:35"))
    }

    @Test
    fun `非法时刻返回 null`() {
        assertNull(TimeUtils.parseClockTime("0521"))
        assertNull(TimeUtils.parseClockTime("05:61"))
        assertNull(TimeUtils.parseClockTime("aa:bb"))
        assertNull(TimeUtils.parseClockTime(""))
        // 与 parseToSecondsOfDay 不同：用户记录的是当天时刻，不接受跨零点的 24:xx
        assertNull(TimeUtils.parseClockTime("24:00"))
    }
}