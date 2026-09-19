package com.metronearby.location

import com.metronearby.data.model.Station
import com.metronearby.geo.GeoUtils

/**
 * 根据当前定位找最近的若干个车站。只做直线距离排序，
 * 不走步行路径，符合"打开即用、不拖慢首屏"的目标。
 */
class NearestStationFinder(private val stations: List<Station>) {

    data class NearbyStation(
        val station: Station,
        val distanceMeters: Double
    )

    fun findNearest(
        lat: Double,
        lng: Double,
        limit: Int = 3,
        maxDistanceMeters: Double = Double.MAX_VALUE
    ): List<NearbyStation> =
        stations
            .map { station ->
                NearbyStation(
                    station = station,
                    distanceMeters = GeoUtils.haversineMeters(lat, lng, station.lat, station.lng)
                )
            }
            .filter { it.distanceMeters <= maxDistanceMeters }
            .sortedBy { it.distanceMeters }
            .take(limit)
}