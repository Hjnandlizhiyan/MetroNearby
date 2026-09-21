package com.metronearby.location

import com.metronearby.data.model.MetroLine
import com.metronearby.domain.TransferStationResolver
import com.metronearby.geo.GeoUtils

data class NearbyStationSummary(
    val stationName: String,
    val cityName: String,
    val distanceMeters: Double,
    val lineIds: List<String>,
    val lineNames: List<String>
)

/** 将换乘站的多条线路合并，返回按直线距离排序的附近车站。 */
object NearbyStationCatalog {
    fun find(
        lines: List<MetroLine>,
        lat: Double,
        lng: Double,
        limit: Int = 5
    ): List<NearbyStationSummary> {
        if (limit <= 0) return emptyList()
        data class Candidate(
            val name: String,
            val cityName: String,
            val distance: Double,
            val lineId: String,
            val lineName: String
        )
        val candidates = lines.flatMap { line ->
            val city = line.cityName?.takeIf { it.isNotBlank() } ?: line.cityId ?: "未设置城市"
            line.stations.map { station ->
                Candidate(
                    name = station.name,
                    cityName = city,
                    distance = GeoUtils.haversineMeters(lat, lng, station.lat, station.lng),
                    lineId = line.lineId,
                    lineName = line.lineName
                )
            }
        }
        return candidates
            .groupBy { it.cityName + "|" + TransferStationResolver.normalize(it.name) }
            .values
            .map { group ->
                val nearest = group.minBy { it.distance }
                NearbyStationSummary(
                    stationName = nearest.name,
                    cityName = nearest.cityName,
                    distanceMeters = nearest.distance,
                    lineIds = group.distinctBy { it.lineId }.map { it.lineId },
                    lineNames = group.distinctBy { it.lineId }.map { it.lineName }
                )
            }
            .sortedBy { it.distanceMeters }
            .take(limit)
    }
}