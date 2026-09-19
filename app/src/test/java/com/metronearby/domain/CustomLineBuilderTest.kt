package com.metronearby.domain

import com.metronearby.data.model.ServiceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLineBuilderTest {
    private fun input() = CustomLineInput(
        stableId = "abc123",
        cityName = "天津",
        lineName = "测试线",
        color = "#2f80ed",
        stations = listOf(
            CustomStationInput("甲", 39.1, 116.1),
            CustomStationInput("乙", 39.2, 116.2),
            CustomStationInput("丙", 39.3, 116.3)
        ),
        firstDeparture = "06:00",
        lastDeparture = "23:00",
        intervalMinutes = 6,
        runMinutesPerStop = 3
    )

    @Test
    fun `构建自定义线路会生成城市标签站序线段和双方向`() {
        val line = (CustomLineBuilder.build(input()) as CustomLineBuilder.Result.Built).line

        assertEquals("天津", line.cityName)
        assertEquals("custom_abc123", line.lineId)
        assertEquals("#2F80ED", line.color)
        assertEquals(3, line.stations.size)
        assertEquals(2, line.segments.size)
        assertEquals(180, line.segments.first().runSeconds)
        assertEquals(setOf("forward", "reverse"), line.patterns.map { it.id }.toSet())
        assertTrue(line.patterns.all { pattern ->
            pattern.services.map { it.serviceType }.toSet() == setOf(ServiceTypes.WEEKDAY, ServiceTypes.WEEKEND)
        })
    }

    @Test
    fun `站点文本同时接受中英文逗号`() {
        val (stations, error) = CustomLineBuilder.parseStations("甲,39.1,116.1\n乙，39.2，116.2")

        assertEquals(null, error)
        assertEquals(listOf("甲", "乙"), stations!!.map { it.name })
    }

    @Test
    fun `少于两个站点会拒绝`() {
        val result = CustomLineBuilder.build(input().copy(stations = input().stations.take(1)))
        assertEquals("至少需要两个站点", (result as CustomLineBuilder.Result.Rejected).reason)
    }

    @Test
    fun `零坐标会拒绝避免定位到错误位置`() {
        val result = CustomLineBuilder.build(input().copy(
            stations = listOf(CustomStationInput("甲", 0.0, 0.0), CustomStationInput("乙", 39.2, 116.2))
        ))
        assertEquals("站点经纬度无效", (result as CustomLineBuilder.Result.Rejected).reason)
    }

    @Test
    fun `重复站名会拒绝`() {
        val result = CustomLineBuilder.build(input().copy(
            stations = listOf(CustomStationInput("甲", 39.1, 116.1), CustomStationInput("甲", 39.2, 116.2))
        ))
        assertEquals("同一线路不能有重名站点", (result as CustomLineBuilder.Result.Rejected).reason)
    }

    @Test
    fun `末班早于首班会拒绝`() {
        val result = CustomLineBuilder.build(input().copy(firstDeparture = "23:00", lastDeparture = "06:00"))
        assertEquals("末班时间必须晚于首班时间", (result as CustomLineBuilder.Result.Rejected).reason)
    }
}
