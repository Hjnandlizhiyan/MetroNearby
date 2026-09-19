package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class HeadwayLearningTest {
    private fun clock(seconds: Int): Calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai")).apply {
        set(2026, Calendar.SEPTEMBER, 18, seconds / 3600, seconds % 3600 / 60, seconds % 60)
        set(Calendar.MILLISECOND, 0)
    }
    private val base = 8 * 3600
    private val now = clock(base + 600).timeInMillis
    private val times = (0..12).map { base + it * 300 }
    private val item = ArrivalItem("s1", "east_full", "全程", "east", false, "s2", "乙", base, 0, ArrivalSource.ESTIMATED, 0)
    private fun session(gaps: List<Int> = listOf(240, 240), daysAgo: Int = 0): HeadwaySession {
        var current = base
        val points = (listOf(0) + gaps).map { gap ->
            current += gap
            HeadwayPoint(current, clock(current).timeInMillis - daysAgo * CalibrationLearning.DAY_MILLIS)
        }
        return HeadwaySession("s-$daysAgo-${gaps.joinToString()}", "s1", "east_full", "weekday", "date-$daysAgo", base, points)
    }
    private fun learned(s: List<HeadwaySession>) = HeadwayLearning.learn(s, "weekday", base + 600, now)

    @Test fun `两班只有一个间隔时不启用学习`() { assertNull(learned(listOf(session(listOf(240))))) }
    @Test fun `三班连续记录可学习两个间隔`() { assertEquals(240, learned(listOf(session()))!!.seconds) }
    @Test fun `重复同组不会翻倍统计`() { val s = session(); assertEquals(2, learned(listOf(s, s))!!.intervals) }
    @Test fun `不同组之间不构造间隔`() {
        assertNull(learned(listOf(session(emptyList()), session(emptyList(), 1))))
    }
    @Test fun `日型不同不混用`() { assertNull(learned(listOf(session().copy(serviceType = "weekend")))) }
    @Test fun `未来记录不参与学习`() { assertNull(learned(listOf(session(daysAgo = -1)))) }
    @Test fun `过期历史不参与学习`() { assertNull(learned(listOf(session(daysAgo = 91)))) }
    @Test fun `同组三个以上间隔可剔除孤立异常值`() {
        val s = session(listOf(240, 240, 1200), 1)
        assertEquals(240, learned(listOf(s))!!.seconds)
    }
    @Test fun `两个极不一致的间隔暂不学习`() {
        assertNull(learned(listOf(session(listOf(120, 900), 1))))
    }
    @Test fun `没确认无漏车不能追加`() {
        val s = HeadwayLearning.start(item, clock(base))
        assertNotNull(HeadwayLearning.appendError(s, clock(base + 240), false))
    }
    @Test fun `重复点击不产生一分钟以内的间隔`() {
        val s = HeadwayLearning.start(item, clock(base))
        assertNotNull(HeadwayLearning.appendError(s, clock(base + 1), true))
    }
    @Test fun `结束组不能继续追加`() {
        val s = HeadwayLearning.start(item, clock(base)).copy(ended = true)
        assertNotNull(HeadwayLearning.appendError(s, clock(base + 240), true))
    }
    @Test fun `超半小时需结束重开`() {
        val s = HeadwayLearning.start(item, clock(base))
        assertNotNull(HeadwayLearning.appendError(s, clock(base + 1801), true))
    }
    @Test fun `跨日期不能连接`() {
        val s = HeadwayLearning.start(item, clock(base))
        val tomorrow = clock(base + 240).apply { add(Calendar.DAY_OF_MONTH, 1) }
        assertNotNull(HeadwayLearning.appendError(s, tomorrow, true))
    }
    @Test fun `确认连续车后追加秒级现场记录`() {
        val s = HeadwayLearning.start(item, clock(base))
        assertEquals(base + 241, HeadwayLearning.append(s, clock(base + 241), true).points.last().secondsOfDay)
    }
    @Test fun `近期连续记录改变后续间隔而不只是整体平移`() {
        val s = session()
        fun arrival(target: Int) = target + HeadwayLearning.adjust(null, listOf(s), emptyList(), times,
            "weekday", target, now)!!.averageOffsetSeconds
        assertEquals(240, arrival(base + 1200) - arrival(base + 900))
    }
    @Test fun `历史间隔没有近期锚点时不伪造相位`() {
        assertNull(HeadwayLearning.adjust(null, listOf(session(daysAgo = 2)), emptyList(), times, "weekday", base + 900, now))
    }
    @Test fun `历史间隔可结合今天普通到站记录`() {
        val o = CalibrationLearning.record(item, CalibrationLearning.snapshot(item, clock(base - 60)), clock(base), null, true)
        val result = HeadwayLearning.adjust(null, listOf(session(daysAgo = 2)), listOf(o), times, "weekday", base + 300, now)
        assertEquals(-60, result!!.averageOffsetSeconds)
    }
    @Test fun `已观测班次使用真实时刻供列表移除`() {
        assertEquals(0, HeadwayLearning.adjust(null, listOf(session()), emptyList(), times, "weekday", base, now)!!.averageOffsetSeconds)
    }
    @Test fun `旧文件没有间隔组时解析兼容`() {
        assertTrue(MetroJson.parseOverrides("{\"lineId\":\"test\"}").headwaySessions.isEmpty())
    }
    @Test fun `完整连续记录可以落盘往返`() {
        val o = UserOverrides(lineId = "test", headwaySessions = listOf(session()))
        assertEquals(o, MetroJson.parseOverrides(MetroJson.encodeOverrides(o)))
    }
    @Test fun `删除学习组后恢复原校准`() {
        val original = UserObservationAverage(30, 3)
        assertEquals(original, HeadwayLearning.adjust(original, emptyList(), emptyList(), times, "weekday", base + 900, now))
    }
    @Test fun `推算入口真正使用连续间隔`() {
        val line = MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!.bufferedReader().use { it.readText() })
            .copy(exactDepartures = mapOf("s1" to mapOf("east_full" to times.map { TimeUtils.formatSecondsOfDay(it) })))
        val result = ArrivalEstimator(line, UserOverrides(lineId = line.lineId, headwaySessions = listOf(session())))
            .nextArrivalsByDirection("s1", "weekday", base + 600, currentEpochMillis = now, displayMode = ArrivalDisplayMode.USER_CALIBRATED)
            .first { it.directionId == "east" }.arrivals
        assertEquals(UserCalibrationScope.LEARNED_HEADWAY, result.first().userAverage!!.scope)
        assertEquals(240, result[1].userArrivalSecondsOfDay!! - result[0].userArrivalSecondsOfDay!!)
    }

    @Test fun `第三班后提前冻结第四班预测`() {
        val s = HeadwayLearning.freezeNext(session(), emptyList())
        assertEquals(clock(base + 720).timeInMillis, s.nextExpectedEpochMillis)
    }
    @Test fun `到站时沿用先前冻结预测不重新拟合`() {
        val raw = HeadwayLearning.start(item, clock(base))
        val second = HeadwayLearning.append(raw, clock(base + 240), true)
        val third = HeadwayLearning.freezeNext(HeadwayLearning.append(second, clock(base + 480), true), emptyList())
        val fourth = HeadwayLearning.append(third, clock(base + 750), true)
        assertEquals(clock(base + 720).timeInMillis, fourth.points.last().predictedEpochMillis)
        assertTrue(HeadwayLearning.forecastAccuracy(listOf(fourth), clock(base + 751).timeInMillis).contains("30 秒"))
    }
    @Test fun `没有预存预测不能算验证成绩`() {
        assertTrue(HeadwayLearning.forecastAccuracy(listOf(session()), now).startsWith("尚无"))
    }
    @Test fun `间隔差异较大仍保持连续班次顺序`() {
        val s = session(listOf(480, 480))
        val at = clock(base + 1000).timeInMillis
        val a = base + 900 + HeadwayLearning.adjust(null, listOf(s), emptyList(), times, "weekday", base + 900, at)!!.averageOffsetSeconds
        val b = base + 1200 + HeadwayLearning.adjust(null, listOf(s), emptyList(), times, "weekday", base + 1200, at)!!.averageOffsetSeconds
        assertEquals(480, b - a)
    }
    @Test fun `跨高峰边界的观测间隔不参与学习`() {
        val points = listOf(9 * 3600 + 55 * 60, 10 * 3600, 10 * 3600 + 5 * 60).map {
            HeadwayPoint(it, clock(it).timeInMillis)
        }
        val s = session().copy(points = points)
        assertNull(HeadwayLearning.learn(listOf(s), "weekday", 10 * 3600 + 600, clock(10 * 3600 + 600).timeInMillis))
    }
    @Test fun `下一班跨高峰边界时不冻结旧时段间隔`() {
        val points = listOf(9 * 3600 + 50 * 60, 9 * 3600 + 54 * 60, 9 * 3600 + 58 * 60).map {
            HeadwayPoint(it, clock(it).timeInMillis)
        }
        assertNull(HeadwayLearning.freezeNext(session().copy(points = points), emptyList()).nextExpectedEpochMillis)
    }
    @Test fun `系统时间跳变时拒绝连接记录`() {
        val s = HeadwayLearning.start(item, clock(base)).let { original ->
            original.copy(points = original.points.map { it.copy(secondsOfDay = it.secondsOfDay - 60) })
        }
        assertNotNull(HeadwayLearning.appendError(s, clock(base + 240), true))
    }
    @Test fun `间隔累计差超过十分钟仍正确推进未来班次`() {
        val sequence = (0..10).map { base + 300 + it * 420 }
        val s = session(listOf(140, 140)).copy(referenceBaseSeconds = sequence.first())
        val line = MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!.bufferedReader().use { it.readText() })
            .copy(exactDepartures = mapOf("s1" to mapOf("east_full" to sequence.map { TimeUtils.formatSecondsOfDay(it) })))
        val result = ArrivalEstimator(line, UserOverrides(lineId = line.lineId, headwaySessions = listOf(s)))
            .nextArrivalsByDirection("s1", "weekday", base + 360, currentEpochMillis = clock(base + 360).timeInMillis,
                displayMode = ArrivalDisplayMode.USER_CALIBRATED).first { it.directionId == "east" }.arrivals
        assertEquals(base + 420, result.first().userArrivalSecondsOfDay)
        assertEquals(140, result[1].userArrivalSecondsOfDay!! - result[0].userArrivalSecondsOfDay!!)
    }
}
