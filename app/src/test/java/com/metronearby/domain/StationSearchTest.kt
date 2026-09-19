package com.metronearby.domain

import com.metronearby.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationSearchTest {

    private val stations = listOf(
        Station(id = "s1", name = "西单", lat = 0.0, lng = 0.0, aliases = listOf("西单站")),
        Station(id = "s2", name = "天安门西", lat = 0.0, lng = 0.0, aliases = listOf("天安门西站")),
        Station(
            id = "s3",
            name = "天安门东",
            lat = 0.0,
            lng = 0.0,
            aliases = listOf("天安门东站", "Tiananmen East")
        )
    )

    private fun idsOf(keyword: String) = StationSearch.match(stations, keyword).map { it.id }

    /**
     * 空关键字代表「用户没有在搜索」，此时 UI 要回落到定位推荐的最近站，
     * 所以这里必须返回空列表，而不是把整条线的站名都倒出来。
     */
    @Test
    fun `空关键字返回空列表而不是全部车站`() {
        assertTrue(StationSearch.match(stations, "").isEmpty())
    }

    @Test
    fun `纯空白关键字也返回空列表`() {
        assertTrue(StationSearch.match(stations, "   ").isEmpty())
    }

    @Test
    fun `完整站名精确命中一站`() {
        assertEquals(listOf("s2"), idsOf("天安门西"))
    }

    @Test
    fun `部分站名可命中多站`() {
        assertEquals(listOf("s2", "s3"), idsOf("天安门"))
    }

    @Test
    fun `单个汉字命中所有包含它的站`() {
        assertEquals(listOf("s1", "s2"), idsOf("西"))
    }

    @Test
    fun `别名同样参与匹配`() {
        assertEquals(listOf("s1"), idsOf("西单站"))
    }

    @Test
    fun `英文别名忽略大小写`() {
        assertEquals(listOf("s3"), idsOf("tiananmen east"))
    }

    @Test
    fun `关键字前后空格会被忽略`() {
        assertEquals(listOf("s1"), idsOf("  西单  "))
    }

    @Test
    fun `没有匹配时返回空列表`() {
        assertTrue(StationSearch.match(stations, "上海").isEmpty())
    }

    @Test
    fun `空车站列表不会出错`() {
        assertTrue(StationSearch.match(emptyList(), "西单").isEmpty())
    }

    /**
     * 换乘站在每条线路里各有一条记录（1 号线复兴门 / 2 号线复兴门），
     * 跨线路一起搜时必须合并成一行，否则候选列表会出现重复的「复兴门」。
     */
    @Test
    fun `跨线路同名站只保留先出现的一条`() {
        val multiLine = listOf(
            Station(id = "bj1_12", name = "复兴门", lat = 0.0, lng = 0.0, aliases = listOf("复兴门站")),
            Station(id = "bj2_16", name = "复兴门", lat = 0.0, lng = 0.0, aliases = listOf("复兴门站"))
        )
        assertEquals(
            listOf("bj1_12"),
            StationSearch.matchDistinctByName(multiLine, "复兴门").map { it.id }
        )
    }

    @Test
    fun `带站后缀的同名站在去重时视为同一站`() {
        val multiLine = listOf(
            Station(id = "a", name = "复兴门", lat = 0.0, lng = 0.0),
            Station(id = "b", name = "复兴门站", lat = 0.0, lng = 0.0)
        )
        assertEquals(
            listOf("a"),
            StationSearch.matchDistinctByName(multiLine, "复兴门").map { it.id }
        )
    }

    @Test
    fun `去重版本保持空关键字返回空列表的语义`() {
        assertTrue(StationSearch.matchDistinctByName(stations, "").isEmpty())
    }

    @Test
    fun `不同站名的匹配结果不受去重影响`() {
        assertEquals(
            listOf("s2", "s3"),
            StationSearch.matchDistinctByName(stations, "天安门").map { it.id }
        )
    }
}