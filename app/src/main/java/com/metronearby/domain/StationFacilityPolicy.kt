package com.metronearby.domain

import com.metronearby.data.model.*

object StationFacilityPolicy {
    const val MAX_NOTE_LENGTH = 1000

    fun linesFor(lines: List<MetroLine>, key: String): List<MetroLine> =
        lines.filter { line -> line.stations.any { OfflineRoutePlanner.stationKey(line, it.name) == key } }


    fun recordsFor(catalog: StationFacilityCatalog, stationKey: String, lineIds: Set<String>): List<StationFacilityRecord> =
        catalog.records.filter { it.stationKey == stationKey && it.lineId in lineIds }

    fun hasNotes(notes: StationPersonalNotes): Boolean =
        listOf(notes.exitNote, notes.facilityNote, notes.transferNote).any { it.isNotBlank() }

    fun error(notes: StationPersonalNotes): String? = when {
        notes.stationKey.isBlank() -> "未指定车站"
        listOf(notes.exitNote, notes.facilityNote, notes.transferNote).any { it.length > MAX_NOTE_LENGTH } ->
            "每项备注最多 1000 字"
        else -> null
    }

    fun upsert(items: List<StationPersonalNotes>, draft: StationPersonalNotes, nowMillis: Long): List<StationPersonalNotes> {
        require(error(draft) == null)
        val cleaned = draft.copy(exitNote = draft.exitNote.trim(), facilityNote = draft.facilityNote.trim(),
            transferNote = draft.transferNote.trim(), updatedAtMillis = nowMillis)
        val remaining = items.filterNot { it.stationKey == draft.stationKey }
        return if (hasNotes(cleaned)) remaining + cleaned else remaining
    }

    fun remove(items: List<StationPersonalNotes>, key: String): List<StationPersonalNotes> =
        items.filterNot { it.stationKey == key }
}
