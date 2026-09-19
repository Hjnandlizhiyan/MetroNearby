package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.ArrivalObservation
import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.UserOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserCalibrationTest {
    private val baseLine = MetroJson.parseLine(
        javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!
            .bufferedReader().use { it.readText() }
    )
    private val line = baseLine.copy(
        exactDepartures = mapOf(
            STATION to mapOf(PATTERN to listOf("08:00", "08:10", "18:00", "18:10"))
        )
    )

    private fun observation(
        time: String,
        serviceType: String? = ServiceTypes.WEEKDAY,
        band: ObservationTimeBand? = ObservationTimeBand.fromSeconds(TimeUtils.parseToSecondsOfDay(time)),
        epochMillis: Long? = null,
        recordedSeconds: Int? = null
    ) = ArrivalObservation(
        stationId = STATION,
        patternId = PATTERN,
        observedTime = time,
        serviceType = serviceType,
        timeBand = band?.storageKey,
        recordedAtEpochMillis = epochMillis,
        recordedSecondsOfDay = recordedSeconds
    )

    private fun calibration(
        observations: List<ArrivalObservation>,
        query: String,
        serviceType: String = ServiceTypes.WEEKDAY,
        epochMillis: Long? = null
    ): UserObservationAverage? {
        val result = ArrivalEstimator(line, UserOverrides(lineId = line.lineId, arrivalObservations = observations))
            .nextArrivalsByDirection(
                stationId = STATION,
                serviceType = serviceType,
                nowSecondsOfDay = TimeUtils.parseToSecondsOfDay(query),
                perDirectionLimit = 1,
                currentEpochMillis = epochMillis
            )
        return result.first { it.directionId == "east" }.arrivals.first().userAverage
    }

    @Test fun `七点前属于平峰`() =
        assertEquals(ObservationTimeBand.OFF_PEAK, ObservationTimeBand.fromSeconds(seconds("06:59")))

    @Test fun `七点起属于早高峰`() =
        assertEquals(ObservationTimeBand.MORNING_PEAK, ObservationTimeBand.fromSeconds(seconds("07:00")))

    @Test fun `十点起回到平峰`() =
        assertEquals(ObservationTimeBand.OFF_PEAK, ObservationTimeBand.fromSeconds(seconds("10:00")))

    @Test fun `十七点起属于晚高峰`() =
        assertEquals(ObservationTimeBand.EVENING_PEAK, ObservationTimeBand.fromSeconds(seconds("17:00")))

    @Test fun `二十点起回到平峰`() =
        assertEquals(ObservationTimeBand.OFF_PEAK, ObservationTimeBand.fromSeconds(seconds("20:00")))

    @Test fun `早高峰只使用早高峰样本`() {
        val result = calibration(
            listOf(observation("08:01"), observation("08:12"), observation("18:03"), observation("18:14")),
            "07:59"
        )!!
        assertEquals(UserCalibrationScope.TIME_BAND, result.scope)
        assertEquals(90, result.averageOffsetSeconds)
        assertEquals(2, result.sampleCount)
        assertEquals(ObservationTimeBand.MORNING_PEAK, result.timeBand)
    }

    @Test fun `晚高峰只使用晚高峰样本`() {
        val result = calibration(
            listOf(observation("08:01"), observation("08:12"), observation("18:03"), observation("18:14")),
            "17:59"
        )!!
        assertEquals(UserCalibrationScope.TIME_BAND, result.scope)
        assertEquals(210, result.averageOffsetSeconds)
    }

    @Test fun `工作日和周末样本互不混用`() {
        val samples = listOf(
            observation("08:01"), observation("08:12"),
            observation("08:03", ServiceTypes.WEEKEND), observation("08:14", ServiceTypes.WEEKEND)
        )
        assertEquals(90, calibration(samples, "07:59", ServiceTypes.WEEKDAY)!!.averageOffsetSeconds)
        assertEquals(210, calibration(samples, "07:59", ServiceTypes.WEEKEND)!!.averageOffsetSeconds)
    }

    @Test fun `不同运营日样本按各自班次匹配偏差`() {
        val result = ArrivalEstimator(
            baseLine,
            UserOverrides(
                lineId = baseLine.lineId,
                arrivalObservations = listOf(
                    observation("06:09", serviceType = ServiceTypes.WEEKEND, band = ObservationTimeBand.OFF_PEAK),
                    observation("06:19", serviceType = ServiceTypes.WEEKEND, band = ObservationTimeBand.OFF_PEAK)
                )
            )
        ).nextArrivalsByDirection("s1", ServiceTypes.WEEKEND, seconds("06:00"))
            .first { it.directionId == "east" }.arrivals.first().userAverage!!

        assertEquals(-60, result.averageOffsetSeconds)
        assertEquals(UserCalibrationScope.TIME_BAND, result.scope)
    }

    @Test fun `时段样本不足时回退到同运营日平均`() {
        val result = calibration(
            listOf(observation("08:01"), observation("18:03")),
            "07:59"
        )!!
        assertEquals(UserCalibrationScope.SERVICE_TYPE, result.scope)
        assertEquals(120, result.averageOffsetSeconds)
    }

    @Test fun `运营日样本不足时兼容旧版全天样本`() {
        val result = calibration(
            listOf(observation("08:01"), observation("08:12", serviceType = null, band = null)),
            "07:59"
        )!!
        assertEquals(UserCalibrationScope.ALL_DAY, result.scope)
        assertEquals(90, result.averageOffsetSeconds)
    }

    @Test fun `现场记录在两小时内成为后续班次锚点`() {
        val epoch = 1_000_000L
        val result = calibration(
            listOf(observation("08:01", epochMillis = epoch, recordedSeconds = seconds("08:01"))),
            "08:02",
            epochMillis = epoch + 60_000
        )!!
        assertEquals(UserCalibrationScope.RECENT_ANCHOR, result.scope)
        assertEquals(60, result.averageOffsetSeconds)
        assertEquals(1, result.sampleCount)
    }

    @Test fun `最新现场锚点优先于旧锚点`() {
        val result = calibration(
            listOf(
                observation("08:01", epochMillis = 1_000_000, recordedSeconds = seconds("08:01")),
                observation("08:12", epochMillis = 2_000_000, recordedSeconds = seconds("08:12"))
            ),
            "08:02",
            epochMillis = 2_060_000
        )!!
        assertEquals(UserCalibrationScope.RECENT_ANCHOR, result.scope)
        assertEquals(120, result.averageOffsetSeconds)
    }

    @Test fun `超过两小时的记录不再作为锚点`() {
        val epoch = 1_000_000L
        val result = calibration(
            listOf(observation("08:01", epochMillis = epoch, recordedSeconds = seconds("08:01"))),
            "08:02",
            epochMillis = epoch + ArrivalEstimator.ANCHOR_VALID_MILLIS + 1
        )!!
        assertEquals(UserCalibrationScope.ALL_DAY, result.scope)
    }

    @Test fun `补录旧时刻不作为现场锚点`() {
        val epoch = 1_000_000L
        val result = calibration(
            listOf(observation("08:01", epochMillis = epoch, recordedSeconds = seconds("10:00"))),
            "08:02",
            epochMillis = epoch + 60_000
        )!!
        assertEquals(UserCalibrationScope.ALL_DAY, result.scope)
    }

    @Test fun `未来时间戳不作为锚点`() {
        val epoch = 1_000_000L
        val result = calibration(
            listOf(observation("08:01", epochMillis = epoch + 60_000, recordedSeconds = seconds("08:01"))),
            "08:02",
            epochMillis = epoch
        )!!
        assertEquals(UserCalibrationScope.ALL_DAY, result.scope)
    }

    @Test fun `锚点不反向校准它之前的班次`() {
        val epoch = 1_000_000L
        val result = calibration(
            listOf(observation("18:03", epochMillis = epoch, recordedSeconds = seconds("18:03"))),
            "07:59",
            epochMillis = epoch + 60_000
        )!!
        assertEquals(UserCalibrationScope.ALL_DAY, result.scope)
    }

    @Test fun `无有效样本时没有用户校准`() {
        assertNull(calibration(listOf(observation("09:00")), "07:59"))
    }

    private fun seconds(value: String) = TimeUtils.parseToSecondsOfDay(value)

    companion object {
        private const val STATION = "s1"
        private const val PATTERN = "east_full"
    }
}
