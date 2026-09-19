package com.metronearby.location

import com.metronearby.data.MetroJson
import com.metronearby.domain.TransferStationResolver
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeijingNewLineLocationTest {
    private val city = MetroJson.parseCityIndex(File("src/main/assets/metro/city_beijing.json").readText())
    private val lines = city.lines.mapNotNull { ref ->
        ref.dataFile?.let { MetroJson.parseLine(File("src/main/assets/metro/$it").readText()) }
    }
    private val newLines = lines.filter { it.lineId in setOf("bj3", "bj9") }
    private val allStations = lines.flatMap { it.stations }

    @Test
    fun `三号线和九号线已在城市索引登记数据文件`() {
        assertEquals(
            mapOf("bj3" to "line_beijing_3.json", "bj9" to "line_beijing_9.json"),
            city.lines.filter { it.lineId in setOf("bj3", "bj9") }.associate { it.lineId to it.dataFile }
        )
    }

    @Test
    fun `两条线所有车站都有北京范围内的经纬度`() {
        assertEquals(23, newLines.sumOf { it.stations.size })
        newLines.flatMap { it.stations }.forEach { station ->
            assertTrue(station.name, station.lat in 39.7..40.2)
            assertTrue(station.name, station.lng in 116.0..116.9)
        }
    }

    @Test
    fun `定位在新增车站坐标时能识别同名最近站`() {
        newLines.flatMap { it.stations }.forEach { station ->
            val nearest = NearestStationFinder(allStations).findNearest(station.lat, station.lng, limit = 1).single()
            assertEquals(
                station.name,
                TransferStationResolver.normalize(station.name),
                TransferStationResolver.normalize(nearest.station.name)
            )
            assertTrue(station.name, nearest.distanceMeters < 1.0)
        }
    }

    @Test
    fun `三号线与九号线搜索范围包含全部新增站`() {
        val names = newLines.flatMap { it.stations }.map { it.name }.toSet()
        assertTrue("东坝北" in names)
        assertTrue("朝阳站" in names)
        assertTrue("丰台科技园" in names)
        assertTrue("国家图书馆" in names)
    }
}
