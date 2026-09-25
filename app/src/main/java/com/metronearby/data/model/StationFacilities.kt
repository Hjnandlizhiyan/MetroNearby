package com.metronearby.data.model

import kotlinx.serialization.Serializable

@Serializable
data class StationFacilityCatalog(
    val schemaVersion: Int = 1,
    val records: List<StationFacilityRecord> = emptyList()
)

@Serializable
data class StationFacilityRecord(
    val stationKey: String,
    val lineId: String,
    val sourceTitle: String,
    val sourceUrl: String,
    val checkedOn: String,
    val sourceUpdatedOn: String,
    val evidenceNote: String,
    val exits: List<StationExitInfo> = emptyList(),
    val facilities: List<StationFacilityInfo> = emptyList()
)

@Serializable
data class StationExitInfo(val name: String, val description: String)

@Serializable
data class StationFacilityInfo(val name: String, val location: String)

@Serializable
data class StationPersonalNotes(
    val stationKey: String,
    val exitNote: String = "",
    val facilityNote: String = "",
    val transferNote: String = "",
    val updatedAtMillis: Long = 0
)
