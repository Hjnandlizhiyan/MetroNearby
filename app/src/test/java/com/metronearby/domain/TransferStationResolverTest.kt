package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferStationResolverTest {

    private fun line(lineId: String, vararg stations: Station) =
        MetroLine(lineId = lineId, lineName = lineId, stations = stations.toList())

    private fun station(id: String, name: String, vararg aliases: String) =
        Station(id = id, name = name, lat = 0.0, lng = 0.0, aliases = aliases.toList())

    /** 1 号线与 2 号线在复兴门、建国门换乘，另外各有自己的独立车站。 */
    private val bj1 = line(
        "bj1",
        station("bj1_12", "复兴门", "复兴门站"),
        station("bj1_18", "建国门", "建国门站"),
        station("bj1_13", "西单", "西单站")
    )

    private val bj2 = line(
        "bj2",
        station("bj2_16", "复兴门", "复兴门站"),
        station("bj2_09", "建国门", "建国门站"),
        station("bj2_14", "宣武门", "宣武门站")
    )

    private val lines = listOf(bj1, bj2)

    private fun matches(name: String) =
        TransferStationResolver.match(lines, name).map { "${it.line.lineId}:${it.stationId}" }

    @Test
    fun `换乘站同时命中两条线且各取本线的站点 id`() {
        assertEquals(listOf("bj1:bj1_12", "bj2:bj2_16"), matches("复兴门"))
    }

    @Test
    fun `又一个换乘站同样命中两条线`() {
        assertEquals(listOf("bj1:bj1_18", "bj2:bj2_09"), matches("建国门"))
    }

    @Test
    fun `非换乘站只命中它所属的那条线`() {
        assertEquals(listOf("bj1:bj1_13"), matches("西单"))
        assertEquals(listOf("bj2:bj2_14"), matches("宣武门"))
    }

    /**
     * 站名带「站」后缀是 OSM 数据的常见写法，两条线的别名都是「复兴门站」，
     * 用带后缀的名字查询必须仍然命中，否则换乘会被漏配。
     */
    @Test
    fun `带站后缀的名字与不带后缀视为同一站`() {
        assertEquals(listOf("bj1:bj1_12", "bj2:bj2_16"), matches("复兴门站"))
    }

    @Test
    fun `首尾空白会被忽略`() {
        assertEquals(listOf("bj1:bj1_12", "bj2:bj2_16"), matches("  复兴门  "))
    }

    /** 只按站名匹配，不做子串匹配——「门」这种片段不该把一堆无关车站拉进来。 */
    @Test
    fun `不做子串匹配`() {
        assertTrue(TransferStationResolver.match(lines, "门").isEmpty())
    }

    @Test
    fun `线路里没有该站时不出现在结果中`() {
        val onlyBj1 = TransferStationResolver.match(listOf(bj1), "宣武门")
        assertTrue(onlyBj1.isEmpty())
    }

    @Test
    fun `空站名返回空列表`() {
        assertTrue(TransferStationResolver.match(lines, "").isEmpty())
        assertTrue(TransferStationResolver.match(lines, "   ").isEmpty())
    }

    @Test
    fun `空线路列表返回空列表`() {
        assertTrue(TransferStationResolver.match(emptyList(), "复兴门").isEmpty())
    }

    /** 结果顺序跟随传入的线路顺序，调用方据此把「我的线路」置顶。 */
    @Test
    fun `结果顺序与传入线路顺序一致`() {
        val reversed = TransferStationResolver.match(listOf(bj2, bj1), "复兴门")
            .map { "${it.line.lineId}:${it.stationId}" }
        assertEquals(listOf("bj2:bj2_16", "bj1:bj1_12"), reversed)
    }

    @Test
    fun `归一化只去掉末尾的站字`() {
        assertEquals("复兴门", TransferStationResolver.normalize(" 复兴门站 "))
        assertEquals("复兴门", TransferStationResolver.normalize("复兴门"))
        assertEquals("北京", TransferStationResolver.normalize("北京站"))
        assertEquals("站前", TransferStationResolver.normalize("站前"))
    }
}