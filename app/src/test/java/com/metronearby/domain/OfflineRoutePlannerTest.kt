package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Segment
import com.metronearby.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineRoutePlannerTest {
    private val lineOne = line(
        id = "one",
        name = "1号线",
        stations = listOf("a" to "甲", "x1" to "换乘", "b" to "乙")
    )
    private val lineTwo = line(
        id = "two",
        name = "2号线",
        stations = listOf("x2" to "换乘", "c" to "丙", "d" to "丁")
    )

    @Test
    fun joinsTransferStationsByNameWithinCity() {
        val choices = OfflineRoutePlanner.stationChoices(listOf(lineOne, lineTwo))
        val transfer = choices.single { it.stationName == "换乘" }

        assertEquals(listOf("1号线", "2号线"), transfer.lineNames)
    }

    @Test
    fun plansRouteAndGroupsLegs() {
        val choices = OfflineRoutePlanner.stationChoices(listOf(lineOne, lineTwo))
        val origin = choices.single { it.stationName == "甲" }
        val destination = choices.single { it.stationName == "丁" }

        val result = OfflineRoutePlanner.plan(
            listOf(lineOne, lineTwo),
            origin.key,
            destination.key,
            OfflineRoutePlanner.Preference.FEWER_TRANSFERS
        ) as OfflineRoutePlanner.PlanResult.Found

        assertEquals(1, result.route.transferCount)
        assertEquals(3, result.route.stopCount)
        assertEquals(listOf("1号线", "2号线"), result.route.legs.map { it.lineName })
        assertEquals("换乘", result.route.legs.first().toStation)
    }

    @Test
    fun doesNotJoinSameStationNameAcrossCities() {
        val otherCity = line(
            id = "other",
            name = "外地线",
            cityId = "other",
            cityName = "外地",
            stations = listOf("z1" to "换乘", "z2" to "终点")
        )
        val choices = OfflineRoutePlanner.stationChoices(listOf(lineOne, otherCity))
        val origin = choices.single { it.cityName == "北京" && it.stationName == "甲" }
        val destination = choices.single { it.cityName == "外地" && it.stationName == "终点" }

        val result = OfflineRoutePlanner.plan(
            listOf(lineOne, otherCity),
            origin.key,
            destination.key,
            OfflineRoutePlanner.Preference.FEWER_STOPS
        )

        assertTrue(result is OfflineRoutePlanner.PlanResult.Rejected)
    }

    @Test
    fun rejectsSameOriginAndDestination() {
        val choice = OfflineRoutePlanner.stationChoices(listOf(lineOne)).first()

        val result = OfflineRoutePlanner.plan(
            listOf(lineOne),
            choice.key,
            choice.key,
            OfflineRoutePlanner.Preference.FEWER_TRANSFERS
        )

        assertTrue(result is OfflineRoutePlanner.PlanResult.Rejected)
    }

    private fun line(
        id: String,
        name: String,
        stations: List<Pair<String, String>>,
        cityId: String = "beijing",
        cityName: String = "北京"
    ): MetroLine = MetroLine(
        cityId = cityId,
        cityName = cityName,
        lineId = id,
        lineName = name,
        stations = stations.mapIndexed { index, (stationId, stationName) ->
            Station(stationId, stationName, 39.9 + index * .01, 116.3)
        },
        segments = stations.zipWithNext { from, to -> Segment(from.first, to.first, 120) }
    )
}