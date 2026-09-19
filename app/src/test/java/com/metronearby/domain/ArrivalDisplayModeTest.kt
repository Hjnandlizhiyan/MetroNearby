package com.metronearby.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalDisplayModeTest {
    private val base = ArrivalItem(
        stationId = "s1",
        patternId = "p1",
        patternName = "全程车",
        directionId = "forward",
        isShortTurn = false,
        terminalStationId = "s2",
        terminalStationName = "乙站",
        arrivalSecondsOfDay = 8 * 3600,
        waitSeconds = 300,
        source = ArrivalSource.ESTIMATED,
        offsetSeconds = 0
    )

    @Test fun `缺省使用系统预计`() {
        assertEquals(ArrivalDisplayMode.SYSTEM_ESTIMATE, ArrivalDisplayMode.DEFAULT)
    }

    @Test fun `持久化键可还原用户校准`() {
        assertEquals(ArrivalDisplayMode.USER_CALIBRATED, ArrivalDisplayMode.fromStorageKey(" user_calibrated "))
    }

    @Test fun `未知持久化键回退系统预计`() {
        assertEquals(ArrivalDisplayMode.SYSTEM_ESTIMATE, ArrivalDisplayMode.fromStorageKey("unknown"))
    }

    @Test fun `系统口径忽略已有用户偏差`() {
        val item = base.copy(userAverage = UserObservationAverage(120, 2))
        val result = item.present(ArrivalDisplayMode.SYSTEM_ESTIMATE, 7 * 3600 + 55 * 60)
        assertEquals(8 * 3600, result.arrivalSecondsOfDay)
        assertFalse(result.usesUserCalibration)
        assertFalse(result.fellBackToSystem)
    }

    @Test fun `用户口径采用锚点修正后的时间`() {
        val item = base.copy(
            userAverage = UserObservationAverage(120, 1, UserCalibrationScope.RECENT_ANCHOR)
        )
        val result = item.present(ArrivalDisplayMode.USER_CALIBRATED, 7 * 3600 + 55 * 60)
        assertEquals(8 * 3600 + 120, result.arrivalSecondsOfDay)
        assertEquals(7 * 60, result.waitSeconds)
        assertEquals("用户近期锚点", result.sourceLabel)
        assertTrue(result.usesUserCalibration)
    }

    @Test fun `用户口径无样本时回退系统预计`() {
        val result = base.present(ArrivalDisplayMode.USER_CALIBRATED, 7 * 3600 + 55 * 60)
        assertEquals(8 * 3600, result.arrivalSecondsOfDay)
        assertFalse(result.usesUserCalibration)
        assertTrue(result.fellBackToSystem)
    }

    @Test fun `用户负偏差会缩短主倒计时`() {
        val item = base.copy(userAverage = UserObservationAverage(-60, 2))
        val result = item.present(ArrivalDisplayMode.USER_CALIBRATED, 7 * 3600 + 55 * 60)
        assertEquals(4 * 60, result.waitSeconds)
    }
}
