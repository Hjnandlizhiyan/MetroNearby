package com.metronearby.data

import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.ManagedTrip
import com.metronearby.data.model.StationCoordFix
import com.metronearby.data.model.UserOverrides
import com.metronearby.data.source.FileOverrideStore
import com.metronearby.data.source.InMemoryMetroDataSource
import com.metronearby.domain.ArrivalEstimator
import com.metronearby.domain.TimeUtils
import com.metronearby.domain.userArrivalSecondsOfDay
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class MetroRepositoryOverrideTest {

    @Test fun `间隔组更新不会重复追加且切线路仍保留`() {
        val store = tempStore()
        val repo = repository(store)
        val session = com.metronearby.data.model.HeadwaySession("id", "s1", "east_full", "weekday", "2026-09-18", 28800)
        repo.saveHeadwaySession("test", session)
        repo.saveHeadwaySession("test", session.copy(ended = true))
        repo.recordObservation("bj9", "s9", "p", "08:00")
        assertEquals(1, repo.loadResolvedLine("line_test.json").overrides!!.headwaySessions.size)
        assertTrue(repo.loadResolvedLine("line_test.json").overrides!!.headwaySessions.single().ended)
    }

    @Test fun `新组会结束同交路旧组`() {
        val repo = repository(tempStore())
        val session = com.metronearby.data.model.HeadwaySession("old", "s1", "east_full", "weekday", "2026-09-18", 28800)
        repo.saveHeadwaySession("test", session)
        repo.saveHeadwaySession("test", session.copy(id = "new"))
        assertEquals(1, repo.loadResolvedLine("line_test.json").overrides!!.headwaySessions.count { !it.ended })
    }

    @Test fun `删除间隔组不删普通观测`() {
        val repo = repository(tempStore())
        repo.recordObservation("test", "s1", "east_full", "08:00")
        repo.saveHeadwaySession("test", com.metronearby.data.model.HeadwaySession("id", "s1", "east_full", "weekday", "2026-09-18", 28800))
        repo.removeHeadwaySession("test", "id")
        val saved = repo.loadResolvedLine("line_test.json").overrides!!
        assertTrue(saved.headwaySessions.isEmpty())
        assertEquals(1, saved.arrivalObservations.size)
    }

    @Test fun `在另一线路录入不会丢失先前观测`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordObservation("test", "s1", "east_full", "08:01")
        repo.recordObservation("bj9", "bj9_01", "forward", "08:05")
        assertEquals("08:01", repo.loadResolvedLine("line_test.json").overrides!!.arrivalObservations.single().observedTime)
    }

    @Test fun `切回旧线路继续累积且归档不嵌套`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordObservation("test", "s1", "east_full", "08:01")
        repo.recordObservation("bj9", "bj9_01", "forward", "08:05")
        repo.recordObservation("test", "s1", "east_full", "08:11")
        val saved = store.load()!!
        assertEquals(2, saved.arrivalObservations.size)
        assertEquals("08:05", saved.otherLines.getValue("bj9").arrivalObservations.single().observedTime)
        assertTrue(saved.otherLines.getValue("bj9").otherLines.isEmpty())
    }

    @Test fun `清空一个线路不会清空另一线路`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordObservation("test", "s1", "east_full", "08:01")
        repo.recordObservation("bj9", "bj9_01", "forward", "08:05")
        repo.clearObservations("test", "s1", "east_full")
        assertEquals(1, store.load()!!.otherLines.getValue("bj9").arrivalObservations.size)
    }

    private val testJson: String =
        javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!
            .bufferedReader().use { it.readText() }

    private fun repository(store: FileOverrideStore? = null) = MetroRepository(
        dataSource = InMemoryMetroDataSource(
            mapOf(MetroRepository.assetPath("line_test.json") to testJson)
        ),
        overrideStore = store
    )

    private fun tempStore(): FileOverrideStore {
        val file = File.createTempFile("overrides", ".json")
        file.delete()
        file.deleteOnExit()
        return FileOverrideStore(file)
    }

    /**
     * 取"东行 full 交路"在指定站的下一班。
     *
     * 不能用扁平的 nextArrivals() 再按 patternId 过滤：默认 limit 只有 3，
     * 目标交路会被其它方向的班次挤出列表，导致断言失败。
     */
    private fun eastFullArrival(
        resolved: MetroRepository.ResolvedLine,
        stationId: String,
        now: String
    ) = ArrivalEstimator(resolved.line, resolved.overrides)
        .nextArrivalsByDirection(
            stationId,
            ServiceTypes.WEEKDAY,
            TimeUtils.parseToSecondsOfDay(now),
            perDirectionLimit = 10
        )
        .first { it.directionId == "east" }
        .arrivals
        .first { it.patternId == "east_full" }

    // ------------------------------------------------------------------
    // 静态修正合并
    // ------------------------------------------------------------------

    @Test
    fun `坐标修正会覆盖内置坐标`() {
        val overrides = UserOverrides(
            lineId = "test",
            stationCoordFix = mapOf("s1" to StationCoordFix(lat = 1.0, lng = 2.0))
        )
        val merged = repository().merge(repository().loadLine("line_test.json"), overrides)
        assertEquals(1.0, merged.stationById("s1")!!.lat, 0.0001)
        assertEquals(2.0, merged.stationById("s1")!!.lng, 0.0001)
    }

    @Test
    fun `站间时长修正会覆盖内置值`() {
        val overrides = UserOverrides(
            lineId = "test",
            segmentRunSecondsFix = mapOf("s1->s2" to 200)
        )
        val merged = repository().merge(repository().loadLine("line_test.json"), overrides)
        assertEquals(200, merged.segments.first { it.from == "s1" }.runSeconds)
    }

    @Test
    fun `发车间隔修正会覆盖内置分时段配置`() {
        val overrides = UserOverrides(
            lineId = "test",
            headwayFix = mapOf(
                "east_full" to mapOf(
                    ServiceTypes.WEEKDAY to listOf(
                        com.metronearby.data.model.HeadwayRule(
                            from = "06:00",
                            to = "07:00",
                            intervalSeconds = 60
                        )
                    )
                )
            )
        )
        val merged = repository().merge(repository().loadLine("line_test.json"), overrides)
        val service = merged.patternById("east_full")!!
            .services.first { it.serviceType == ServiceTypes.WEEKDAY }

        assertEquals(1, service.headways.size)
        assertEquals(60, service.headways.first().intervalSeconds)
    }

    // ------------------------------------------------------------------
    // 用户反馈入口：写入后立即生效
    // ------------------------------------------------------------------

    @Test
    fun `录入真实发车时刻后推算结果立即采用`() {
        val store = tempStore()
        val repo = repository(store)

        val before = repo.loadResolvedLine("line_test.json")
        val beforeArrival = eastFullArrival(before, "s2", "06:00")
        assertEquals("06:02", TimeUtils.formatSecondsOfDay(beforeArrival.arrivalSecondsOfDay))

        repo.recordExactDeparture(
            lineId = "test",
            stationId = "s2",
            patternId = "east_full",
            departureTime = "06:30"
        )

        val after = repo.loadResolvedLine("line_test.json")
        val afterArrival = eastFullArrival(after, "s2", "06:00")

        assertEquals("06:30", TimeUtils.formatSecondsOfDay(afterArrival.arrivalSecondsOfDay))
    }

    @Test
    fun `录入观测时间后落盘并产出平均偏差`() {
        val store = tempStore()
        val repo = repository(store)

        repo.recordObservation(
            lineId = "test",
            stationId = "s2",
            patternId = "east_full",
            observedTime = "06:09"
        )

        val resolved = repo.loadResolvedLine("line_test.json")
        val overrides = resolved.overrides
        assertNotNull(overrides)
        assertEquals(1, overrides!!.arrivalObservations.size)

        val arrival = eastFullArrival(resolved, "s2", "06:00")

        // 推算结果不受观测影响，仍是纯推算的 06:02
        assertEquals("06:02", TimeUtils.formatSecondsOfDay(arrival.arrivalSecondsOfDay))
        // 观测 06:09 匹配到最近的推算班次 06:08，偏差 +60 秒
        assertEquals(60, arrival.userAverage?.averageOffsetSeconds)
        assertEquals("06:03", TimeUtils.formatSecondsOfDay(arrival.userArrivalSecondsOfDay!!))
    }

    @Test
    fun `录入观测会保存分时段与锚点元数据`() {
        val store = tempStore()
        val repo = repository(store)

        repo.recordObservation(
            lineId = "test",
            stationId = "s2",
            patternId = "east_full",
            observedTime = "08:35",
            serviceType = ServiceTypes.WEEKDAY,
            timeBand = "morning_peak",
            recordedAtEpochMillis = 123456L,
            recordedSecondsOfDay = 8 * 3600 + 35 * 60
        )

        val saved = repo.loadResolvedLine("line_test.json").overrides!!.arrivalObservations.single()
        assertEquals(ServiceTypes.WEEKDAY, saved.serviceType)
        assertEquals("morning_peak", saved.timeBand)
        assertEquals(123456L, saved.recordedAtEpochMillis)
        assertEquals(8 * 3600 + 35 * 60, saved.recordedSecondsOfDay)
    }

    @Test
    fun `未显式传入时段时按观测时刻自动归类`() {
        val store = tempStore()
        val repo = repository(store)

        repo.recordObservation("test", "s2", "east_full", "18:35")

        assertEquals(
            "evening_peak",
            repo.loadResolvedLine("line_test.json").overrides!!.arrivalObservations.single().timeBand
        )
    }

    @Test
    fun `删除指定观测后只移除那一条`() {
        val store = tempStore()
        val repo = repository(store)
        listOf("06:09", "06:15", "06:21").forEach { time ->
            repo.recordObservation(
                lineId = "test",
                stationId = "s2",
                patternId = "east_full",
                observedTime = time
            )
        }

        repo.removeObservation(
            lineId = "test",
            stationId = "s2",
            patternId = "east_full",
            index = 1
        )

        val observations = repo.loadResolvedLine("line_test.json").overrides!!.arrivalObservations
        assertEquals(listOf("06:09", "06:21"), observations.map { it.observedTime })
    }

    @Test
    fun `删除越界索引时观测保持不变`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordObservation(
            lineId = "test",
            stationId = "s2",
            patternId = "east_full",
            observedTime = "06:09"
        )

        repo.removeObservation(
            lineId = "test",
            stationId = "s2",
            patternId = "east_full",
            index = 5
        )

        val observations = repo.loadResolvedLine("line_test.json").overrides!!.arrivalObservations
        assertEquals(listOf("06:09"), observations.map { it.observedTime })
    }

    @Test
    fun `删除观测不影响其它交路`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_full", observedTime = "06:09")
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_full", observedTime = "06:15")
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_short", observedTime = "06:30")

        repo.removeObservation(lineId = "test", stationId = "s2", patternId = "east_full", index = 0)

        val observations = repo.loadResolvedLine("line_test.json").overrides!!.arrivalObservations
        assertEquals(2, observations.size)
        assertEquals(1, observations.count { it.patternId == "east_full" })
        assertEquals(1, observations.count { it.patternId == "east_short" })
    }

    @Test
    fun `清空观测只清目标交路`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_full", observedTime = "06:09")
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_full", observedTime = "06:15")
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_short", observedTime = "06:30")

        repo.clearObservations(lineId = "test", stationId = "s2", patternId = "east_full")

        val observations = repo.loadResolvedLine("line_test.json").overrides!!.arrivalObservations
        assertEquals(listOf("06:30"), observations.map { it.observedTime })
    }

    @Test
    fun `删除观测后平均样本数随之减少且推算不变`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_full", observedTime = "06:09")
        repo.recordObservation(lineId = "test", stationId = "s2", patternId = "east_full", observedTime = "06:15")

        val before = eastFullArrival(repo.loadResolvedLine("line_test.json"), "s2", "06:00")
        assertEquals(2, before.userAverage?.sampleCount)

        repo.removeObservation(lineId = "test", stationId = "s2", patternId = "east_full", index = 0)

        val after = eastFullArrival(repo.loadResolvedLine("line_test.json"), "s2", "06:00")
        assertEquals(1, after.userAverage?.sampleCount)
        // 删样本同样不触碰推算结果
        assertEquals("06:02", TimeUtils.formatSecondsOfDay(after.arrivalSecondsOfDay))
    }

    @Test
    fun `禁用交路后不再返回该交路`() {
        val store = tempStore()
        val repo = repository(store)
        repo.setPatternEnabled(lineId = "test", patternId = "east_short", enabled = false)

        val resolved = repo.loadResolvedLine("line_test.json")
        val result = ArrivalEstimator(resolved.line, resolved.overrides)
            .nextArrivals("s1", ServiceTypes.WEEKDAY, TimeUtils.parseToSecondsOfDay("06:10"), limit = 5)

        assertTrue(result.none { it.patternId == "east_short" })
    }

    @Test
    fun `清除真实发车序列后回到推算`() {
        val store = tempStore()
        val repo = repository(store)
        repo.recordExactDeparture(
            lineId = "test",
            stationId = "s2",
            patternId = "east_full",
            departureTime = "06:30"
        )
        repo.clearExactDepartures(lineId = "test", stationId = "s2", patternId = "east_full")

        val resolved = repo.loadResolvedLine("line_test.json")
        val arrival = eastFullArrival(resolved, "s2", "06:00")

        assertEquals("06:02", TimeUtils.formatSecondsOfDay(arrival.arrivalSecondsOfDay))
    }

    @Test
    fun `班次表按工作日周末分别保存并可单独恢复`() {
        val repo = repository(tempStore())
        repo.setServiceDepartures("test", "east_full", ServiceTypes.WEEKDAY, listOf("08:10", "08:00"))
        repo.setServiceDepartures("test", "east_full", ServiceTypes.WEEKEND, listOf("09:00"))

        var saved = repo.loadResolvedLine("line_test.json").overrides!!
        assertEquals(listOf("08:00", "08:10"), saved.serviceDepartures.getValue("east_full").getValue(ServiceTypes.WEEKDAY))
        assertEquals(listOf("09:00"), saved.serviceDepartures.getValue("east_full").getValue(ServiceTypes.WEEKEND))

        repo.clearServiceDepartures("test", "east_full", ServiceTypes.WEEKDAY)
        saved = repo.loadResolvedLine("line_test.json").overrides!!
        assertEquals(null, saved.serviceDepartures.getValue("east_full")[ServiceTypes.WEEKDAY])
        assertEquals(listOf("09:00"), saved.serviceDepartures.getValue("east_full").getValue(ServiceTypes.WEEKEND))
    }

    @Test
    fun `恢复本线路默认会清除全部交路和日型班次表`() {
        val repo = repository(tempStore())
        repo.setServiceDepartures("test", "east_full", ServiceTypes.WEEKDAY, listOf("08:00"))
        repo.setServiceDepartures("test", "east_full", ServiceTypes.WEEKEND, listOf("09:00"))
        repo.setServiceDepartures("test", "west_full", ServiceTypes.WEEKDAY, listOf("10:00"))

        repo.clearAllServiceSchedules("test")

        val saved = repo.loadResolvedLine("line_test.json").overrides!!
        assertTrue(saved.serviceDepartures.isEmpty())
        assertTrue(saved.serviceTrips.isEmpty())
    }

    @Test
    fun `恢复本线路默认会保留到站观测`() {
        val repo = repository(tempStore())
        repo.setServiceDepartures("test", "east_full", ServiceTypes.WEEKDAY, listOf("08:00"))
        repo.recordObservation("test", "s2", "east_full", "08:02")

        repo.clearAllServiceSchedules("test")

        val saved = repo.loadResolvedLine("line_test.json").overrides!!
        assertEquals(1, saved.arrivalObservations.size)
    }
    @Test
    fun `用户区间车可分别覆盖工作日和周末班次`() {
        val repo = repository(tempStore())
        repo.addShortTurn("test", "s1", "s3", listOf("08:00"))
        val patternId = repo.loadResolvedLine("line_test.json").line.patterns.single { it.id.startsWith("user-shortturn") }.id
        repo.setServiceDepartures("test", patternId, ServiceTypes.WEEKDAY, listOf("09:00"))
        repo.setServiceDepartures("test", patternId, ServiceTypes.WEEKEND, listOf("10:00"))

        val resolved = repo.loadResolvedLine("line_test.json")
        val weekday = ArrivalEstimator(resolved.line, resolved.overrides)
            .nextArrivals("s2", ServiceTypes.WEEKDAY, TimeUtils.parseToSecondsOfDay("08:55"), 20)
            .single { it.patternId == patternId }
        val weekend = ArrivalEstimator(resolved.line, resolved.overrides)
            .nextArrivals("s2", ServiceTypes.WEEKEND, TimeUtils.parseToSecondsOfDay("09:55"), 20)
            .single { it.patternId == patternId }
        assertEquals("09:02", TimeUtils.formatSecondsOfDay(weekday.arrivalSecondsOfDay))
        assertEquals("10:02", TimeUtils.formatSecondsOfDay(weekend.arrivalSecondsOfDay))
    }

    @Test
    fun `带中间站修正的班次会落盘并同步兼容起点时刻表`() {
        val repo = repository(tempStore())
        val line = repo.loadResolvedLine("line_test.json").line
        repo.setServiceTrips(
            line = line,
            patternId = "east_full",
            serviceType = ServiceTypes.WEEKDAY,
            trips = listOf(ManagedTrip("stable", "06:00", mapOf("s2" to "06:04")))
        )

        val saved = repo.loadResolvedLine("line_test.json").overrides!!
        assertEquals("stable", saved.serviceTrips.getValue("east_full")
            .getValue(ServiceTypes.WEEKDAY).single().id)
        assertEquals("06:04", saved.serviceTrips.getValue("east_full")
            .getValue(ServiceTypes.WEEKDAY).single().stationTimes.getValue("s2"))
        assertEquals(listOf("06:00"), saved.serviceDepartures.getValue("east_full")
            .getValue(ServiceTypes.WEEKDAY))
    }

    @Test
    fun `发生超车的逐站班次表拒绝落盘`() {
        val repo = repository(tempStore())
        val line = repo.loadResolvedLine("line_test.json").line
        val error = assertThrows(IllegalArgumentException::class.java) {
            repo.setServiceTrips(
                line = line,
                patternId = "east_full",
                serviceType = ServiceTypes.WEEKDAY,
                trips = listOf(
                    ManagedTrip("first", "06:00", mapOf("s2" to "06:10")),
                    ManagedTrip("second", "06:05")
                )
            )
        }
        assertTrue(error.message!!.contains("追上或超过"))
        assertNull(repo.loadResolvedLine("line_test.json").overrides)
    }

    // ------------------------------------------------------------------
    // 用户自定义区间车
    // ------------------------------------------------------------------

    /** 取某站由用户区间车给出的班次；内置的区间车交路不在此范围 */
    private fun shortTurnArrivals(
        resolved: MetroRepository.ResolvedLine,
        stationId: String,
        now: String
    ) = ArrivalEstimator(resolved.line, resolved.overrides)
        .nextArrivals(stationId, ServiceTypes.WEEKDAY, TimeUtils.parseToSecondsOfDay(now), limit = 20)
        .filter { it.isShortTurn }

    @Test
    fun `新增区间车后立即折算进线路数据并给出班次`() {
        val store = tempStore()
        val repo = repository(store)

        repo.addShortTurn(
            lineId = "test",
            startStationId = "s1",
            endStationId = "s3",
            departures = listOf("08:00", "08:10")
        )

        val resolved = repo.loadResolvedLine("line_test.json")
        assertEquals(1, resolved.overrides!!.shortTurns.size)
        assertEquals(5, resolved.line.patterns.size)

        val arrivals = shortTurnArrivals(resolved, "s1", "07:55")
        assertEquals(
            listOf("08:00", "08:10"),
            arrivals.map { TimeUtils.formatSecondsOfDay(it.arrivalSecondsOfDay) }
        )
    }

    @Test
    fun `删除区间车后不再给出该交路班次`() {
        val store = tempStore()
        val repo = repository(store)
        repo.addShortTurn(
            lineId = "test",
            startStationId = "s1",
            endStationId = "s3",
            departures = listOf("08:00", "08:10")
        )

        repo.removeShortTurn(lineId = "test", index = 0)

        val resolved = repo.loadResolvedLine("line_test.json")
        assertTrue(resolved.overrides!!.shortTurns.isEmpty())
        assertTrue(shortTurnArrivals(resolved, "s1", "07:55").isEmpty())
    }

    @Test
    fun `删除越界索引时区间车保持不变`() {
        val store = tempStore()
        val repo = repository(store)
        repo.addShortTurn(lineId = "test", startStationId = "s1", endStationId = "s3", departures = listOf("08:00"))
        repo.addShortTurn(lineId = "test", startStationId = "s4", endStationId = "s2", departures = listOf("09:00"))

        repo.removeShortTurn(lineId = "test", index = 5)

        assertEquals(2, repo.loadResolvedLine("line_test.json").overrides!!.shortTurns.size)
    }

    @Test
    fun `新增区间车时剔除空白时刻`() {
        val store = tempStore()
        val repo = repository(store)

        repo.addShortTurn(
            lineId = "test",
            startStationId = "s1",
            endStationId = "s3",
            departures = listOf(" 08:00 ", "", "08:10")
        )

        assertEquals(
            listOf("08:00", "08:10"),
            repo.loadResolvedLine("line_test.json").overrides!!.shortTurns.first().departures
        )
    }

    @Test
    fun `没有修正文件时不产生覆盖数据`() {
        val repo = repository(null)
        val resolved = repo.loadResolvedLine("line_test.json")
        assertNull(resolved.overrides)
    }

    @Test
    fun `不同线路的修正文件不会串用`() {
        val store = tempStore()
        store.save(UserOverrides(lineId = "other", patternEnabled = mapOf("east_short" to false)))

        val resolved = repository(store).loadResolvedLine("line_test.json")
        assertNull(resolved.overrides)
    }

    @Test
    fun `按默认路径读取城市索引`() {
        val cityJson = """
            {
              "schemaVersion": 1,
              "cityId": "beijing",
              "cityName": "北京",
              "coordSystem": "wgs84",
              "lines": [
                {
                  "lineId": "bj1",
                  "name": "1号线",
                  "color": "#A4343A",
                  "dataFile": "line_beijing_1.json"
                }
              ]
            }
        """.trimIndent()

        val repo = MetroRepository(
            dataSource = InMemoryMetroDataSource(
                mapOf(MetroRepository.DEFAULT_CITY_INDEX to cityJson)
            )
        )

        val city = repo.loadCityIndex()
        assertEquals("beijing", city.cityId)
        assertEquals("北京", city.cityName)
        assertEquals(1, city.lines.size)

        val lineRef = city.lines.first()
        assertEquals("bj1", lineRef.lineId)
        assertEquals("1号线", lineRef.name)
        assertEquals("line_beijing_1.json", lineRef.dataFile)
        assertEquals(MetroRepository.assetPath("line_beijing_1.json"), "metro/line_beijing_1.json")
    }
}
