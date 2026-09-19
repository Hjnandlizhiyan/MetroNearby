package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.ServiceTypes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 官方来源与核对范围见 docs/prediction-accuracy.md。 */
class BeijingLine14ScheduleTest {
    private val line = MetroJson.parseLine(
        File("src/main/assets/metro/line_beijing_14.json").readText()
    )

    @Test
    fun `两个始发站的首班为官网公布的0455`() {
        for (pattern in line.patterns) {
            for (service in pattern.services) {
                val first = ArrivalEstimator(line).nextArrivals(
                    pattern.startStationId, service.serviceType, 0, limit = 1
                ).single()
                assertEquals(TimeUtils.parseToSecondsOfDay("04:55"), first.arrivalSecondsOfDay)
            }
        }
    }

    @Test
    fun `两个始发站的末班为官网公布的2242`() {
        for (pattern in line.patterns) {
            for (service in pattern.services) {
                val window = ArrivalEstimator(line).serviceWindow(pattern.startStationId, service.serviceType)!!
                assertEquals(TimeUtils.parseToSecondsOfDay("22:42"), window.lastSecondsOfDay)
            }
        }
    }

    @Test
    fun `修正始发边界后沿途估算偏移保持一致`() {
        val estimator = ArrivalEstimator(line)
        for (pattern in line.patterns) {
            for ((stationId, byPattern) in line.stationServiceTimes) {
                if (stationId == pattern.endStationId) continue
                val published = byPattern.getValue(pattern.id)
                val last = estimator.nextArrivalsByDirection(
                    stationId, ServiceTypes.WEEKDAY,
                    TimeUtils.parseToSecondsOfDay(published.lastDeparture!!) - 1
                ).first { it.directionId == pattern.directionId }.arrivals.last()
                assertEquals(TimeUtils.parseToSecondsOfDay(published.lastDeparture), last.arrivalSecondsOfDay)
            }
        }
    }

    @Test
    fun `只核对始发首末班不将整条线路标记为已核实`() {
        assertTrue(line.needsReview)
    }
}
