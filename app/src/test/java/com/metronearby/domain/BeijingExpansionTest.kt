package com.metronearby.domain

import com.metronearby.data.MetroJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BeijingExpansionTest(
    private val number: Int,
    private val count: Int,
    private val firstName: String,
    private val lastName: String,
    private val forwardFirst: String,
    private val forwardLast: String,
    private val reverseFirst: String,
    private val reverseLast: String
) {
    private val line = MetroJson.parseLine(File("src/main/assets/metro/line_beijing_$number.json").readText())
    private val estimator = ArrivalEstimator(line)

    @Test
    fun `收录站数与核对的全程站表一致`() {
        assertEquals(count, line.stations.size)
    }

    @Test
    fun `两个始发站的服务窗口与官方全程首末班一致`() {
        val expected = mapOf(firstName to (forwardFirst to forwardLast), lastName to (reverseFirst to reverseLast))
        for ((name, times) in expected) {
            val station = line.stations.single { it.name == name }
            for (serviceType in listOf("weekday", "weekend")) {
                val window = estimator.serviceWindow(station.id, serviceType)!!
                assertEquals(times.first to times.second,
                    TimeUtils.formatSecondsOfDay(window.firstSecondsOfDay) to TimeUtils.formatSecondsOfDay(window.lastSecondsOfDay))
            }
        }
    }

    @Test
    fun `所有中间站均可查询双向班次`() {
        for (station in line.stations.drop(1).dropLast(1)) {
            val directions = estimator.nextArrivalsByDirection(station.id, "weekday", 12 * 3600)
            assertEquals(station.name, setOf("forward", "reverse"), directions.map { it.directionId }.toSet())
        }
    }

    @Test
    fun `两个端点只显示离站方向`() {
        val first = estimator.nextArrivalsByDirection(line.stations.first().id, "weekday", 12 * 3600)
        val last = estimator.nextArrivalsByDirection(line.stations.last().id, "weekday", 12 * 3600)
        assertEquals(listOf(lastName) to listOf(firstName),
            first.map { it.terminalStationName } to last.map { it.terminalStationName })
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "北京{0}号线")
        fun lines(): List<Array<Any>> = listOf(
            arrayOf(3, 10, "东四十条", "东坝北", "05:35", "23:25", "05:05", "22:50"),
            arrayOf(4, 35, "安河桥北", "天宫院", "04:58", "22:35", "05:05", "22:45"),
            arrayOf(5, 23, "天通苑北", "宋家庄", "04:59", "22:47", "05:19", "23:10"),
            arrayOf(6, 36, "金安桥", "潞阳", "05:06", "22:24", "05:29", "22:44"),
            arrayOf(7, 30, "北京西站", "环球度假区", "05:29", "22:59", "05:09", "22:59"),
            arrayOf(8, 35, "朱辛庄", "瀛海", "05:09", "22:40", "05:06", "23:01"),
            arrayOf(9, 13, "国家图书馆", "郭公庄", "05:38", "23:18", "04:59", "22:39"),
            arrayOf(13, 17, "东直门", "西直门", "05:34", "22:49", "05:34", "22:41")
        )
    }
}
