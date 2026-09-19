package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.ArrivalObservation
import com.metronearby.data.model.ArrivalPredictionSnapshot
import com.metronearby.data.model.UserOverrides
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CalibrationLearningTest {
    private val now = 1_780_000_000_000L
    private val base = 8 * 3600
    private val day = CalibrationLearning.DAY_MILLIS

    private fun sample(offset: Int = 120, daysAgo: Int = 1, baseTime: Int = base,
                       calibrated: Int = baseTime + 90, lead: Long = 60_000,
                       confirmed: Boolean = true): ArrivalObservation {
        val actual = now - daysAgo * day
        return ArrivalObservation("s1", "east_full", TimeUtils.formatSecondsOfDay(baseTime + offset),
            serviceType = "weekday", recordedAtEpochMillis = actual,
            recordedSecondsOfDay = baseTime + offset, observedSecondsOfDay = baseTime + offset,
            observedEpochMillis = actual, observationDate = "day-$daysAgo",
            prediction = ArrivalPredictionSnapshot(baseTime, calibrated, actual - lead,
                baseTime + offset - (lead / 1000).toInt(), "day-$daysAgo", "weekday"),
            matchConfirmed = confirmed, liveArrival = true)
    }

    private fun estimate(samples: List<ArrivalObservation>, at: Long = now) =
        CalibrationLearning.estimate(samples, listOf(base, base + 240, base + 600), "weekday", base + 600, at)

    @Test fun `保存明确班次避免三分钟延误被误配为下一班提前`() {
        val result = estimate(listOf(sample(offset = 180)))!!
        assertTrue(result.averageOffsetSeconds > 0)
    }

    @Test fun `重复同班不会增加样本数量`() {
        val o = sample()
        assertEquals(1, CalibrationLearning.append(listOf(o), o.copy(observedSecondsOfDay = base + 130)).size)
    }

    @Test fun `不同站点相同班次不被去重`() {
        val o = sample()
        assertEquals(2, CalibrationLearning.append(listOf(o), o.copy(stationId = "s2")).size)
    }

    @Test fun `跨日期相同班次保留两条`() {
        assertEquals(2, CalibrationLearning.append(listOf(sample()), sample(daysAgo = 2)).size)
    }

    @Test fun `未确认记录影响低于确认记录`() {
        assertTrue(estimate(listOf(sample(confirmed = false)))!!.averageOffsetSeconds <
            estimate(listOf(sample()))!!.averageOffsetSeconds)
    }

    @Test fun `一致历史增加后修正更接近已验证偏差`() {
        val one = estimate(listOf(sample()))!!.averageOffsetSeconds
        val many = estimate((1..8).map { sample(daysAgo = it) })!!.averageOffsetSeconds
        assertTrue(absError(many, 120) < absError(one, 120))
    }

    @Test fun `孤立极端记录不会推翻一致历史`() {
        val records = (1..8).map { sample(daysAgo = it) } + sample(offset = -500, daysAgo = 9)
        assertTrue(estimate(records)!!.averageOffsetSeconds > 60)
    }

    @Test fun `旧记录权重低于近期记录`() {
        assertTrue(estimate(listOf(sample(daysAgo = 1)))!!.averageOffsetSeconds >
            estimate(listOf(sample(daysAgo = 50)))!!.averageOffsetSeconds)
    }

    @Test fun `锚点与历史共同参与而非完全替代`() {
        val history = (1..8).map { sample(daysAgo = it, offset = 60) }
        val anchor = sample(offset = 240, daysAgo = 0)
        val correction = estimate(history + anchor)!!.averageOffsetSeconds
        assertTrue(correction in 61..239)
    }

    @Test fun `锚点影响随时间衰减`() {
        val records = (1..8).map { sample(daysAgo = it, offset = 60) } + sample(offset = 240, daysAgo = 0)
        assertTrue(estimate(records)!!.averageOffsetSeconds > estimate(records, now + 3_600_000)!!.averageOffsetSeconds)
    }

    @Test fun `超过十五分钟的手动补录不能成为近期锚点`() {
        assertEquals(UserCalibrationScope.LEARNED_HISTORY,
            estimate(listOf(sample(daysAgo = 0).copy(
                liveArrival = false,
                recordedAtEpochMillis = now,
                observedEpochMillis = now - 901_000
            )))!!.scope)
    }

    @Test fun `确认同班的近时手动记录可以成为近期锚点但不计准确度`() {
        val observation = sample(offset = 240, daysAgo = 0).copy(
            liveArrival = false,
            recordedAtEpochMillis = now,
            observedEpochMillis = now - 60_000
        )
        val result = CalibrationLearning.estimate(
            listOf(observation), listOf(base, base + 600), "weekday", base, now
        )!!
        assertEquals(UserCalibrationScope.BLENDED_ANCHOR, result.scope)
        assertEquals(240, result.averageOffsetSeconds)
        assertNull(CalibrationLearning.accuracy(listOf(observation), now))
    }

    @Test fun `未来记录不会参与学习`() {
        assertNull(estimate(listOf(sample(daysAgo = -1))))
    }

    @Test fun `其他运营日不会参与学习`() {
        assertNull(estimate(listOf(sample().copy(serviceType = "weekend"))))
    }

    @Test fun `超过九十天的记录不参与学习`() {
        assertNull(estimate(listOf(sample(daysAgo = 91))))
    }

    @Test fun `提前预测误差按冻结值比较`() {
        val stats = CalibrationLearning.accuracy(listOf(sample()), now)!!
        assertEquals(120, stats.systemMae)
        assertEquals(30, stats.userMae)
    }

    @Test fun `事后预测不得计入准确度`() {
        assertNull(CalibrationLearning.accuracy(listOf(sample(lead = -1000)), now))
    }

    @Test fun `未提前三十秒的预测不计入准确度`() {
        assertNull(CalibrationLearning.accuracy(listOf(sample(lead = 29_000)), now))
    }

    @Test fun `未确认班次不计入准确度`() {
        assertNull(CalibrationLearning.accuracy(listOf(sample(confirmed = false)), now))
    }

    @Test fun `手动补录不计入准确度`() {
        assertNull(CalibrationLearning.accuracy(listOf(sample().copy(liveArrival = false)), now))
    }

    @Test fun `没有旧校准预测不得伪造对比`() {
        val o = sample()
        assertNull(CalibrationLearning.accuracy(listOf(o.copy(prediction = o.prediction!!.copy(calibratedSeconds = null))), now))
    }

    @Test fun `至少五条跨天改善记录才提高历史信任`() {
        assertTrue(CalibrationLearning.accuracy((1..5).map { sample(daysAgo = it) }, now)!!.supportsLearning)
        assertFalse(CalibrationLearning.accuracy((1..4).map { sample(daysAgo = it) }, now)!!.supportsLearning)
    }

    @Test fun `验证变差时历史修正权重下降`() {
        val better = estimate((1..6).map { sample(daysAgo = it) })!!.averageOffsetSeconds
        val worse = estimate((1..6).map { sample(daysAgo = it, calibrated = base + 400) })!!.averageOffsetSeconds
        assertTrue(worse < better)
    }

    @Test fun `秒级现场记录包含当天日期`() {
        val clock = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
            set(2026, Calendar.SEPTEMBER, 18, 8, 1, 37)
        }
        val item = ArrivalItem("s1", "east_full", "全程", "east", false, "s2", "乙", base, 0, ArrivalSource.ESTIMATED, 0)
        val o = CalibrationLearning.record(item, CalibrationLearning.snapshot(item, clock), clock, null, true)
        assertEquals("08:01:37", o.observedTime)
        assertEquals(base + 97, o.observedSecondsOfDay)
        assertEquals("2026-09-18", o.observationDate)
    }

    @Test fun `新字段可完整落盘还原`() {
        val overrides = UserOverrides(lineId = "test", arrivalObservations = listOf(sample()))
        assertEquals(overrides, MetroJson.parseOverrides(MetroJson.encodeOverrides(overrides)))
    }

    @Test fun `真实推算入口使用新学习器`() {
        val line = MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!.bufferedReader().use { it.readText() })
            .copy(exactDepartures = mapOf("s1" to mapOf("east_full" to listOf("08:00", "08:04", "08:10"))))
        val result = ArrivalEstimator(line, UserOverrides(lineId = line.lineId, arrivalObservations = listOf(sample(offset = 180))))
            .nextArrivalsByDirection("s1", "weekday", base - 60, currentEpochMillis = now)
            .first { it.directionId == "east" }.arrivals.first().userAverage!!
        assertEquals(UserCalibrationScope.LEARNED_HISTORY, result.scope)
        assertTrue(result.averageOffsetSeconds > 0)
    }

    private fun absError(value: Int, target: Int) = kotlin.math.abs(value - target)

    @Test fun `重复现场点击保留首次到站时间避免虚假验证`() {
        val o = sample()
        val repeated = o.copy(observedEpochMillis = now, observedSecondsOfDay = base + 240)
        assertEquals(o, CalibrationLearning.append(listOf(o), repeated).single())
    }

    @Test fun `时刻表移除了原班次时停止套用其旧偏差`() {
        assertNull(CalibrationLearning.estimate(listOf(sample()), listOf(base + 60), "weekday", base + 60, now))
    }

    private fun timetable(): com.metronearby.data.model.MetroLine =
        MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!.bufferedReader().use { it.readText() })
            .copy(exactDepartures = mapOf("s1" to mapOf("east_full" to listOf("08:00", "08:10"))))

    @Test fun `用户模式延误班次不会按系统时间提前消失`() {
        val line = timetable()
        val legacy = ArrivalObservation("s1", "east_full", "08:02")
        val result = ArrivalEstimator(line, UserOverrides(lineId = line.lineId, arrivalObservations = listOf(legacy)))
            .nextArrivalsByDirection("s1", "weekday", base + 60, displayMode = ArrivalDisplayMode.USER_CALIBRATED)
            .first { it.directionId == "east" }.arrivals.first()
        assertEquals(base, result.arrivalSecondsOfDay)
    }

    @Test fun `晚到的同班锚点按实际到站时刻保留当前班次`() {
        val line = timetable()
        val result = ArrivalEstimator(line, UserOverrides(
            lineId = line.lineId,
            arrivalObservations = listOf(sample(offset = 240, daysAgo = 0))
        )).nextArrivalsByDirection(
            "s1", "weekday", base + 240,
            currentEpochMillis = now,
            displayMode = ArrivalDisplayMode.USER_CALIBRATED
        ).first { it.directionId == "east" }.arrivals.first()
        assertEquals(base, result.arrivalSecondsOfDay)
        assertEquals(base + 240, result.userArrivalSecondsOfDay)
        assertEquals(0, result.present(ArrivalDisplayMode.USER_CALIBRATED, base + 240).waitSeconds)
    }

    @Test fun `用户模式提前离站班次被移除`() {
        val line = timetable()
        val legacy = ArrivalObservation("s1", "east_full", "07:58")
        val result = ArrivalEstimator(line, UserOverrides(lineId = line.lineId, arrivalObservations = listOf(legacy)))
            .nextArrivalsByDirection("s1", "weekday", base - 30, displayMode = ArrivalDisplayMode.USER_CALIBRATED)
            .first { it.directionId == "east" }.arrivals.first()
        assertEquals(base + 600, result.arrivalSecondsOfDay)
    }
}
