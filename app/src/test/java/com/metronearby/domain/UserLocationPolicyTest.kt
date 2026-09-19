package com.metronearby.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserLocationPolicyTest {
    private val now = 1_000_000L

    @Test
    fun rejectsExpiredCachedLocation() {
        val location = UserLocation(39.9, 116.4, 20f, now - UserLocationPolicy.MAX_CACHE_AGE_MILLIS - 1)

        assertFalse(UserLocationPolicy.isUsable(location, now))
    }

    @Test
    fun acceptsFreshAccurateLocation() {
        val location = UserLocation(39.9, 116.4, 20f, now - 30_000L)

        assertTrue(UserLocationPolicy.isUsable(location, now))
    }

    @Test
    fun rejectsLocationWithUnusableAccuracy() {
        val location = UserLocation(39.9, 116.4, 8_000f, now)

        assertFalse(UserLocationPolicy.isUsable(location, now))
    }

    @Test
    fun selectsMoreAccurateFreshLocation() {
        val network = UserLocation(39.9, 116.4, 300f, now, "network")
        val gps = UserLocation(39.91, 116.41, 12f, now - 1_000L, "gps")

        assertEquals(gps, UserLocationPolicy.best(listOf(network, gps), now))
    }

    @Test
    fun returnsNullWhenEveryLocationIsExpired() {
        val expired = UserLocation(39.9, 116.4, 20f, now - 300_000L)

        assertNull(UserLocationPolicy.best(listOf(expired), now))
    }

    @Test
    fun formatsCoordinatesToFiveDecimalPlaces() {
        val location = UserLocation(39.907501, 116.190249, capturedAtMillis = now)

        assertEquals("39.90750, 116.19025", UserLocationPolicy.coordinatesText(location))
    }

    @Test
    fun detailShowsAccuracyAgeAndMockSource() {
        val location = UserLocation(39.9, 116.4, 18.6f, now - 70_000L, isMock = true)

        assertEquals("精度约 19 米 · 1 分钟前更新 · 模拟位置", UserLocationPolicy.detailText(location, now))
    }
}
