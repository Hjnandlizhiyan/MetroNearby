package com.metronearby.domain

import com.metronearby.data.MetroJson
import org.junit.Assert.*
import org.junit.Test

class StationLookupTest {
    private fun station(name: String, city: String = "北京", aliases: List<String> = emptyList()) =
        OfflineRoutePlanner.StationChoice("$city|$name", city, name, listOf("1号线"), aliases, listOf("#A4343A"))
    private val choices = listOf(station("古城"), station("复兴门"), station("军事博物馆", aliases = listOf("军博")))
    private fun search(query: String) = StationLookup.search(choices, query)

    @Test fun chineseSearch() { assertEquals("古城", search("古城").first().station.stationName) }
    @Test fun fullPinyin() { assertEquals("古城", search("gucheng").single().station.stationName) }
    @Test fun initials() { assertEquals("复兴门", search("fxm").single().station.stationName) }
    @Test fun uppercaseAndWhitespace() { assertEquals("古城", search(" GU CHENG ").single().station.stationName) }
    @Test fun accentedPinyin() { assertEquals("古城", search("gǔchéng").single().station.stationName) }
    @Test fun fullwidthLatin() { assertEquals("古城", search("ｇｕｃｈｅｎｇ").single().station.stationName) }
    @Test fun alias() { assertEquals("军事博物馆", search("军博").single().station.stationName) }
    @Test fun aliasPinyin() { assertEquals("军事博物馆", search("junbo").single().station.stationName) }
    @Test fun lineSearch() { assertEquals(3, search("1号线").size) }
    @Test fun blankHomeSearch() { assertTrue(search(" ").isEmpty()) }
    @Test fun blankPickerListsAll() { assertEquals(choices.size, StationLookup.search(choices, "", true).size) }
    @Test fun punctuationDoesNotListAll() { assertTrue(StationLookup.search(choices, "---", true).isEmpty()) }
    @Test fun exactBeforePrefix() {
        assertEquals("古城", StationLookup.search(listOf(station("古城路"), station("古城")), "古城").first().station.stationName)
    }
    @Test fun chineseTypoIsSuggestion() { assertTrue(search("复新门").single().suggestion) }
    @Test fun latinTypoIsSuggestion() { assertTrue(search("fuxinmen").single().suggestion) }
    @Test fun noFuzzyWhenDirectExists() {
        val hits = StationLookup.search(listOf(station("复新门"), station("复兴门")), "复新门")
        assertEquals(listOf("复新门"), hits.map { it.station.stationName })
    }
    @Test fun shortChineseNotFuzzy() { assertTrue(search("谷城").isEmpty()) }
    @Test fun initialsNotFuzzy() { assertTrue(search("fxn").isEmpty()) }
    @Test fun twoTyposRejected() { assertTrue(search("复新们").isEmpty()) }
    @Test fun noAutomaticSelection() { assertEquals("复兴门", search("复新门").single().station.stationName) }
    @Test fun sameNameDifferentCitiesRemainSeparate() {
        assertEquals(2, StationLookup.search(listOf(station("古城"), station("古城", "其他市")), "gc").size)
    }
    @Test fun customNamesSupportChineseWithoutPinyin() {
        assertEquals("自建小站", StationLookup.search(listOf(station("自建小站")), "小站").single().station.stationName)
    }
    @Test fun polyphonicChangchun() {
        assertEquals("长椿街", StationLookup.search(listOf(station("长椿街")), "changchunjie").single().station.stationName)
    }
    @Test fun polyphonicShichahai() {
        assertEquals("什刹海", StationLookup.search(listOf(station("什刹海")), "shichahai").single().station.stationName)
    }
    @Test fun polyphonicMajiapu() {
        assertEquals("马家堡", StationLookup.search(listOf(station("马家堡")), "majiapu").single().station.stationName)
    }
    @Test fun oneInsertion() { assertTrue(StationLookup.oneEditAway("abcd", "abxcd")) }
    @Test fun oneDeletion() { assertTrue(StationLookup.oneEditAway("abxcd", "abcd")) }
    @Test fun sameTextIsNotTypo() { assertFalse(StationLookup.oneEditAway("abcd", "abcd")) }
    @Test fun transferAliasesMergedWithinCity() {
        val line = MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!
            .bufferedReader().use { it.readText() }).copy(cityId = "beijing", cityName = "北京")
        val other = line.copy(lineId = "other", lineName = "2号线",
            stations = line.stations.map { it.copy(aliases = listOf("新增别名")) })
        val merged = OfflineRoutePlanner.stationChoices(listOf(line, other))
        assertEquals(line.stations.size, merged.size)
        assertTrue(merged.all { "新增别名" in it.aliases && it.lineNames.size == 2 })
    }
    @Test fun differentCityChoicesNotMerged() {
        val line = MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!
            .bufferedReader().use { it.readText() }).copy(cityId = "beijing")
        assertEquals(line.stations.size * 2, OfflineRoutePlanner.stationChoices(
            listOf(line, line.copy(lineId = "other", cityId = "other"))).size)
    }
}
