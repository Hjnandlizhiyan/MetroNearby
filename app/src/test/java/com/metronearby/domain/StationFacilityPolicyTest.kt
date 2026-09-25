package com.metronearby.domain

import com.metronearby.data.MetroJson
import com.metronearby.data.StationFacilityCodec
import com.metronearby.data.model.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StationFacilityPolicyTest {
    private val key = "beijing|复兴门"
    private val record = StationFacilityRecord(key, "bj1", "北京地铁官网",
        "https://bjsubway.com/example", "2026-09-25", "2024-12", "未现场核验")
    private val catalog = StationFacilityCatalog(records = listOf(record, record.copy(lineId = "bj2")))
    private val note = StationPersonalNotes(key, exitNote = "C口", facilityNote = "站厅", transferNote = "先看标识")
    private val other = StationPersonalNotes("beijing|积水潭", exitNote = "B口")
    private fun line(city: String, id: String) = MetroLine(
        cityId = city, cityName = city, lineId = id, lineName = id,
        stations = listOf(Station("s", "复兴门", 39.9, 116.3)))

    @Test fun recordsStayWithinCity() {
        assertTrue(StationFacilityPolicy.recordsFor(catalog, "tianjin|复兴门", setOf("bj1")).isEmpty())
    }
    @Test fun recordsStayWithinLine() {
        assertEquals(listOf(record), StationFacilityPolicy.recordsFor(catalog, key, setOf("bj1")))
    }
    @Test fun transferStationKeepsSeparateRecords() {
        assertEquals(2, StationFacilityPolicy.recordsFor(catalog, key, setOf("bj1","bj2")).size)
    }
    @Test fun missingStationHasNoInventedData() {
        assertTrue(StationFacilityPolicy.recordsFor(catalog,"beijing|未知",setOf("bj1")).isEmpty())
    }
    @Test fun lineLookupDoesNotMixCities() {
        assertEquals(listOf("bj1"), StationFacilityPolicy.linesFor(
            listOf(line("beijing","bj1"),line("tianjin","tj1")),key).map { it.lineId })
    }
    @Test fun lineLookupNormalizesStationSuffix() {
        val l=line("beijing","bj1")
        assertEquals(listOf(l), StationFacilityPolicy.linesFor(listOf(l),OfflineRoutePlanner.stationKey(l,"复兴门站")))
    }
    @Test fun blankNotesAreEmpty() {
        assertFalse(StationFacilityPolicy.hasNotes(StationPersonalNotes(key, exitNote = "  ")))
    }
    @Test fun facilityNoteAloneCountsAsFilled() {
        assertTrue(StationFacilityPolicy.hasNotes(StationPersonalNotes(key, facilityNote = "电梯")))
    }
    @Test fun rejectsBlankStationKey() {
        assertNotNull(StationFacilityPolicy.error(note.copy(stationKey = " ")))
    }
    @Test fun rejectsOversizedNote() {
        assertNotNull(StationFacilityPolicy.error(note.copy(exitNote = "字".repeat(1001))))
    }
    @Test fun acceptsLimitLengthNote() {
        assertNull(StationFacilityPolicy.error(note.copy(exitNote = "字".repeat(1000))))
    }
    @Test fun savesTrimmedNotes() {
        assertEquals("C口", StationFacilityPolicy.upsert(emptyList(),note.copy(exitNote=" C口 "),10).single().exitNote)
    }
    @Test fun saveStampsPersonalUpdateTime() {
        assertEquals(42L, StationFacilityPolicy.upsert(emptyList(),note,42).single().updatedAtMillis)
    }
    @Test fun saveReplacesSameStationOnly() {
        val updated=StationFacilityPolicy.upsert(listOf(note,other),note.copy(exitNote="D口"),1)
        assertEquals(listOf(other,note.copy(exitNote="D口",updatedAtMillis=1)),updated)
    }
    @Test fun savingEmptyNotesRemovesRecord() {
        assertEquals(listOf(other),StationFacilityPolicy.upsert(listOf(note,other),StationPersonalNotes(key),1))
    }
    @Test fun removeKeepsOtherStations() {
        assertEquals(listOf(other),StationFacilityPolicy.remove(listOf(note,other),key))
    }
    @Test fun chineseNotesRoundTrip() {
        assertEquals(listOf(note),StationFacilityCodec.decodeNotes(StationFacilityCodec.encodeNotes(listOf(note))))
    }
    @Test(expected = Exception::class) fun damagedNotesAreNotSilentlyEmptied() {
        StationFacilityCodec.decodeNotes("{broken")
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateNoteKeysAreRejected() {
        StationFacilityCodec.decodeNotes(StationFacilityCodec.encodeNotes(listOf(note,note)))
    }
    @Test fun oldAbsentNoteFieldsDefaultToEmpty() {
        val parsed=StationFacilityCodec.decodeNotes("[{\"stationKey\":\"beijing|复兴门\"}]").single()
        assertFalse(StationFacilityPolicy.hasNotes(parsed))
    }
    @Test(expected = IllegalArgumentException::class) fun unsupportedCatalogVersionIsRejected() {
        StationFacilityCodec.decodeCatalog("{\"schemaVersion\":2}")
    }
    @Test(expected = IllegalArgumentException::class) fun duplicateOfficialLineRecordIsRejected() {
        val text=MetroJson.instance.encodeToString(StationFacilityCatalog.serializer(),
            StationFacilityCatalog(records=listOf(record,record)))
        StationFacilityCodec.decodeCatalog(text)
    }
    @Test fun everyBundledRecordMatchesActualStationAndLine() {
        val bundled=StationFacilityCodec.decodeCatalog(File("src/main/assets/metro/station_facilities_beijing.json").readText())
        val lines=File("src/main/assets/metro").listFiles()!!.filter {
            it.name.startsWith("line_beijing_") && it.extension=="json"
        }.map { MetroJson.parseLine(it.readText()) }
        assertTrue(bundled.records.isNotEmpty())
        bundled.records.forEach { entry ->
            assertTrue(StationFacilityPolicy.linesFor(lines,entry.stationKey).any { it.lineId==entry.lineId })
        }
    }
    @Test fun bundledRecordsHaveOfficialSourcesAndDates() {
        val bundled=StationFacilityCodec.decodeCatalog(File("src/main/assets/metro/station_facilities_beijing.json").readText())
        bundled.records.forEach {
            val host=java.net.URI(it.sourceUrl).host
            assertTrue(host=="bjsubway.com" || host=="www.bjsubway.com")
            assertFalse(it.sourceUpdatedOn.isBlank())
            assertFalse(it.evidenceNote.isBlank())
        }
    }
}
