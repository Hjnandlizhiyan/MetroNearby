package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.ManagedTrip
import com.metronearby.data.model.ServiceTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TripStationPlannerTest {
    private val line = MetroJson.parseLine(
        javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!
            .bufferedReader().use { it.readText() }
    )
    private val east = line.patternById("east_full")!!

    @Test fun `同一班修改中间站后延误传播到后续站`() {
        val trip = ManagedTrip("t1", "06:00")
        val updated = TripStationPlanner.updateStation(
            line, east, ServiceTypes.WEEKDAY, trip, "s2", seconds("06:04")
        )!!
        val times = TripStationPlanner.timeline(line, east, ServiceTypes.WEEKDAY, updated)
        assertEquals(listOf("06:00", "06:04", "06:06", "06:08"),
            times.map { TimeUtils.formatSecondsOfDay(it.finalSeconds) })
    }

    @Test fun `后续新锚点从该站重新确定延误`() {
        val trip = ManagedTrip("t1", "06:00", mapOf("s2" to "06:04", "s3" to "06:05"))
        val times = TripStationPlanner.timeline(line, east, ServiceTypes.WEEKDAY, trip)
        assertEquals(listOf("06:00", "06:04", "06:05", "06:07"),
            times.map { TimeUtils.formatSecondsOfDay(it.finalSeconds) })
    }

    @Test fun `修改起点时整班及已有锚点共同平移`() {
        val trip = ManagedTrip("stable-id", "06:00", mapOf("s2" to "06:04"))
        val updated = TripStationPlanner.updateStation(
            line, east, ServiceTypes.WEEKDAY, trip, "s1", seconds("06:01")
        )!!
        assertEquals("stable-id", updated.id)
        assertEquals("06:01", updated.departureTime)
        assertEquals("06:05", updated.stationTimes.getValue("s2"))
    }

    @Test fun `后站不晚于前站时拒绝时间倒置`() {
        val trip = ManagedTrip("t1", "06:00", mapOf("s2" to "05:59"))
        val result = TripStationPlanner.validate(line, east, ServiceTypes.WEEKDAY, listOf(trip))
        assertTrue(result is TripStationPlanner.Validation.Rejected)
        assertTrue((result as TripStationPlanner.Validation.Rejected).reason.contains("时间倒置"))
    }

    @Test fun `后车在中间站追上前车时拒绝保存`() {
        val first = ManagedTrip("t1", "06:00", mapOf("s2" to "06:10"))
        val second = ManagedTrip("t2", "06:05")
        val result = TripStationPlanner.validate(line, east, ServiceTypes.WEEKDAY, listOf(first, second))
        assertTrue(result is TripStationPlanner.Validation.Rejected)
        assertTrue((result as TripStationPlanner.Validation.Rejected).reason.contains("追上或超过"))
    }

    @Test fun `合法的相邻班次通过防超车校验`() {
        val first = ManagedTrip("t1", "06:00", mapOf("s2" to "06:03"))
        val second = ManagedTrip("t2", "06:05", mapOf("s2" to "06:08"))
        assertEquals(TripStationPlanner.Validation.Valid,
            TripStationPlanner.validate(line, east, ServiceTypes.WEEKDAY, listOf(first, second)))
    }

    @Test fun `区间车时间线只包含运行区段`() {
        val short = line.patternById("east_short")!!
        assertEquals(listOf("s1", "s2", "s3"), TripStationPlanner.routeStationIds(line, short))
    }

    @Test fun `反向交路按实际运行方向排列站点`() {
        val west = line.patternById("west_full")!!
        assertEquals(listOf("s4", "s3", "s2", "s1"), TripStationPlanner.routeStationIds(line, west))
    }

    @Test fun `北京环线两个方向都包含整圈站点`() {
        val ring = MetroJson.parseLine(File("src/main/assets/metro/line_beijing_2.json").readText())
        assertEquals(ring.stationOrder,
            TripStationPlanner.routeStationIds(ring, ring.patternById("forward")!!))
        assertEquals(ring.stationOrder.reversed(),
            TripStationPlanner.routeStationIds(ring, ring.patternById("reverse")!!))
    }

    private fun seconds(text: String) = TimeUtils.parseToSecondsOfDay(text)
}
