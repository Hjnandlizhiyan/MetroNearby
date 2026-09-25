package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.model.StationSubscription
import org.junit.Assert.*
import org.junit.Test

class SubscriptionPreferencesTest {
    private val line = MetroJson.parseLine(javaClass.classLoader!!.getResourceAsStream("metro/line_test.json")!!
        .bufferedReader().use { it.readText() }).copy(cityId = "beijing", cityName = "北京")
    private val lines = listOf(line)
    private val item = StationSubscription(line.stations.first().name, line.lineId)
    private val target = OfflineRoutePlanner.stationKey(line, line.stations.last().name)

    @Test fun legacyDataKeepsDefaults() {
        val old = MetroJson.parseSubscriptions("""[{"stationName":"西单","lineId":"bj1","directionId":"forward"}]""").single()
        assertEquals(StationSubscription("西单", "bj1", "forward"), old)
    }
    @Test fun metadataSurvivesSerialization() {
        val value = item.copy(tag = "公司", destinationKey = target, preferredExit = "A口", note = "走天桥")
        assertEquals(listOf(value), MetroJson.parseSubscriptions(MetroJson.encodeSubscriptions(listOf(value))))
    }
    @Test fun editingMetadataKeepsDirection() {
        val value = item.copy(directionId = "east")
        assertEquals("east", StationSubscriptions.upsert(listOf(value), value.copy(note = "备注")).single().directionId)
    }
    @Test fun trimsPersonalText() {
        val value = StationSubscriptions.upsert(emptyList(), item.copy(preferredExit = " A口 ", note = " 备注 ")).single()
        assertEquals("A口", value.preferredExit)
        assertEquals("备注", value.note)
    }
    @Test fun destinationExcludesOrigin() {
        assertFalse(SubscriptionPreferences.destinations(item, lines).any { it.key == SubscriptionPreferences.originKey(item, lines) })
    }
    @Test fun destinationExcludesOtherCity() {
        val other = line.copy(lineId = "other", cityId = "other", cityName = "其他市")
        assertTrue(SubscriptionPreferences.destinations(item, lines + other).all { it.key.startsWith("beijing|") })
    }
    @Test fun ambiguousOriginDoesNotGuessCity() {
        val other = line.copy(lineId = "other", cityId = "other", cityName = "其他市")
        assertNull(SubscriptionPreferences.originKey(item.copy(lineId = null), lines + other))
    }
    @Test fun invalidLineDoesNotFallback() {
        assertNull(SubscriptionPreferences.originKey(item.copy(lineId = "gone"), lines))
    }
    @Test fun removedDestinationIsReported() {
        assertNotNull(SubscriptionPreferences.error(item.copy(destinationKey = "gone"), lines))
    }
    @Test fun currentStationCannotBeDestination() {
        assertNotNull(SubscriptionPreferences.error(item.copy(destinationKey = SubscriptionPreferences.originKey(item, lines)), lines))
    }
    @Test fun validDestinationAccepted() {
        assertNull(SubscriptionPreferences.error(item.copy(destinationKey = target), lines))
    }
    @Test fun clearedDestinationAccepted() {
        assertNull(SubscriptionPreferences.error(item.copy(destinationKey = null), lines))
    }
    @Test fun exitLengthBoundaryAccepted() {
        assertNull(SubscriptionPreferences.error(item.copy(preferredExit = "字".repeat(80)), lines))
    }
    @Test fun tooLongExitRejected() {
        assertNotNull(SubscriptionPreferences.error(item.copy(preferredExit = "字".repeat(81)), lines))
    }
    @Test fun noteLengthBoundaryAccepted() {
        assertNull(SubscriptionPreferences.error(item.copy(note = "字".repeat(500)), lines))
    }
    @Test fun tooLongNoteRejected() {
        assertNotNull(SubscriptionPreferences.error(item.copy(note = "字".repeat(501)), lines))
    }
    @Test fun unknownTagRejected() {
        assertNotNull(SubscriptionPreferences.error(item.copy(tag = "unknown"), lines))
    }
    @Test fun staleDestinationRetainedOnLoad() {
        val value = item.copy(destinationKey = "gone")
        assertEquals("gone", MetroJson.parseSubscriptions(MetroJson.encodeSubscriptions(listOf(value))).single().destinationKey)
    }
}
