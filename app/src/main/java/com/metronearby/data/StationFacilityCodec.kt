package com.metronearby.data

import com.metronearby.data.model.*
import com.metronearby.domain.StationFacilityPolicy
import kotlinx.serialization.builtins.ListSerializer

object StationFacilityCodec {
    fun decodeCatalog(text: String): StationFacilityCatalog =
        MetroJson.instance.decodeFromString(StationFacilityCatalog.serializer(), text).also { catalog ->
            require(catalog.schemaVersion == 1) { "暂不支持该资料格式" }
            require(catalog.records.distinctBy { it.stationKey to it.lineId }.size == catalog.records.size)
            catalog.records.forEach {
                require(it.stationKey.isNotBlank() && it.lineId.isNotBlank())
                require(it.sourceTitle.isNotBlank() && it.checkedOn.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
                require(it.sourceUrl.startsWith("https://"))
            }
        }

    /** 不将损坏数据当成空列表，避免编辑时覆盖原始备注。 */
    fun decodeNotes(text: String): List<StationPersonalNotes> =
        MetroJson.instance.decodeFromString(ListSerializer(StationPersonalNotes.serializer()), text).also { notes ->
            require(notes.all { StationFacilityPolicy.error(it) == null })
            require(notes.distinctBy { it.stationKey }.size == notes.size)
        }

    fun encodeNotes(notes: List<StationPersonalNotes>): String =
        MetroJson.instance.encodeToString(ListSerializer(StationPersonalNotes.serializer()), notes)
}
