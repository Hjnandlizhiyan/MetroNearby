package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.ShortTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortTurnPlannerTest {

    private val line: MetroLine = loadTestLine()

    private fun loadTestLine(): MetroLine {
        val stream = javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")
            ?: error("缺少测试数据 metro/line_test.json")
        return MetroJson.parseLine(stream.bufferedReader().use { it.readText() })
    }

    private fun planned(shortTurn: ShortTurn): Pattern =
        (ShortTurnPlanner.plan(line, shortTurn) as ShortTurnPlanner.Plan.Planned).pattern

    private fun rejected(shortTurn: ShortTurn): String =
        (ShortTurnPlanner.plan(line, shortTurn) as ShortTurnPlanner.Plan.Rejected).reason

    /** 只保留东行全程车 + 折算出的区间车，避免其它交路干扰断言 */
    private fun lineWith(vararg shortTurns: ShortTurn): MetroLine = line.copy(
        patterns = line.patterns.filter { it.id == "east_full" } +
            ShortTurnPlanner.patterns(line, shortTurns.toList())
    )

    // ------------------------------------------------------------------
    // 折算：交路属性与方向
    // ------------------------------------------------------------------

    @Test
    fun `区间车折算成同方向的区间交路`() {
        val pattern = planned(ShortTurn("s1", "s3", listOf("08:00", "08:10")))

        assertTrue(pattern.isShortTurn)
        assertEquals("east", pattern.directionId)
        assertEquals("s1", pattern.startStationId)
        assertEquals("s3", pattern.endStationId)
        assertEquals(ShortTurnPlanner.PATTERN_NAME, pattern.name)
    }

    @Test
    fun `反向区间车的方向自动推导`() {
        val pattern = planned(ShortTurn("s4", "s2", listOf("08:00")))

        assertEquals("west", pattern.directionId)
    }

    @Test
    fun `方向标签留空交由推算统一推导`() {
        val pattern = planned(ShortTurn("s1", "s3", listOf("08:00")))
        assertEquals(null, pattern.directionLabel)
    }

    // ------------------------------------------------------------------
    // 逐班时刻 -> 首末班 + 间隔
    // ------------------------------------------------------------------

    @Test
    fun `逐班时刻折算成分段间隔`() {
        val pattern = planned(ShortTurn("s1", "s3", listOf("08:00", "08:10", "08:25")))
        val service = pattern.services.first { it.serviceType == ServiceTypes.WEEKDAY }

        assertEquals("08:00", service.firstDeparture)
        assertEquals("08:25", service.lastDeparture)
        assertEquals(listOf(600, 900), service.headways.map { it.intervalSeconds })
    }

    @Test
    fun `时刻乱序且重复时去重升序`() {
        val pattern = planned(ShortTurn("s1", "s3", listOf("08:20", "08:00", "08:10", "08:10")))
        val service = pattern.services.first { it.serviceType == ServiceTypes.WEEKDAY }

        assertEquals("08:00", service.firstDeparture)
        assertEquals("08:20", service.lastDeparture)
        assertEquals(listOf(600, 600), service.headways.map { it.intervalSeconds })
    }

    @Test
    fun `只填一班时用兜底间隔`() {
        val pattern = planned(ShortTurn("s1", "s3", listOf("08:00")))
        val service = pattern.services.first { it.serviceType == ServiceTypes.WEEKDAY }

        assertEquals(1, service.headways.size)
        assertEquals(ShortTurnPlanner.DEFAULT_INTERVAL_SECONDS, service.headways.first().intervalSeconds)
    }

    @Test
    fun `工作日与周末共用同一份时刻`() {
        val pattern = planned(ShortTurn("s1", "s3", listOf("08:00", "08:10")))

        assertEquals(
            listOf(ServiceTypes.WEEKDAY, ServiceTypes.WEEKEND),
            pattern.services.map { it.serviceType }
        )
        assertEquals(
            pattern.services.first().headways,
            pattern.services.last().headways
        )
    }

    // ------------------------------------------------------------------
    // 折算结果参与推算
    // ------------------------------------------------------------------

    @Test
    fun `起点站的到站时刻与填写逐班一致`() {
        val arrivals = ArrivalEstimator(lineWith(ShortTurn("s1", "s3", listOf("08:00", "08:10", "08:25"))))
            .nextArrivals("s1", ServiceTypes.WEEKDAY, seconds("07:55"), limit = 20)
            .filter { it.isShortTurn }

        assertEquals(
            listOf(seconds("08:00"), seconds("08:10"), seconds("08:25")),
            arrivals.map { it.arrivalSecondsOfDay }
        )
    }

    @Test
    fun `中间站按运行偏移顺延`() {
        val arrivals = ArrivalEstimator(lineWith(ShortTurn("s1", "s3", listOf("08:00", "08:10"))))
            .nextArrivals("s2", ServiceTypes.WEEKDAY, seconds("07:55"), limit = 20)
            .filter { it.isShortTurn }

        // 甲乙两站间站间时长 120 秒
        assertEquals(
            listOf(seconds("08:02"), seconds("08:12")),
            arrivals.map { it.arrivalSecondsOfDay }
        )
    }

    // ------------------------------------------------------------------
    // 校验
    // ------------------------------------------------------------------

    @Test
    fun `起点与终点相同时被拒绝`() {
        assertEquals("区间车的起点站与终点站不能相同", rejected(ShortTurn("s1", "s1", listOf("08:00"))))
    }

    @Test
    fun `起点不在线路内被拒绝`() {
        assertEquals("起点站不在该线路内", rejected(ShortTurn("unknown", "s3", listOf("08:00"))))
    }

    @Test
    fun `终点不在线路内被拒绝`() {
        assertEquals("终点站不在该线路内", rejected(ShortTurn("s1", "unknown", listOf("08:00"))))
    }

    @Test
    fun `没有填写时刻时被拒绝`() {
        assertEquals("请至少填写一个发车时刻", rejected(ShortTurn("s1", "s3", listOf(" ", ""))))
    }

    @Test
    fun `时刻格式非法时被拒绝`() {
        assertEquals(
            "时刻格式应为 HH:mm，例如 08:30",
            rejected(ShortTurn("s1", "s3", listOf("08:00", "25:00")))
        )
    }

    @Test
    fun `批量折算跳过非法条目`() {
        val patterns = ShortTurnPlanner.patterns(
            line,
            listOf(
                ShortTurn("s1", "s3", listOf("08:00")),
                ShortTurn("s1", "s1", listOf("08:10")),
                ShortTurn("s4", "s2", listOf("09:00"))
            )
        )

        assertEquals(2, patterns.size)
        assertEquals(listOf("east", "west"), patterns.map { it.directionId })
    }

    private fun seconds(text: String): Int = TimeUtils.parseToSecondsOfDay(text)
}