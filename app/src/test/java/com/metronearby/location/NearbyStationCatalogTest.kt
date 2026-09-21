package com.metronearby.location

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyStationCatalogTest {
    @Test
    fun mergesTransferLinesAndSortsByDistance() {
        val one = MetroLine(
            cityId = "beijing",
            cityName = "北京",
            lineId = "one",
            lineName = "1号线",
            stations = listOf(
                Station("a", "近站", 39.9000, 116.3000),
                Station("x1", "换乘站", 39.9100, 116.3000)
            )
        )
        val two = MetroLine(
            cityId = "beijing",
            cityName = "北京",
            lineId = "two",
            lineName = "2号线",
            stations = listOf(Station("x2", "换乘站", 39.9101, 116.3000))
        )

        val result = NearbyStationCatalog.find(listOf(one, two), 39.9001, 116.3000)

        assertEquals("近站", result.first().stationName)
        assertEquals(listOf("1号线", "2号线"), result.single { it.stationName == "换乘站" }.lineNames)
    }

    @Test
    fun respectsLimit() {
        val line = MetroLine(
            lineId = "one",
            lineName = "1号线",
            stations = (1..6).map { Station("$it", "站$it", 39.9 + it * .001, 116.3) }
        )

        val result = NearbyStationCatalog.find(listOf(line), 39.9, 116.3, limit = 3)

        assertEquals(3, result.size)
        assertTrue(result.zipWithNext().all { it.first.distanceMeters <= it.second.distanceMeters })
    }
}