package com.metronearby.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoUtilsTest {

    @Test
    fun `同一点距离为零`() {
        val distance = GeoUtils.haversineMeters(39.9057386, 116.3682035, 39.9057386, 116.3682035)
        assertEquals(0.0, distance, 0.001)
    }

    @Test
    fun `西单到天安门西约 1_45 公里`() {
        val distance = GeoUtils.haversineMeters(39.9057386, 116.3682035, 39.9059702, 116.3851622)
        assertEquals(1448.0, distance, 20.0)
    }

    @Test
    fun `天安门西到天安门东约 834 米`() {
        val distance = GeoUtils.haversineMeters(39.9059702, 116.3851622, 39.9063337, 116.3949169)
        assertEquals(834.0, distance, 20.0)
    }

    @Test
    fun `距离与方向无关`() {
        val forward = GeoUtils.haversineMeters(39.9057386, 116.3682035, 39.9059702, 116.3851622)
        val backward = GeoUtils.haversineMeters(39.9059702, 116.3851622, 39.9057386, 116.3682035)
        assertEquals(forward, backward, 0.0001)
    }
}