package com.metronearby.data

import com.metronearby.data.model.ArrivalObservation
import com.metronearby.data.model.CityIndex
import com.metronearby.data.model.CoordSystems
import com.metronearby.data.model.DEFAULT_DIRECTION_ID
import com.metronearby.data.model.LineRef
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.Station
import com.metronearby.data.model.StationServiceTime
import com.metronearby.data.model.UserOverrides
import com.metronearby.domain.ArrivalEstimator
import com.metronearby.domain.LineVisuals
import com.metronearby.domain.TimeUtils
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MetroJsonParsingTest {

    private fun resourceText(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!.bufferedReader().use { it.readText() }

    /**
     * 读取内置的北京一号线全线数据。Gradle 单测的工作目录是 app/，
     * 因此可直接读到 assets 下的文件；找不到时跳过而不是失败。
     */
    private fun beijingLine(): MetroLine {
        val file = File("src/main/assets/metro/line_beijing_1.json")
        assumeTrue("找不到内置数据文件，跳过", file.exists())
        return MetroJson.parseLine(file.readText())
    }

    @Test
    fun `解析线路数据`() {
        val line = MetroJson.parseLine(resourceText("metro/line_test.json"))

        assertEquals("test", line.lineId)
        assertEquals(CoordSystems.WGS84, line.coordSystem)
        assertEquals(listOf("s1", "s2", "s3", "s4"), line.stationOrder)
        assertEquals(4, line.stations.size)
        assertEquals(4, line.patterns.size)
        assertEquals(2, line.patterns.count { it.isShortTurn })
        assertEquals("甲站", line.stationById("s1")?.name)
    }

    @Test
    fun `解析方向与分时段间隔`() {
        val line = MetroJson.parseLine(resourceText("metro/line_test.json"))

        val east = line.patterns.filter { it.directionId == "east" }
        val west = line.patterns.filter { it.directionId == "west" }
        assertEquals(2, east.size)
        assertEquals(2, west.size)
        assertEquals("开往 丁站", line.patternById("east_full")?.directionLabel)

        assertEquals("06:02", line.stationServiceTimes["s2"]?.get("east_full")?.firstDeparture)
        val weekday = line.patternById("east_full")!!
            .services.first { it.serviceType == ServiceTypes.WEEKDAY }
        assertEquals(2, weekday.headways.size)
        assertEquals(120, weekday.headways.first().intervalSeconds)
    }

    @Test
    fun `用户修正数据可往返序列化`() {
        val original = UserOverrides(
            lineId = "test",
            updatedAt = "2026-09-18T10:00:00+08:00",
            exactDepartures = mapOf("s2" to mapOf("east_full" to listOf("06:30", "06:45"))),
            arrivalObservations = listOf(ArrivalObservation("s2", "east_full", "06:09")),
            patternEnabled = mapOf("east_short" to false)
        )

        val decoded = MetroJson.parseOverrides(MetroJson.encodeOverrides(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `未知字段被忽略以便向前兼容`() {
        val json = """
            {
              "schemaVersion": 1,
              "lineId": "x",
              "lineName": "X线",
              "futureField": { "a": 1 },
              "stationOrder": [],
              "stations": [],
              "patterns": []
            }
        """.trimIndent()

        val line = MetroJson.parseLine(json)
        assertEquals("x", line.lineId)
    }

    /**
     * 真实内置数据文件的格式校验。Gradle 单测的工作目录是 app/，
     * 因此可以直接读到 assets 下的文件；在无法定位时跳过而不是失败。
     */
    @Test
    fun `内置北京一号线全线数据可被正确解析`() {
        val line = beijingLine()

        assertEquals("bj1", line.lineId)
        assertEquals("beijing", line.cityId)
        assertEquals("北京", line.cityName)
        assertEquals("1号线", line.lineName)
        assertEquals(CoordSystems.WGS84, line.coordSystem)
        assertEquals("苹果园", line.stationById("bj1_01")?.name)
        assertEquals("西单", line.stationById("bj1_13")?.name)
        assertEquals(39.9057386, line.stationById("bj1_13")!!.lat, 0.0000001)
        assertTrue("数据仍标注为待核对", line.needsReview)
    }

    // ------------------------------------------------------------------
    // 内置北京一号线：全线 36 站逐站校验
    // ------------------------------------------------------------------

    @Test
    fun `一号线站序与站表一一对应且无重复`() {
        val line = beijingLine()

        assertEquals("站表数量应与站序一致", line.stations.size, line.stationOrder.size)
        assertEquals("站序不应有重复", line.stationOrder.size, line.stationOrder.toSet().size)
        assertEquals("站表不应有重复 id", line.stations.size, line.stations.map { it.id }.toSet().size)
        assertEquals("站表顺序应与站序逐一对应", line.stations.map { it.id }, line.stationOrder)

        assertEquals("苹果园", line.stationById("bj1_01")?.name)
        assertEquals("环球度假区", line.stationById("bj1_36")?.name)
        assertEquals(36, line.stations.size)

        // 每一站都应按其在站序中的真实位置被索引到
        line.stationOrder.forEachIndexed { index, stationId ->
            assertEquals("$stationId 的 orderIndexOf 应等于 $index", index, line.orderIndexOf(stationId))
        }
        assertEquals(-1, line.orderIndexOf("bj1_99"))
    }

    @Test
    fun `一号线每一站都有有效坐标与别名`() {
        val line = beijingLine()

        line.stations.forEachIndexed { index, station ->
            val label = "第 ${index + 1} 站 ${station.id}"
            assertTrue("$label 名称不应为空", station.name.isNotBlank())
            assertTrue("$label 纬度应落在北京纬度范围内", station.lat in 39.7..40.1)
            assertTrue("$label 经度应落在北京经度范围内", station.lng in 116.0..116.9)
            assertTrue("$label 应至少有一个别名", station.aliases.isNotEmpty())
            assertTrue(
                "$label 的别名应包含标准站名",
                station.aliases.contains("${station.name}站")
            )
        }
    }

    @Test
    fun `一号线站间线段首尾相接且覆盖全线`() {
        val line = beijingLine()

        assertEquals("36 站应产生 35 段", line.stationOrder.size - 1, line.segments.size)
        line.segments.forEachIndexed { index, segment ->
            assertEquals("第 ${index + 1} 段起点应接前一段终点", line.stationOrder[index], segment.from)
            assertEquals("第 ${index + 1} 段终点应是下一站", line.stationOrder[index + 1], segment.to)
            assertTrue("第 ${index + 1} 段站间时长应为正数", segment.runSeconds > 0)
        }

        // 全段累加得到的全程运行时长应落在合理区间（约一小时上下）
        val totalSeconds = line.segments.sumOf { it.runSeconds }
        assertTrue("全线运行时长 $totalSeconds 秒明显偏离合理区间", totalSeconds in 3000..6000)
    }

    @Test
    fun `一号线交路起终点均落在站表内且方向相反`() {
        val line = beijingLine()
        val westEnd = line.stationOrder.first()
        val eastEnd = line.stationOrder.last()

        line.patterns.forEach { pattern ->
            assertNotNull("交路 ${pattern.id} 的起点应存在于站表", line.stationById(pattern.startStationId))
            assertNotNull("交路 ${pattern.id} 的终点应存在于站表", line.stationById(pattern.endStationId))
            assertTrue(
                "交路 ${pattern.id} 的起终点不应相同",
                pattern.startStationId != pattern.endStationId
            )
            assertTrue("交路 ${pattern.id} 应至少有一套服务方案", pattern.services.isNotEmpty())
        }

        val eastFull = line.patternById("east_full")!!
        assertEquals(westEnd, eastFull.startStationId)
        assertEquals(eastEnd, eastFull.endStationId)
        assertEquals("east", eastFull.directionId)
        assertEquals("开往 环球度假区", eastFull.directionLabel)
        assertTrue("全程车不应标记为区间车", !eastFull.isShortTurn)

        val westFull = line.patternById("west_full")!!
        assertEquals(eastEnd, westFull.startStationId)
        assertEquals(westEnd, westFull.endStationId)
        assertEquals("west", westFull.directionId)
        assertEquals("开往 苹果园", westFull.directionLabel)
        assertTrue("全程车不应标记为区间车", !westFull.isShortTurn)

        // 全线贯通后当前只建模双向全程车，区间车尚未落实
        assertEquals(2, line.patterns.size)
        assertEquals(1, line.patterns.count { it.directionId == "east" })
        assertEquals(1, line.patterns.count { it.directionId == "west" })
        assertEquals(0, line.patterns.count { it.isShortTurn })
    }

    @Test
    fun `一号线每套服务方案的时刻表自洽`() {
        val line = beijingLine()

        line.patterns.forEach { pattern ->
            pattern.services.forEach { service ->
                val label = "${pattern.id}/${service.serviceType}"
                assertTrue(
                    "$label 的服务类型应为 weekday 或 weekend",
                    service.serviceType == ServiceTypes.WEEKDAY || service.serviceType == ServiceTypes.WEEKEND
                )
                val first = TimeUtils.parseToSecondsOfDay(service.firstDeparture)
                val last = TimeUtils.parseToSecondsOfDay(service.lastDeparture)
                assertTrue("$label 末班不应早于首班", last >= first)
                assertTrue("$label 应给出分时段发车间隔", service.headways.isNotEmpty())

                service.headways.forEach { rule ->
                    val from = TimeUtils.parseToSecondsOfDay(rule.from)
                    val to = TimeUtils.parseToSecondsOfDay(rule.to)
                    assertTrue("$label 分时段起点应早于终点", to > from)
                    assertTrue("$label 分时段应落在首末班区间内", from >= first && to <= last)
                    assertTrue("$label 发车间隔应为正数", rule.intervalSeconds > 0)
                }
            }
        }
    }

    /**
     * stationServiceTimes 是刻意留出的"逐站替换口"，用于将来覆盖由 segments 推算的
     * offset。这里只约束其结构：一旦填入数据，键必须是已知站点与已知交路，
     * 时刻必须合法；当前为空集合时该契约同样成立。
     */
    @Test
    fun `一号线首末班车替换口只接受合法站点与交路键`() {
        val line = beijingLine()
        val stationIds = line.stations.map { it.id }.toSet()
        val patternIds = line.patterns.map { it.id }.toSet()

        line.stationServiceTimes.forEach { (stationId, byPattern) ->
            assertTrue("首末班车表出现未知站点 $stationId", stationIds.contains(stationId))
            byPattern.forEach { (patternId, time) ->
                assertTrue("$stationId 出现未知交路 $patternId", patternIds.contains(patternId))
                val first = TimeUtils.parseToSecondsOfDay(time.firstDeparture)
                assertTrue("$stationId/$patternId 首班时刻应为合法时刻", first in 0 until TimeUtils.SECONDS_PER_DAY)
                time.lastDeparture?.let { lastDeparture ->
                    val last = TimeUtils.parseToSecondsOfDay(lastDeparture)
                    assertTrue("$stationId/$patternId 末班不应早于首班", last >= first)
                }
            }
        }
    }

    @Test
    fun `城市索引指向的一号线数据文件真实存在且可解析`() {
        val indexFile = File("src/main/assets/metro/city_beijing.json")
        assumeTrue("找不到城市索引文件，跳过", indexFile.exists())

        val city = MetroJson.parseCityIndex(indexFile.readText())
        assertEquals("beijing", city.cityId)
        assertEquals("北京", city.cityName)
        assertEquals(CoordSystems.WGS84, city.coordSystem)

        val lineRef = city.lines.single { it.lineId == "bj1" }
        assertEquals("1号线", lineRef.name)
        assertEquals("#A4343A", lineRef.color)

        val lineFile = File("src/main/assets/metro/${lineRef.dataFile}")
        assertTrue("索引指向的线路数据文件应存在", lineFile.exists())

        val line = MetroJson.parseLine(lineFile.readText())
        assertEquals(lineRef.lineId, line.lineId)
        assertEquals(lineRef.name, line.lineName)
    }

    @Test
    fun `城市索引为每条线路登记了合法的主题色`() {
        val indexFile = File("src/main/assets/metro/city_beijing.json")
        assumeTrue("找不到城市索引文件，跳过", indexFile.exists())

        val city = MetroJson.parseCityIndex(indexFile.readText())
        assertTrue("应登记多条线路的主题色", city.lines.size > 1)

        city.lines.forEach { line ->
            assertNotNull(
                "${line.name} 的主题色应为合法 hex：${line.color}",
                LineVisuals.parseHexColor(line.color)
            )
        }

        // 抽两条代表色核对：2 号线是深蓝、13 号线是明黄
        assertEquals("#004B87", city.lines.single { it.lineId == "bj2" }.color)
        assertEquals("#F4DA40", city.lines.single { it.lineId == "bj13" }.color)

        // 1 号线是核心数据，必须始终收录站点
        assertEquals("line_beijing_1.json", city.lines.single { it.lineId == "bj1" }.dataFile)

        // 但线路是逐步收录的：只有登记了 dataFile 的线路才必须能加载。
        // 这里不写死「除 bj1 外都为空」，否则每加一条线路都要改测试。
        city.lines.filter { !it.dataFile.isNullOrBlank() }.forEach { ref ->
            val dataFile = File("src/main/assets/metro/${ref.dataFile}")
            assertTrue("${ref.name} 指向的 ${ref.dataFile} 应存在", dataFile.exists())

            val line = MetroJson.parseLine(dataFile.readText())
            assertEquals("${ref.name} 的 lineId 应与索引一致", ref.lineId, line.lineId)
            assertEquals("${ref.name} 的线路名应与索引一致", ref.name, line.lineName)
            assertEquals("${ref.name} 的主题色应与索引一致", ref.color, line.color)
        }
    }

    @Test
    fun `自定义线路列表可往返序列化`() {
        val original = listOf(MetroLine(cityId = "custom_city", cityName = "天津", lineId = "custom_1", lineName = "测试线"))
        val decoded = MetroJson.parseLines(MetroJson.encodeLines(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `北京城市索引已收录当前全部二十八条线路且线路携带城市标签`() {
        val indexFile = File("src/main/assets/metro/city_beijing.json")
        assumeTrue("找不到城市索引，跳过", indexFile.exists())
        val city = MetroJson.parseCityIndex(indexFile.readText())

        assertEquals(28, city.lines.size)
        assertEquals(28, city.lines.count { !it.dataFile.isNullOrBlank() })
        city.lines.forEach { ref ->
            val line = MetroJson.parseLine(File("src/main/assets/metro/${ref.dataFile}").readText())
            assertEquals("${ref.name} 的城市 id", city.cityId, line.cityId)
            assertEquals("${ref.name} 的城市标签", city.cityName, line.cityName)
            assertTrue("${ref.name} 的站点应有有效经纬度", line.stations.all { it.lat != 0.0 && it.lng != 0.0 })
        }
    }

    /**
     * 已收录线路的数据自洽性。
     *
     * 与 tools/line-pipeline/Test-LineData.ps1 同源：脚本负责在登记前拦下坏数据，
     * 这里保证已经提交进仓库的数据不会悄悄退化。断言按「每条已收录线路」遍历，
     * 新增线路时自动纳入，无需改动本测试。
     */
    @Test
    fun `城市索引中每条已收录线路的站表与线段自洽`() {
        val indexFile = File("src/main/assets/metro/city_beijing.json")
        assumeTrue("找不到城市索引文件，跳过", indexFile.exists())

        val city = MetroJson.parseCityIndex(indexFile.readText())
        val available = city.lines.mapNotNull { ref -> ref.dataFile?.let { ref to it } }
        assertTrue("应至少有一条线路已收录站点数据", available.isNotEmpty())

        available.forEach { (ref, dataFile) ->
            val file = File("src/main/assets/metro/$dataFile")
            assertTrue("${ref.name} 的数据文件 $dataFile 应存在", file.exists())
            val line = MetroJson.parseLine(file.readText())
            val label = ref.name

            assertEquals(
                "$label 站表应与站序逐一对应",
                line.stationOrder,
                line.stations.map { it.id }
            )
            assertEquals(
                "$label 站序不应有重复",
                line.stationOrder.size,
                line.stationOrder.toSet().size
            )

            line.stations.forEach { station ->
                val stationLabel = "$label ${station.id}"
                assertTrue("$stationLabel 名称不应为空", station.name.isNotBlank())
                assertTrue("$stationLabel 应至少有一个别名", station.aliases.isNotEmpty())
                assertTrue(
                    "$stationLabel 的别名应含站名本身或「站名+站」",
                    station.aliases.contains(station.name) ||
                        station.aliases.contains("${station.name}站")
                )
            }

            // 每一对相邻站都要有线段，否则 ArrivalEstimator 累加 offset 时会静默失败
            val segmentKeys = line.segments.map { "${it.from}->${it.to}" }.toSet()
            line.stationOrder.zipWithNext().forEachIndexed { index, (from, to) ->
                assertTrue("$label 缺少第 ${index + 1} 段 $from->$to", segmentKeys.contains("$from->$to"))
            }
            line.segments.forEach { segment ->
                assertTrue(
                    "$label 线段 ${segment.from}->${segment.to} 的时长应为正数",
                    segment.runSeconds > 0
                )
            }

            line.patterns.forEach { pattern ->
                assertNotNull(
                    "$label 交路 ${pattern.id} 的起点应存在于站表",
                    line.stationById(pattern.startStationId)
                )
                assertNotNull(
                    "$label 交路 ${pattern.id} 的终点应存在于站表",
                    line.stationById(pattern.endStationId)
                )
                assertTrue(
                    "$label 交路 ${pattern.id} 的起终点不应相同",
                    pattern.startStationId != pattern.endStationId
                )
            }

            // 首末班替换口与真实发车序列只能挂在已知站点与已知交路下
            val stationIds = line.stations.map { it.id }.toSet()
            val patternIds = line.patterns.map { it.id }.toSet()
            line.stationServiceTimes.forEach { (stationId, byPattern) ->
                assertTrue("$label 的首末班表出现未知站点 $stationId", stationIds.contains(stationId))
                byPattern.forEach { (patternId, _) ->
                    assertTrue("$label 的 $stationId 出现未知交路 $patternId", patternIds.contains(patternId))
                }
            }
            line.exactDepartures.forEach { (stationId, byPattern) ->
                assertTrue("$label 的真实发车序列出现未知站点 $stationId", stationIds.contains(stationId))
                byPattern.forEach { (patternId, _) ->
                    assertTrue("$label 的 $stationId 出现未知交路 $patternId", patternIds.contains(patternId))
                }
            }
        }
    }

    @Test
    fun `数据模型默认值契约保持稳定`() {
        // 城市索引默认使用 wgs84、schemaVersion=1，且可以没有线路
        val city = CityIndex(cityId = "demo", cityName = "演示")
        assertEquals(1, city.schemaVersion)
        assertEquals(CoordSystems.WGS84, city.coordSystem)
        assertTrue("未配置线路时应为空", city.lines.isEmpty())

        // 线路引用允许不指定颜色
        val lineRef = LineRef(lineId = "demo_1", name = "1号线", dataFile = "line_demo_1.json")
        assertNull("未指定颜色时应保持 null", lineRef.color)

        // 站点默认不带别名
        val station = Station(id = "s1", name = "甲站", lat = 1.0, lng = 2.0)
        assertTrue("默认不应有别名", station.aliases.isEmpty())

        // 首末班车的末班时刻可缺省
        val serviceTime = StationServiceTime(firstDeparture = "06:00")
        assertNull("未提供末班时应保持 null", serviceTime.lastDeparture)

        // 交路缺省方向归入 default 组，且默认为全程车、无服务方案
        val pattern = Pattern(id = "p1", name = "全程车", startStationId = "s1", endStationId = "s2")
        assertEquals(DEFAULT_DIRECTION_ID, pattern.directionId)
        assertTrue("默认不应是区间车", !pattern.isShortTurn)
        assertTrue("默认不应带服务方案", pattern.services.isEmpty())
        assertNull(pattern.directionLabel)

        // 线路默认未标记待核对，所有集合为空
        val line = MetroLine(lineId = "demo", lineName = "演示线")
        assertEquals(1, line.schemaVersion)
        assertEquals(CoordSystems.WGS84, line.coordSystem)
        assertTrue("默认不应标记待核对", !line.needsReview)
        assertTrue(line.stations.isEmpty())
        assertTrue(line.stationServiceTimes.isEmpty())
        assertTrue(line.segments.isEmpty())
        assertTrue(line.patterns.isEmpty())
        assertTrue(line.exactDepartures.isEmpty())
        assertNull(line.updatedAt)
        assertNull(line.dataSource)
        assertNull(line.cityId)
        assertNull(line.cityName)
    }

    // ------------------------------------------------------------------
    // 内置北京一号线：全线数据的到站方向回归
    // ------------------------------------------------------------------

    /**
     * 内置全线数据的到站方向回归：终点站不返回折返方向的列车，中间站两个方向都出现。
     *
     * 一号线的 stationServiceTimes 与 segments 都已填满，ArrivalEstimator 的 offset 实际
     * 由前者反推（segments 被遮蔽）。因此本用例顺带锁定「offset 确实取自 stationServiceTimes」。
     *
     * stationServiceTimes 是整分钟粒度的估算值，凡是跨全线的时长只给合理上下界，
     * 不把估算值锁成精确契约。
     */
    @Test
    fun `一号线两端站只返回可乘坐方向中间站两个方向都返回`() {
        val line = beijingLine()
        val estimator = ArrivalEstimator(line)
        val weekdayMorning = TimeUtils.parseToSecondsOfDay("08:00")

        // 苹果园是 west_full 的终点：列车在此清客折返，西行对这一站没有意义
        val westEnd = estimator.nextArrivalsByDirection("bj1_01", ServiceTypes.WEEKDAY, weekdayMorning)
        assertEquals("西端站只应剩东行一组", listOf("east"), westEnd.map { it.directionId })
        assertEquals("开往 环球度假区", westEnd.single().directionLabel)
        assertEquals("bj1_36", westEnd.single().terminalStationId)
        assertEquals("环球度假区", westEnd.single().terminalStationName)
        assertTrue("东行应返回 east_full 的班次", westEnd.single().arrivals.all { it.patternId == "east_full" })

        // 环球度假区是 east_full 的终点：同理只剩西行
        val eastEnd = estimator.nextArrivalsByDirection("bj1_36", ServiceTypes.WEEKDAY, weekdayMorning)
        assertEquals("东端站只应剩西行一组", listOf("west"), eastEnd.map { it.directionId })
        assertEquals("开往 苹果园", eastEnd.single().directionLabel)
        assertEquals("bj1_01", eastEnd.single().terminalStationId)
        assertEquals("苹果园", eastEnd.single().terminalStationName)
        assertTrue("西行应返回 west_full 的班次", eastEnd.single().arrivals.all { it.patternId == "west_full" })

        // 中间站两个方向都坐得上
        val middle = estimator.nextArrivalsByDirection("bj1_13", ServiceTypes.WEEKDAY, weekdayMorning)
        assertEquals(
            "西单应同时看到两个方向",
            setOf("east", "west"),
            middle.map { it.directionId }.toSet()
        )
        assertEquals("开往 环球度假区", middle.first { it.directionId == "east" }.directionLabel)
        assertEquals("开往 苹果园", middle.first { it.directionId == "west" }.directionLabel)
        middle.forEach { group ->
            assertTrue("${group.directionId} 方向应至少有一班", group.arrivals.isNotEmpty())
            assertTrue(
                "${group.directionId} 方向不应超过默认条数上限",
                group.arrivals.size <= ArrivalEstimator.DEFAULT_LIMIT
            )
            val times = group.arrivals.map { it.arrivalSecondsOfDay }
            assertEquals("${group.directionId} 方向的班次应按到站时刻升序", times.sorted(), times)
            group.arrivals.forEach { arrival ->
                assertTrue(
                    "到站时刻 ${arrival.arrivalSecondsOfDay} 早于当前时刻 $weekdayMorning 超出了停站窗口",
                    arrival.arrivalSecondsOfDay >= weekdayMorning - ArrivalEstimator.DEFAULT_DWELL_SECONDS
                )
            }
        }

        // 全线首班（东行）：既用于校验 offset 来源，也用于推导全程用时
        val eastOriginFirst = TimeUtils.parseToSecondsOfDay(
            line.patternById("east_full")!!
                .services.first { it.serviceType == ServiceTypes.WEEKDAY }
                .firstDeparture
        )

        // offset 应取自 stationServiceTimes 而非 segments：西单东行首班 05:35 − 始发 05:10 = 25 分钟。
        // 期望值由数据推导而非硬编码；同时算出 segments 口径的兜底值写进失败信息——
        // 一号线这两个口径分别是 1500 秒与 1535 秒，并不相等，因此该断言具备区分度。
        val publishedOffset = TimeUtils.parseToSecondsOfDay(
            line.stationServiceTimes.getValue("bj1_13").getValue("east_full").firstDeparture
        ) - eastOriginFirst
        val segmentsOffset = line.stationOrder
            .zipWithNext { from, to -> from to to }
            .takeWhile { it.first != "bj1_13" }
            .sumOf { (from, to) -> line.segments.first { it.from == from && it.to == to }.runSeconds }
        val eastOffsetAtMiddle = middle.first { it.directionId == "east" }.arrivals.first().offsetSeconds
        assertEquals(
            "西单东行 offset 应取自 stationServiceTimes（segments 口径为 $segmentsOffset 秒）",
            publishedOffset,
            eastOffsetAtMiddle
        )

        // 全程用时由首末班反推：东端首班 06:26 − 西端始发 05:10 ≈ 76 分钟。
        // 估算值只给合理上下界，不锁成精确契约。
        val eastTerminalFirst = TimeUtils.parseToSecondsOfDay(
            line.stationServiceTimes.getValue("bj1_36").getValue("east_full").firstDeparture
        )
        val fullRunMinutes = (eastTerminalFirst - eastOriginFirst) / 60.0
        assertTrue(
            "全线推算约 76 分钟，实际 $fullRunMinutes 分钟偏离合理区间",
            fullRunMinutes in 74.0..78.0
        )
    }
}
