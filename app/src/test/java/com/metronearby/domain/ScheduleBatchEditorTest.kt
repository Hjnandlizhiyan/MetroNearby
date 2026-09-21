package com.metronearby.domain

import com.metronearby.data.model.HeadwayRule
import com.metronearby.data.model.ManagedTrip
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.Segment
import com.metronearby.data.model.Service
import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleBatchEditorTest {
    private val pattern = Pattern(
        id = "forward",
        name = "往乙站",
        startStationId = "a",
        endStationId = "b",
        services = listOf(
            Service(
                serviceType = ServiceTypes.WEEKDAY,
                firstDeparture = "05:00",
                lastDeparture = "23:00",
                headways = listOf(HeadwayRule("05:00", "23:00", 360))
            )
        )
    )
    private val line = MetroLine(
        lineId = "test",
        lineName = "测试线",
        stationOrder = listOf("a", "b"),
        stations = listOf(Station("a", "甲站", 0.0, 0.0), Station("b", "乙站", 0.0, 0.0)),
        segments = listOf(Segment("a", "b", 180)),
        patterns = listOf(pattern)
    )

    @Test
    fun shiftMovesSelectedTripAndItsAnchors() {
        val trips = listOf(
            ManagedTrip("one", "06:00", mapOf("b" to "06:03")),
            ManagedTrip("two", "06:10", mapOf("b" to "06:13"))
        )

        val result = ScheduleBatchEditor.shift(
            line, pattern, ServiceTypes.WEEKDAY, trips, setOf("two"), 2
        ) as ScheduleBatchEditor.Result.Updated

        assertEquals("06:00", result.trips[0].departureTime)
        assertEquals("06:12", result.trips[1].departureTime)
        assertEquals("06:15", result.trips[1].stationTimes["b"])
        assertEquals("two", result.trips[1].id)
    }

    @Test
    fun shiftRejectsOvertaking() {
        val trips = listOf(
            ManagedTrip("one", "06:00"),
            ManagedTrip("two", "06:05")
        )

        val result = ScheduleBatchEditor.shift(
            line, pattern, ServiceTypes.WEEKDAY, trips, setOf("one"), 5
        )

        assertTrue(result is ScheduleBatchEditor.Result.Rejected)
    }

    @Test
    fun removeKeepsUnselectedTrips() {
        val trips = listOf(
            ManagedTrip("one", "06:00"),
            ManagedTrip("two", "06:05")
        )

        val result = ScheduleBatchEditor.remove(
            line, pattern, ServiceTypes.WEEKDAY, trips, setOf("one")
        ) as ScheduleBatchEditor.Result.Updated

        assertEquals(listOf("two"), result.trips.map { it.id })
    }

    @Test
    fun removeRejectsEmptySchedule() {
        val result = ScheduleBatchEditor.remove(
            line,
            pattern,
            ServiceTypes.WEEKDAY,
            listOf(ManagedTrip("one", "06:00")),
            setOf("one")
        )

        assertTrue(result is ScheduleBatchEditor.Result.Rejected)
    }
}