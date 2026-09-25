package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Segment
import com.metronearby.data.model.Station
import com.metronearby.data.source.JourneyCodec
import org.junit.Assert.*
import org.junit.Test

class TravelToolsTest {
    private val saved = SavedJourney("id", "上班", "beijing|a", "beijing|b", commute = true)
    private fun line(id: String = "one", city: String = "beijing") = MetroLine(
        lineId = id, lineName = id, cityId = city, cityName = city, color = "#123456",
        stationOrder = listOf("a", "b"),
        stations = listOf(Station("a", "甲", 39.9, 116.3), Station("b", "乙", 39.91, 116.3)),
        segments = listOf(Segment("a", "b", 90))
    )
    @Test fun stationWithinAccuracyHasNoFalseDirection() {
        assertEquals("当前位置附近", StationRadar.positionLabel(3.0, 90.0, 5f))
    }
    @Test fun stationOutsideAccuracyHasDirection() {
        assertEquals("东", StationRadar.positionLabel(30.0, 90.0, 5f))
    }
    @Test fun northBearingIsZero() { assertEquals(0.0, StationRadar.bearing(0.0,0.0,1.0,0.0),0.001) }
    @Test fun eastBearingIsNinety() { assertEquals(90.0, StationRadar.bearing(0.0,0.0,0.0,1.0),0.001) }
    @Test fun southBearingIs180() { assertEquals(180.0, StationRadar.bearing(0.0,0.0,-1.0,0.0),0.001) }
    @Test fun westBearingIs270() { assertEquals(270.0, StationRadar.bearing(0.0,0.0,0.0,-1.0),0.001) }
    @Test fun northWrapsAcrossZero() { assertEquals("北", StationRadar.direction(359.0)) }
    @Test fun diagonalDirection() { assertEquals("东南", StationRadar.direction(135.0)) }
    @Test fun radarMergesTransferLines() {
        val result = StationRadar.find(listOf(line(),line("two")),UserLocation(39.9,116.3,capturedAtMillis=1))
        assertEquals(listOf("one","two"), result.first().lineNames)
    }
    @Test fun radarSortsByDistance() {
        val result = StationRadar.find(listOf(line()),UserLocation(39.91,116.3,capturedAtMillis=1))
        assertEquals("乙",result.first().name)
    }
    @Test fun radarSeparatesCities() {
        val result = StationRadar.find(listOf(line(),line("two","other")),UserLocation(39.9,116.3,capturedAtMillis=1))
        assertEquals(4,result.size)
    }
    @Test fun zeroRadarLimitIsEmpty() {
        assertTrue(StationRadar.find(listOf(line()),UserLocation(39.9,116.3,capturedAtMillis=1),0).isEmpty())
    }
    @Test fun reverseSwapsEndpoints() { assertEquals(saved.originKey,JourneyPolicy.reversed(saved).destinationKey) }
    @Test fun reversePreservesPreference() {
        val item=saved.copy(preference="FEWER_STOPS")
        assertEquals("FEWER_STOPS",JourneyPolicy.reversed(item).preference)
    }
    @Test fun updateKeepsOnlyOneId() {
        assertEquals(listOf(saved.copy(name="回家")),JourneyPolicy.upsert(listOf(saved),saved.copy(name="回家")))
    }
    @Test fun emptyNameIsInvalid() { assertFalse(JourneyPolicy.valid(saved.copy(name=" "))) }
    @Test fun sameEndpointsAreInvalid() { assertFalse(JourneyPolicy.valid(saved.copy(destinationKey=saved.originKey))) }
    @Test fun unknownPreferenceFallsBack() {
        assertEquals(OfflineRoutePlanner.Preference.FEWER_TRANSFERS,JourneyPolicy.preference(saved.copy(preference="future")))
    }
    @Test fun journeysSurviveSerialization() { assertEquals(listOf(saved),JourneyCodec.decode(JourneyCodec.encode(listOf(saved)))) }
    @Test fun damagedSavedJsonIsSafe() { assertTrue(JourneyCodec.decode("broken").isEmpty()) }
    @Test fun duplicateIdsAreRemovedOnLoad() {
        val single=JourneyCodec.encode(listOf(saved)).trim().removePrefix("[").removeSuffix("]")
        assertEquals(1,JourneyCodec.decode("[$single,$single]").size)
    }
    @Test fun topologyDoesNotIncludeTimetables() {
        val result = CustomLineBuilder.buildTopology("a","北京","测试","#123456",
            listOf(CustomStationInput("甲",39.9,116.3),CustomStationInput("乙",39.91,116.3)))
            as CustomLineBuilder.Result.Built
        assertTrue(result.line.patterns.all { it.services.isEmpty() })
    }
    private val route = OfflineRoutePlanner.RouteResult("甲","丙",listOf(
        OfflineRoutePlanner.RouteLeg("one","1号线","#123456","甲","换乘",listOf("甲","中间","换乘")),
        OfflineRoutePlanner.RouteLeg("two","2号线","#234567","换乘","丙",listOf("换乘","丙"))
    ))
    @Test fun directionUsesNextStationRatherThanDestination() { assertEquals("中间",TravelGuide.steps(route).first().nextStation) }
    @Test fun transferNamesNextLine() { assertEquals("2号线",TravelGuide.steps(route).first().transferTo) }
    @Test fun lastLegHasNoTransfer() { assertNull(TravelGuide.steps(route).last().transferTo) }
    @Test fun cardIncludesTransferInstruction() { assertTrue(TravelGuide.cardLines(route).contains("在 换乘 换乘 2号线")) }
    @Test fun cardIncludesTotalStops() { assertTrue(TravelGuide.cardLines(route).contains("共 3 站 · 换乘 1 次")) }
    @Test fun savedJourneyCanReplanBothWays() {
        val l=line()
        val keys=OfflineRoutePlanner.stationChoices(listOf(l))
        val item=saved.copy(originKey=keys[0].key,destinationKey=keys[1].key)
        val reverse=JourneyPolicy.reversed(item)
        assertTrue(OfflineRoutePlanner.plan(listOf(l),reverse.originKey,reverse.destinationKey,JourneyPolicy.preference(reverse))
            is OfflineRoutePlanner.PlanResult.Found)
    }
}
