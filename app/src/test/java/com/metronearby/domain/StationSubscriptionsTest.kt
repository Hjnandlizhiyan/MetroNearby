package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.*
import org.junit.Assert.*
import org.junit.Test

class StationSubscriptionsTest {
    private val line = MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!.bufferedReader().use { it.readText() })
    private val name = line.stationById("s2")!!.name
    private val item = StationSubscription(name, line.lineId, "east")
    private fun preview(value: StationSubscription = item, now: Int = 21600) =
        StationSubscriptions.preview(value, listOf(line), emptyMap(), ServiceTypes.WEEKDAY, now)

    @Test fun `重复订阅更新通勤方向`() {
        assertEquals(listOf(item), StationSubscriptions.upsert(listOf(StationSubscription(name)), item))
    }
    @Test fun `站名后缀不造成重复订阅`() {
        assertEquals(1, StationSubscriptions.upsert(listOf(item), item.copy(stationName = " ${name.removeSuffix("站")} ")).size)
    }
    @Test fun `编辑保持原来的站点顺序`() {
        val other = StationSubscription("别站")
        assertEquals(listOf(item, other), StationSubscriptions.upsert(listOf(StationSubscription(name), other), item))
    }
    @Test fun `取消订阅只删除指定站`() {
        val other = StationSubscription("别站")
        assertEquals(listOf(other), StationSubscriptions.remove(listOf(item, other), name))
    }
    @Test fun `空站名不能保存`() {
        assertTrue(StationSubscriptions.upsert(emptyList(), StationSubscription(" ")).isEmpty())
    }
    @Test fun `全部线路清除残留方向`() {
        assertNull(StationSubscriptions.upsert(emptyList(), item.copy(lineId = null)).single().directionId)
    }
    @Test fun `只显示所选通勤方向`() {
        assertEquals(listOf("east"), preview().map { it.arrival!!.directionId })
    }
    @Test fun `全部方向同时展示`() {
        assertEquals(2, preview(item.copy(directionId = null)).size)
    }
    @Test fun `失效方向不回退到其他方向`() {
        assertTrue(preview(item.copy(directionId = "missing")).isEmpty())
    }
    @Test fun `失效线路不回退到其他线路`() {
        assertTrue(preview(item.copy(lineId = "missing")).isEmpty())
    }
    @Test fun `换乘站只显示选中的线路`() {
        val other = line.copy(lineId = "other", lineName = "另一条线")
        assertEquals(listOf(line.lineName), StationSubscriptions.preview(item, listOf(line, other), emptyMap(), ServiceTypes.WEEKDAY, 21600).map { it.lineName })
    }
    @Test fun `全部线路包括换乘线路`() {
        val other = line.copy(lineId = "other", lineName = "另一条线")
        assertEquals(4, StationSubscriptions.preview(StationSubscription(name), listOf(line, other), emptyMap(), ServiceTypes.WEEKDAY, 21600).size)
    }
    @Test fun `收车后保留所选方向`() {
        assertEquals(1, preview(now = 86399).size)
    }
    @Test fun `收车后显示首班预计时间`() {
        assertTrue(StationSubscriptions.arrivalText(preview(now = 86399).single(), 86399).startsWith("今日已收车 · 首班预计"))
    }
    @Test fun `禁用交路后不提示虚假的预计班次`() {
        val overrides = UserOverrides(lineId = line.lineId, patternEnabled = line.patterns.associate { it.id to false })
        val result = StationSubscriptions.preview(item, listOf(line), mapOf(line.lineId to overrides), ServiceTypes.WEEKDAY, 21600)
        assertEquals("当前方向暂无预计班次", StationSubscriptions.arrivalText(result.single(), 21600))
    }
    @Test fun `终点站不提供清客方向`() {
        assertEquals(setOf("west"), ArrivalEstimator(line).boardingDirections("s4").keys)
    }
    @Test fun `环线闭合点仍有双方向`() {
        val ring = line.copy(segments = line.segments + Segment("s4", "s1", 120))
        assertEquals(2, ArrivalEstimator(ring).boardingDirections("s4").size)
    }
    @Test fun `未知站没有通勤方向`() {
        assertTrue(ArrivalEstimator(line).boardingDirections("missing").isEmpty())
    }
    @Test fun `配置不依赖当日是否运营`() {
        val noService = line.copy(patterns = line.patterns.map { it.copy(services = emptyList()) })
        assertTrue(StationSubscriptions.valid(item, listOf(noService)))
    }
    @Test fun `订阅序列化后保留通勤配置`() {
        assertEquals(listOf(item), MetroJson.parseSubscriptions(MetroJson.encodeSubscriptions(listOf(item))))
    }
    @Test fun `损坏存储不使应用崩溃`() {
        assertTrue(MetroJson.parseSubscriptions("broken").isEmpty())
    }
    @Test fun `存储缺省字段表示全部线路`() {
        assertEquals(listOf(StationSubscription("西单")), MetroJson.parseSubscriptions("[{\"stationName\":\"西单\"}]"))
    }
    @Test fun `失效配置不会从存储中自动删除`() {
        val stale = item.copy(lineId = "missing")
        assertEquals(listOf(stale), MetroJson.parseSubscriptions(MetroJson.encodeSubscriptions(listOf(stale))))
    }
    @Test fun `方向首末班只统计指定方向`() {
        assertNull(ArrivalEstimator(line).serviceWindow("s2", ServiceTypes.WEEKDAY, "missing"))
    }
    @Test fun `另一方向的晚班不影响所选方向收车`() {
        val changed = line.copy(exactDepartures = mapOf("s2" to mapOf("east_full" to listOf("06:00"), "east_short" to listOf("06:00"), "west_full" to listOf("23:00"))))
        assertEquals(21600, ArrivalEstimator(changed).serviceWindow("s2", ServiceTypes.WEEKDAY, "east")!!.lastSecondsOfDay)
    }
    @Test fun `环线区间车终点不可订阅清客方向`() {
        val shortOnly = line.copy(segments = line.segments + Segment("s4", "s1", 120), patterns = line.patterns.filter { it.id == "east_short" })
        assertTrue(ArrivalEstimator(shortOnly).boardingDirections("s3").isEmpty())
    }
    @Test fun `订阅页并列显示用户校准倒计时`() {
        val calibrated = preview().single().copy(
            arrival = preview().single().arrival!!.copy(
                userAverage = UserObservationAverage(60, 2, UserCalibrationScope.TIME_BAND, ObservationTimeBand.MORNING_PEAK, ServiceTypes.WEEKDAY)
            )
        )
        assertTrue(
            StationSubscriptions.arrivalText(calibrated, 21600, ArrivalDisplayMode.USER_CALIBRATED)
                .startsWith("用户早高峰校准")
        )
    }
    @Test fun `没有样本时订阅页提示回退系统预计`() {
        assertEquals(
            "暂无用户校准，已使用系统预计",
            StationSubscriptions.arrivalModeNote(preview().single(), 21600, ArrivalDisplayMode.USER_CALIBRATED)
        )
    }
    @Test fun `未知站点配置提示失效`() {
        assertTrue(StationSubscriptions.description(StationSubscription("不存在"), listOf(line)).contains("已失效"))
    }
}
