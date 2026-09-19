package com.metronearby.domain

import com.metronearby.data.MetroJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class BeijingExpansionTransferTest {
    private val city = MetroJson.parseCityIndex(File("src/main/assets/metro/city_beijing.json").readText())
    private val lines = city.lines.mapNotNull { ref ->
        ref.dataFile?.let { MetroJson.parseLine(File("src/main/assets/metro/$it").readText()) }
    }

    @Test
    fun `平安里匹配四号线六号线和十九号线`() {
        assertEquals(setOf("bj4", "bj6", "bj19"), TransferStationResolver.match(lines, "平安里").map { it.line.lineId }.toSet())
    }

    @Test
    fun `南锣鼓巷匹配六号线和八号线`() {
        assertEquals(setOf("bj6", "bj8"), TransferStationResolver.match(lines, "南锣鼓巷").map { it.line.lineId }.toSet())
    }

    @Test
    fun `磁器口匹配五号线和七号线`() {
        assertEquals(setOf("bj5", "bj7"), TransferStationResolver.match(lines, "磁器口").map { it.line.lineId }.toSet())
    }

    @Test
    fun `通运门在通州北关与北运河西之间`() {
        val names = lines.single { it.lineId == "bj6" }.stations.map { it.name }
        val index = names.indexOf("通运门")
        assertEquals(listOf("通州北关", "通运门", "北运河西"), names.subList(index - 1, index + 2))
    }

    @Test
    fun `六号线新终点潞阳可搜索`() {
        assertEquals(listOf("潞阳"), StationSearch.matchDistinctByName(lines.flatMap { it.stations }, "潞阳").map { it.name })
    }

    @Test
    fun `东四十条匹配二号线和三号线`() {
        assertEquals(setOf("bj2", "bj3"), TransferStationResolver.match(lines, "东四十条").map { it.line.lineId }.toSet())
    }

    @Test
    fun `团结湖匹配三号线和十号线`() {
        assertEquals(setOf("bj3", "bj10"), TransferStationResolver.match(lines, "团结湖").map { it.line.lineId }.toSet())
    }

    @Test
    fun `北京西站匹配七号线和九号线`() {
        assertEquals(setOf("bj7", "bj9"), TransferStationResolver.match(lines, "北京西站").map { it.line.lineId }.toSet())
    }

    @Test
    fun `军事博物馆匹配一号线和九号线`() {
        assertEquals(setOf("bj1", "bj9"), TransferStationResolver.match(lines, "军事博物馆").map { it.line.lineId }.toSet())
    }
}
