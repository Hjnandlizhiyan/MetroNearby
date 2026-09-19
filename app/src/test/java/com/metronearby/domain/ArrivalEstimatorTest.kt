package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.ArrivalObservation
import com.metronearby.data.model.HeadwayRule
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Segment
import com.metronearby.data.model.Service
import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.UserOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalEstimatorTest {

    private val line: MetroLine = loadTestLine()

    private fun loadTestLine(): MetroLine {
        val stream = javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")
            ?: error("缺少测试数据 metro/line_test.json")
        return MetroJson.parseLine(stream.bufferedReader().use { it.readText() })
    }

    private fun estimator(overrides: UserOverrides? = null) = ArrivalEstimator(line, overrides)

    /** 只保留全程车，避免区间车插队影响间隔断言 */
    private fun fullOnly() = UserOverrides(
        lineId = LINE_ID,
        patternEnabled = mapOf("east_short" to false, "west_short" to false)
    )

    /** 只看东行方向 */
    private fun eastOnly() = UserOverrides(
        lineId = LINE_ID,
        patternEnabled = mapOf("west_full" to false, "west_short" to false)
    )

    // ------------------------------------------------------------------
    // 发车序列与分时段间隔
    // ------------------------------------------------------------------

    @Test
    fun `早高峰按 120 秒间隔发车`() {
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("05:59"), limit = 3)

        assertEquals(3, result.size)
        assertEquals(seconds("06:00"), result[0].arrivalSecondsOfDay)
        assertEquals(60, result[0].waitSeconds)
        assertEquals(120, result[1].arrivalSecondsOfDay - result[0].arrivalSecondsOfDay)
        assertEquals(120, result[2].arrivalSecondsOfDay - result[1].arrivalSecondsOfDay)
    }

    @Test
    fun `平峰按 300 秒间隔发车`() {
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("06:06"), limit = 3)

        assertEquals(seconds("06:06"), result[0].arrivalSecondsOfDay)
        assertEquals(300, result[1].arrivalSecondsOfDay - result[0].arrivalSecondsOfDay)
        assertEquals(300, result[2].arrivalSecondsOfDay - result[1].arrivalSecondsOfDay)
    }

    @Test
    fun `周末按独立时刻表发车`() {
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKEND, seconds("06:00"), limit = 3)

        assertEquals(3, result.size)
        assertEquals(600, result[1].arrivalSecondsOfDay - result[0].arrivalSecondsOfDay)
    }

    @Test
    fun `末班车之后没有班次`() {
        val result = estimator().nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("07:01"))
        assertTrue(result.isEmpty())
    }

    // ------------------------------------------------------------------
    // offset：各站首末班反推 vs 站间时长兜底
    // ------------------------------------------------------------------

    @Test
    fun `offset 由各站首末班车反推`() {
        val atB = estimator(eastOnly())
            .nextArrivalsByDirection(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "east" }
        assertEquals(120, atB.arrivals.first().offsetSeconds)
        assertEquals(seconds("06:02"), atB.arrivals.first().arrivalSecondsOfDay)

        val atC = estimator(eastOnly())
            .nextArrivalsByDirection(STATION_C, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "east" }
        assertEquals(240, atC.arrivals.first().offsetSeconds)
        assertEquals(seconds("06:04"), atC.arrivals.first().arrivalSecondsOfDay)
    }

    @Test
    fun `西行方向的 offset 独立计算`() {
        // 丁站是东行终点，因此只给出西行方向
        val atD = estimator()
            .nextArrivalsByDirection(STATION_D, ServiceTypes.WEEKDAY, seconds("06:00"))
        assertEquals(1, atD.size)
        assertEquals("west", atD.first().directionId)

        // 丙站西行 offset 由 west_full 在丙站首班(06:02)减始发站首班(06:00)得到
        val atC = estimator()
            .nextArrivalsByDirection(STATION_C, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "west" }
        assertEquals(120, atC.arrivals.first().offsetSeconds)
        assertEquals(seconds("06:02"), atC.arrivals.first().arrivalSecondsOfDay)
    }

    @Test
    fun `缺少各站首末班时用站间时长累加`() {
        val withoutPublished = line.copy(stationServiceTimes = emptyMap())
        val result = ArrivalEstimator(withoutPublished, eastOnly())
            .nextArrivalsByDirection(STATION_C, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "east" }

        assertEquals(240, result.arrivals.first().offsetSeconds)
        assertEquals(seconds("06:04"), result.arrivals.first().arrivalSecondsOfDay)
    }

    // ------------------------------------------------------------------
    // 方向分组：两个方向倒计时
    // ------------------------------------------------------------------

    @Test
    fun `中间站同时给出两个方向的倒计时`() {
        val directions = estimator()
            .nextArrivalsByDirection(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))

        assertEquals(2, directions.size)

        val east = directions.first { it.directionId == "east" }
        val west = directions.first { it.directionId == "west" }

        assertEquals("开往 丁站", east.directionLabel)
        assertEquals(seconds("06:02"), east.arrivals.first().arrivalSecondsOfDay)

        assertEquals("开往 甲站", west.directionLabel)
        assertEquals(seconds("06:04"), west.arrivals.first().arrivalSecondsOfDay)
    }

    @Test
    fun `终点站不显示该方向`() {
        val directions = estimator()
            .nextArrivalsByDirection(STATION_D, ServiceTypes.WEEKDAY, seconds("06:00"))

        assertEquals(1, directions.size)
        assertEquals("west", directions.first().directionId)
    }

    // ------------------------------------------------------------------
    // 环线：站序闭合点不是真正的折返终点
    // ------------------------------------------------------------------

    /** 把测试线改造成环线：补一段 s4 → s1，两个方向首尾相接 */
    private fun ringLine(): MetroLine = line.copy(
        segments = line.segments + Segment("s4", "s1", 120)
    )

    @Test
    fun `环线在站序闭合点也给出该方向`() {
        val directions = ArrivalEstimator(ringLine())
            .nextArrivalsByDirection(STATION_D, ServiceTypes.WEEKDAY, seconds("06:00"))

        assertEquals(2, directions.size)
        val east = directions.first { it.directionId == "east" }
        assertEquals(seconds("06:06"), east.arrivals.first().arrivalSecondsOfDay)
    }

    @Test
    fun `环线在站序起点也给出绕回来的方向`() {
        val directions = ArrivalEstimator(ringLine())
            .nextArrivalsByDirection(STATION_A, ServiceTypes.WEEKDAY, seconds("06:00"))

        assertEquals(2, directions.size)
        val west = directions.first { it.directionId == "west" }
        assertEquals(seconds("06:06"), west.arrivals.first().arrivalSecondsOfDay)
    }

    @Test
    fun `环线收车后仍保留起点的两个方向`() {
        val directions = ArrivalEstimator(ringLine())
            .directionSchedules(STATION_A, ServiceTypes.WEEKDAY, seconds("23:59"))

        assertEquals(setOf("east", "west"), directions.map { it.directionId }.toSet())
        assertTrue(directions.all { it.arrivals.isEmpty() })
        assertTrue(directions.all { it.serviceWindow != null })
    }

    @Test
    fun `线性线路终点收车后仍只保留离站方向`() {
        val directions = estimator()
            .directionSchedules(STATION_D, ServiceTypes.WEEKDAY, seconds("23:59"))

        assertEquals(listOf("west"), directions.map { it.directionId })
    }

    @Test
    fun `区间车与全程车在同一方向内合并排序`() {
        val east = estimator()
            .nextArrivalsByDirection(STATION_A, ServiceTypes.WEEKDAY, seconds("06:10"))
            .first { it.directionId == "east" }

        val first = east.arrivals.first()
        assertEquals("east_short", first.patternId)
        assertTrue(first.isShortTurn)
        assertEquals(seconds("06:10"), first.arrivalSecondsOfDay)
        assertEquals(STATION_C, first.terminalStationId)
        assertEquals("丙站", first.terminalStationName)

        assertEquals("east_full", east.arrivals[1].patternId)
        assertFalse(east.arrivals[1].isShortTurn)
    }

    @Test
    fun `可以按需忽略某个交路`() {
        val overrides = UserOverrides(
            lineId = LINE_ID,
            patternEnabled = mapOf("east_short" to false)
        )
        val east = estimator(overrides)
            .nextArrivalsByDirection(STATION_A, ServiceTypes.WEEKDAY, seconds("06:10"))
            .first { it.directionId == "east" }

        assertTrue(east.arrivals.none { it.patternId == "east_short" })
    }

    // ------------------------------------------------------------------
    // 真实发车序列覆盖
    // ------------------------------------------------------------------

    @Test
    fun `内置真实发车序列优先于推算`() {
        val withExact = line.copy(
            exactDepartures = mapOf(STATION_B to mapOf("east_full" to listOf("06:30", "06:45")))
        )
        val east = ArrivalEstimator(withExact, eastOnly())
            .nextArrivalsByDirection(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "east" }

        val exact = east.arrivals.first { it.patternId == "east_full" }
        assertEquals(ArrivalSource.EXACT, exact.source)
        assertEquals(seconds("06:30"), exact.arrivalSecondsOfDay)
    }

    @Test
    fun `用户录入的真实序列覆盖内置序列`() {
        val withExact = line.copy(
            exactDepartures = mapOf(STATION_B to mapOf("east_full" to listOf("06:30")))
        )
        val overrides = UserOverrides(
            lineId = LINE_ID,
            patternEnabled = mapOf("west_full" to false, "west_short" to false),
            exactDepartures = mapOf(STATION_B to mapOf("east_full" to listOf("06:20")))
        )
        val exact = ArrivalEstimator(withExact, overrides)
            .nextArrivalsByDirection(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "east" }
            .arrivals.first { it.patternId == "east_full" }

        assertEquals(seconds("06:20"), exact.arrivalSecondsOfDay)
    }

    @Test
    fun `班次表管理按日型覆盖并从起点传播到中间站`() {
        val overrides = UserOverrides(
            lineId = LINE_ID,
            serviceDepartures = mapOf(
                "east_full" to mapOf(ServiceTypes.WEEKDAY to listOf("06:30"))
            )
        )
        val estimator = ArrivalEstimator(line, overrides)
        val weekday = estimator.nextArrivals(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"), limit = 20)
            .first { it.patternId == "east_full" }
        val weekend = estimator.nextArrivals(STATION_B, ServiceTypes.WEEKEND, seconds("06:00"), limit = 20)
            .first { it.patternId == "east_full" }

        assertEquals(seconds("06:32"), weekday.arrivalSecondsOfDay)
        assertEquals(ArrivalSource.EXACT, weekday.source)
        assertEquals(seconds("06:02"), weekend.arrivalSecondsOfDay)
    }

    @Test
    fun `手动班次表可让原本无周末服务的区间车运行`() {
        val overrides = UserOverrides(
            lineId = LINE_ID,
            serviceDepartures = mapOf(
                "east_short" to mapOf(ServiceTypes.WEEKEND to listOf("08:00", "08:10"))
            )
        )
        val arrivals = ArrivalEstimator(line, overrides)
            .nextArrivals(STATION_B, ServiceTypes.WEEKEND, seconds("07:55"), limit = 20)
            .filter { it.patternId == "east_short" }

        assertEquals(listOf(seconds("08:02"), seconds("08:12")), arrivals.map { it.arrivalSecondsOfDay })
    }

    @Test
    fun `同一班的中间站修正会传播到后续站预测`() {
        val trip = com.metronearby.data.model.ManagedTrip(
            id = "trip-1",
            departureTime = "06:00",
            stationTimes = mapOf(STATION_B to "06:04")
        )
        val overrides = UserOverrides(
            lineId = LINE_ID,
            serviceTrips = mapOf(
                "east_full" to mapOf(ServiceTypes.WEEKDAY to listOf(trip))
            )
        )
        val estimator = ArrivalEstimator(line, overrides)
        val stationB = estimator.nextArrivals(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"), 20)
            .single { it.patternId == "east_full" }
        val stationC = estimator.nextArrivals(STATION_C, ServiceTypes.WEEKDAY, seconds("06:00"), 20)
            .single { it.patternId == "east_full" }

        assertEquals(seconds("06:04"), stationB.arrivalSecondsOfDay)
        assertEquals(seconds("06:06"), stationC.arrivalSecondsOfDay)
        assertEquals(ArrivalSource.EXACT, stationC.source)
    }

    // ------------------------------------------------------------------
    // 用户观测的平均偏差（只用于对照展示，不影响推算）
    // ------------------------------------------------------------------

    @Test
    fun `用户观测不改动推算结果只产出平均偏差`() {
        val overrides = UserOverrides(
            lineId = LINE_ID,
            patternEnabled = mapOf("west_full" to false, "west_short" to false),
            arrivalObservations = listOf(
                ArrivalObservation(
                    stationId = STATION_B,
                    patternId = "east_full",
                    observedTime = "06:09"
                )
            )
        )
        val arrival = estimator(overrides)
            .nextArrivalsByDirection(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "east" }
            .arrivals.first { it.patternId == "east_full" }

        // 推算保持纯净：offset 仍取自 s2 的首末班反推，到站时刻仍是 06:02
        assertEquals(120, arrival.offsetSeconds)
        assertEquals(seconds("06:02"), arrival.arrivalSecondsOfDay)
        assertEquals(ArrivalSource.ESTIMATED, arrival.source)

        // 观测 06:09 匹配到最近的推算班次 06:08，偏差 +60 秒
        assertEquals(60, arrival.userAverage?.averageOffsetSeconds)
        assertEquals(1, arrival.userAverage?.sampleCount)
        assertEquals(seconds("06:03"), arrival.userArrivalSecondsOfDay)
    }

    @Test
    fun `多条观测的偏差取算术平均`() {
        val overrides = UserOverrides(
            lineId = LINE_ID,
            arrivalObservations = listOf(
                ArrivalObservation(STATION_A, "east_full", "06:00"),
                ArrivalObservation(STATION_A, "east_full", "06:03")
            )
        )
        val arrival = estimator(overrides)
            .nextArrivalsByDirection(STATION_A, ServiceTypes.WEEKDAY, seconds("05:00"))
            .first { it.directionId == "east" }
            .arrivals.first { it.patternId == "east_full" }

        // 06:00 恰好命中班次（偏差 0），06:03 匹配 06:02（偏差 +60），平均 +30
        assertEquals(30, arrival.userAverage?.averageOffsetSeconds)
        assertEquals(2, arrival.userAverage?.sampleCount)
    }

    @Test
    fun `偏差过大的观测视为记错班次被剔除`() {
        val overrides = UserOverrides(
            lineId = LINE_ID,
            arrivalObservations = listOf(
                ArrivalObservation(STATION_A, "east_full", "06:00"),
                ArrivalObservation(STATION_A, "east_full", "07:30")
            )
        )
        val arrival = estimator(overrides)
            .nextArrivalsByDirection(STATION_A, ServiceTypes.WEEKDAY, seconds("05:00"))
            .first { it.directionId == "east" }
            .arrivals.first { it.patternId == "east_full" }

        // 07:30 距最近班次 07:00 超过 5 分钟阈值，只剩 06:00 这条有效样本
        assertEquals(0, arrival.userAverage?.averageOffsetSeconds)
        assertEquals(1, arrival.userAverage?.sampleCount)
    }

    @Test
    fun `其它站点的观测不会串到当前站`() {
        val overrides = UserOverrides(
            lineId = LINE_ID,
            arrivalObservations = listOf(
                ArrivalObservation(STATION_B, "east_full", "06:09")
            )
        )
        val arrival = estimator(overrides)
            .nextArrivalsByDirection(STATION_A, ServiceTypes.WEEKDAY, seconds("05:00"))
            .first { it.directionId == "east" }
            .arrivals.first { it.patternId == "east_full" }

        assertNull(arrival.userAverage)
    }

    // ------------------------------------------------------------------
    // 边界
    // ------------------------------------------------------------------

    @Test
    fun `未知站点返回空结果`() {
        val result = estimator().nextArrivals("unknown", ServiceTypes.WEEKDAY, seconds("06:00"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `查询时刻恰好等于到站时刻时返回该班`() {
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("06:02"), limit = 1)
        assertEquals(seconds("06:02"), result.first().arrivalSecondsOfDay)
        assertEquals(0, result.first().waitSeconds)
    }

    // ------------------------------------------------------------------
    // offset 兜底与方向标签推导
    // ------------------------------------------------------------------

    @Test
    fun `不带用户修正时也能正常推算`() {
        // 直接使用单参构造，覆盖 overrides 的默认值路径
        val result = ArrivalEstimator(line)
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("06:00"), limit = 1)

        assertEquals(1, result.size)
        assertEquals(ArrivalSource.ESTIMATED, result.first().source)
        assertNull(result.first().userAverage)
    }

    @Test
    fun `既无首末班数据又缺站间时长时该交路返回空`() {
        val broken = line.copy(stationServiceTimes = emptyMap(), segments = emptyList())

        val result = ArrivalEstimator(broken, eastOnly())
            .nextArrivalsByDirection(STATION_C, ServiceTypes.WEEKDAY, seconds("06:00"))

        assertTrue("无法推算 offset 时不应给出结果", result.isEmpty())
    }

    @Test
    fun `缺少各站首末班时西行也用站间时长累加`() {
        val withoutPublished = line.copy(stationServiceTimes = emptyMap())

        val west = ArrivalEstimator(withoutPublished)
            .nextArrivalsByDirection(STATION_C, ServiceTypes.WEEKDAY, seconds("06:00"))
            .first { it.directionId == "west" }

        // 西行始发站是丁站，丙站相对它的一段仍需按反向查找 s3->s4 的 120 秒
        assertEquals(120, west.arrivals.first().offsetSeconds)
        assertEquals(seconds("06:02"), west.arrivals.first().arrivalSecondsOfDay)
    }

    @Test
    fun `方向没有显式标签时按最远终点站推导`() {
        val withoutLabels = line.copy(
            patterns = line.patterns.map { pattern ->
                if (pattern.directionId == "east") pattern.copy(directionLabel = null) else pattern
            }
        )

        val east = ArrivalEstimator(withoutLabels)
            .nextArrivalsByDirection(STATION_A, ServiceTypes.WEEKDAY, seconds("06:10"))
            .first { it.directionId == "east" }

        // 甲站东行方向上最远的终点是丁站
        assertEquals("开往 丁站", east.directionLabel)
    }

    // ------------------------------------------------------------------
    // 首末班标志与服务窗口
    // ------------------------------------------------------------------

    @Test
    fun `首班车带首班标志`() {
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("05:59"), limit = 1)

        assertTrue(result.first().isFirstDeparture)
        assertFalse(result.first().isLastDeparture)
    }

    @Test
    fun `末班车带末班标志`() {
        // 间隔推算到 06:56 后跨过 07:00，仍应保留配置中的末班。
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("06:59"), limit = 3)

        assertEquals(listOf(seconds("07:00")), result.map { it.arrivalSecondsOfDay })
        assertTrue(result.first().isLastDeparture)
        assertFalse(result.first().isFirstDeparture)
    }

    @Test
    fun `中间班次没有首末班标志`() {
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("06:03"), limit = 1)

        assertEquals(seconds("06:04"), result.first().arrivalSecondsOfDay)
        assertFalse(result.first().isFirstDeparture)
        assertFalse(result.first().isLastDeparture)
    }

    @Test
    fun `区间车有自己的首末班`() {
        // 区间车工作日只发 06:10/06:20/06:30/06:40 四班
        val shortTurn = estimator(eastOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("06:39"), limit = 20)
            .first { it.patternId == "east_short" }

        assertEquals(seconds("06:40"), shortTurn.arrivalSecondsOfDay)
        assertTrue(shortTurn.isLastDeparture)
    }

    @Test
    fun `当天只发一班时首末标志同时出现`() {
        val singleDeparture = line.copy(
            patterns = line.patterns.map { pattern ->
                if (pattern.id != "east_full") {
                    pattern
                } else {
                    pattern.copy(
                        services = listOf(
                            Service(
                                serviceType = ServiceTypes.WEEKDAY,
                                firstDeparture = "06:00",
                                lastDeparture = "06:00",
                                headways = listOf(HeadwayRule("00:00", "24:00", 600))
                            )
                        )
                    )
                }
            }
        )

        val result = ArrivalEstimator(singleDeparture, eastOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("05:59"), limit = 1)

        assertEquals(seconds("06:00"), result.first().arrivalSecondsOfDay)
        assertTrue(result.first().isFirstDeparture)
        assertTrue(result.first().isLastDeparture)
    }

    @Test
    fun `服务窗口跨交路取最早首班与最晚末班`() {
        val window = estimator().serviceWindow(STATION_A, ServiceTypes.WEEKDAY)!!

        assertEquals(seconds("06:00"), window.firstSecondsOfDay)
        assertEquals(seconds("07:00"), window.lastSecondsOfDay)
    }

    @Test
    fun `服务窗口在终点站只计入可乘方向`() {
        // 丁站是东行终点，东行坐不了，窗口只剩西行全程车
        val window = estimator().serviceWindow(STATION_D, ServiceTypes.WEEKDAY)!!

        assertEquals(seconds("06:00"), window.firstSecondsOfDay)
        assertEquals(seconds("07:00"), window.lastSecondsOfDay)
    }

    @Test
    fun `未知站点没有服务窗口`() {
        assertNull(estimator().serviceWindow("unknown", ServiceTypes.WEEKDAY))
    }

    @Test
    fun `末班车时刻不算已收车`() {
        val last = seconds("07:00")
        val window = estimator(fullOnly()).serviceWindow(STATION_A, ServiceTypes.WEEKDAY)!!

        assertFalse(window.isFinishedAt(last))
        assertTrue(window.isFinishedAt(last + 1))
    }

    // ------------------------------------------------------------------
    // 停站窗口
    // ------------------------------------------------------------------

    @Test
    fun `停站窗口内已到站的班次仍然保留`() {
        val now = seconds("06:00") + ArrivalEstimator.DEFAULT_DWELL_SECONDS
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, now, limit = 1)

        assertEquals(seconds("06:00"), result.first().arrivalSecondsOfDay)
        assertEquals(-ArrivalEstimator.DEFAULT_DWELL_SECONDS, result.first().waitSeconds)
    }

    @Test
    fun `停站窗口内的班次排在下一班之前`() {
        val now = seconds("06:00") + 30
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, now, limit = 3)

        assertEquals(
            listOf(seconds("06:00"), seconds("06:02"), seconds("06:04")),
            result.map { it.arrivalSecondsOfDay }
        )
        assertEquals(-30, result.first().waitSeconds)
    }

    @Test
    fun `超出停站窗口后不再保留已到站的班次`() {
        val now = seconds("06:00") + ArrivalEstimator.DEFAULT_DWELL_SECONDS + 1
        val result = estimator(fullOnly())
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, now, limit = 1)

        assertEquals(seconds("06:02"), result.first().arrivalSecondsOfDay)
        assertEquals(74, result.first().waitSeconds)
    }

    @Test
    fun `停站窗口为 0 或负数时回到只保留未来班次的口径`() {
        val now = seconds("06:00") + 30

        val zero = ArrivalEstimator(line, fullOnly(), dwellSeconds = 0)
            .nextArrivals(STATION_A, ServiceTypes.WEEKDAY, now, limit = 1)

        assertEquals(seconds("06:02"), zero.first().arrivalSecondsOfDay)
        assertEquals(90, zero.first().waitSeconds)
        assertEquals(0, ArrivalEstimator(line, null, dwellSeconds = -10).dwellSeconds)
    }

    @Test
    fun `末班前不因间隔跨界提前收车`() {
        val window = estimator(fullOnly()).serviceWindow(STATION_A, ServiceTypes.WEEKDAY)!!
        assertFalse(window.isFinishedAt(seconds("06:59")))
    }

    @Test
    fun `下游站末班保留运行偏移`() {
        val last = estimator(fullOnly())
            .nextArrivalsByDirection(STATION_B, ServiceTypes.WEEKDAY, seconds("07:01"))
            .first { it.directionId == "east" }.arrivals.single()
        assertEquals(seconds("07:02"), last.arrivalSecondsOfDay)
    }

    @Test
    fun `间隔恰好命中末班不重复添加`() {
        val arrivals = estimator().nextArrivals(STATION_D, ServiceTypes.WEEKDAY, seconds("06:59"))
        assertEquals(listOf(seconds("07:00")), arrivals.map { it.arrivalSecondsOfDay })
    }

    @Test
    fun `精确时刻表不依赖发车间隔`() {
        val exactLine = line.copy(
            patterns = line.patterns.filter { it.id == "east_full" }.map { pattern ->
                pattern.copy(services = pattern.services.map { it.copy(headways = emptyList()) })
            },
            exactDepartures = mapOf(STATION_B to mapOf("east_full" to listOf("06:30")))
        )
        val arrivals = ArrivalEstimator(exactLine).nextArrivals(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))
        assertEquals(listOf(seconds("06:30")), arrivals.map { it.arrivalSecondsOfDay })
    }

    @Test
    fun `精确时刻表不依赖站间运行数据`() {
        val exactLine = line.copy(
            stationServiceTimes = emptyMap(),
            segments = emptyList(),
            exactDepartures = mapOf(STATION_B to mapOf("east_full" to listOf("06:30")))
        )
        val arrivals = ArrivalEstimator(exactLine).nextArrivals(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))
        assertEquals(listOf(seconds("06:30")), arrivals.map { it.arrivalSecondsOfDay })
    }

    @Test
    fun `精确时刻不自动添加配置中的末班`() {
        val exactLine = line.copy(
            patterns = line.patterns.filter { it.id == "east_full" },
            exactDepartures = mapOf(STATION_B to mapOf("east_full" to listOf("06:30")))
        )
        assertEquals(seconds("06:30"), ArrivalEstimator(exactLine).serviceWindow(STATION_B, ServiceTypes.WEEKDAY)!!.lastSecondsOfDay)
    }

    @Test
    fun `重复的精确时刻只显示一班`() {
        val exactLine = line.copy(
            patterns = line.patterns.filter { it.id == "east_full" },
            exactDepartures = mapOf(STATION_B to mapOf("east_full" to listOf("06:30", "06:30", "06:45")))
        )
        val arrivals = ArrivalEstimator(exactLine).nextArrivals(STATION_B, ServiceTypes.WEEKDAY, seconds("06:00"))
        assertEquals(listOf(seconds("06:30"), seconds("06:45")), arrivals.map { it.arrivalSecondsOfDay })
    }

    @Test
    fun `环线上的区间车在终点不能继续乘坐`() {
        val arrivals = ArrivalEstimator(ringLine()).nextArrivals(STATION_C, ServiceTypes.WEEKDAY, seconds("06:10"), limit = 100)
        assertTrue(arrivals.none { it.patternId == "east_short" })
    }

    @Test
    fun `环线上的区间车仍在中间站展示`() {
        val arrivals = ArrivalEstimator(ringLine()).nextArrivals(STATION_B, ServiceTypes.WEEKDAY, seconds("06:10"), limit = 100)
        assertTrue(arrivals.any { it.patternId == "east_short" })
    }

    @Test
    fun `无效间隔不生成假班次`() {
        val invalid = line.copy(patterns = line.patterns.take(1).map { pattern ->
            pattern.copy(services = pattern.services.map {
                it.copy(headways = listOf(HeadwayRule("06:00", "07:00", 0)))
            })
        })
        assertTrue(ArrivalEstimator(invalid).nextArrivals(STATION_A, ServiceTypes.WEEKDAY, seconds("06:00")).isEmpty())
    }

    private fun seconds(text: String): Int = TimeUtils.parseToSecondsOfDay(text)

    private companion object {
        const val LINE_ID = "test"
        const val STATION_A = "s1"
        const val STATION_B = "s2"
        const val STATION_C = "s3"
        const val STATION_D = "s4"
    }
}
