package com.metronearby.location

import com.metronearby.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearestStationFinderTest {

    private val stations = listOf(
        Station(id = "s1", name = "甲站", lat = 39.9057386, lng = 116.3682035),
        Station(id = "s2", name = "乙站", lat = 39.9059702, lng = 116.3851622),
        Station(id = "s3", name = "丙站", lat = 39.9063337, lng = 116.3949169)
    )

    @Test
    fun `定位点靠近某站时该站排第一`() {
        val finder = NearestStationFinder(stations)
        val result = finder.findNearest(39.9057386, 116.3682035)
        assertEquals("s1", result.first().station.id)
        assertEquals(0.0, result.first().distanceMeters, 0.001)
    }

    @Test
    fun `按距离升序返回并受 limit 限制`() {
        val finder = NearestStationFinder(stations)
        val result = finder.findNearest(39.9059, 116.3800, limit = 2)
        assertEquals(2, result.size)
        assertTrue(result[0].distanceMeters <= result[1].distanceMeters)
    }

    @Test
    fun `超出最大距离的站点被过滤`() {
        val finder = NearestStationFinder(stations)
        val result = finder.findNearest(31.2304, 121.4737, maxDistanceMeters = 5_000.0)
        assertTrue(result.isEmpty())
    }
}