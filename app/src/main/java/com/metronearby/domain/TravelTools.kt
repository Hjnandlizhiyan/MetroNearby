package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.geo.GeoUtils
import kotlinx.serialization.Serializable
import kotlin.math.*

@Serializable
data class SavedJourney(
    val id: String,
    val name: String,
    val originKey: String,
    val destinationKey: String,
    val preference: String = OfflineRoutePlanner.Preference.FEWER_TRANSFERS.name,
    val commute: Boolean = false
)

object JourneyPolicy {
    fun valid(item: SavedJourney): Boolean =
        item.id.isNotBlank() && item.name.isNotBlank() &&
            item.originKey.isNotBlank() && item.destinationKey.isNotBlank() &&
            item.originKey != item.destinationKey

    fun upsert(items: List<SavedJourney>, item: SavedJourney): List<SavedJourney> {
        require(valid(item))
        return items.filterNot { it.id == item.id } + item.copy(name = item.name.trim().take(40))
    }

    fun preference(item: SavedJourney): OfflineRoutePlanner.Preference =
        OfflineRoutePlanner.Preference.entries.firstOrNull { it.name == item.preference }
            ?: OfflineRoutePlanner.Preference.FEWER_TRANSFERS

    fun reversed(item: SavedJourney): SavedJourney =
        item.copy(originKey = item.destinationKey, destinationKey = item.originKey)
}

data class TravelStep(
    val lineName: String,
    val lineColor: String?,
    val board: String,
    val nextStation: String,
    val alight: String,
    val stopCount: Int,
    val transferTo: String?
) {
    val directionText: String get() = "往 ${nextStation} 站方向"
    val finishText: String get() =
        transferTo?.let { "在 ${alight} 换乘 ${it}" } ?: "在 ${alight} 下车，抵达目的站"
}

object TravelGuide {
    fun steps(route: OfflineRoutePlanner.RouteResult): List<TravelStep> =
        route.legs.mapIndexed { index, leg ->
            TravelStep(leg.lineName, leg.lineColor, leg.fromStation,
                leg.stationNames.getOrNull(1) ?: leg.toStation, leg.toStation,
                leg.stopCount, route.legs.getOrNull(index + 1)?.lineName)
        }

    fun cardLines(route: OfflineRoutePlanner.RouteResult): List<String> = buildList {
        add("${route.originName} → ${route.destinationName}")
        add("共 ${route.stopCount} 站 · 换乘 ${route.transferCount} 次")
        steps(route).forEachIndexed { index, step ->
            add("${index + 1}. ${step.lineName} · ${step.stopCount} 站")
            add("从 ${step.board} 上车，${step.directionText}")
            add(step.finishText)
        }
        add("方向按下一站识别，请核对站台标识与列车终点。")
        add("离线线网参考，临时调整以现场公告为准。")
    }
}

data class RadarStation(
    val key: String,
    val name: String,
    val distanceMeters: Double,
    val bearing: Double,
    val lineNames: List<String>,
    val colors: List<String?>
)

object StationRadar {
    /** 真北为 0 度，顺时针增加。 */
    fun bearing(lat: Double, lng: Double, toLat: Double, toLng: Double): Double {
        val a = Math.toRadians(lat)
        val b = Math.toRadians(toLat)
        val d = Math.toRadians(toLng - lng)
        return (Math.toDegrees(atan2(sin(d) * cos(b),
            cos(a) * sin(b) - sin(a) * cos(b) * cos(d))) + 360.0) % 360.0
    }

    fun positionLabel(distanceMeters: Double, bearing: Double, accuracyMeters: Float?): String =
        if (distanceMeters <= maxOf(1.0, accuracyMeters?.toDouble() ?: 1.0)) "当前位置附近"
        else direction(bearing)

    fun direction(bearing: Double): String {
        val labels = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        return labels[((bearing + 22.5).mod(360.0) / 45).toInt()]
    }

    fun find(lines: List<MetroLine>, location: UserLocation, limit: Int = 8): List<RadarStation> {
        if (limit <= 0) return emptyList()
        return lines.flatMap { line ->
            line.stations.map { station -> Triple(
                OfflineRoutePlanner.stationKey(line, station.name), line, station
            ) }
        }.groupBy { it.first }.map { (key, group) ->
            val nearest = group.minBy {
                GeoUtils.haversineMeters(location.lat, location.lng, it.third.lat, it.third.lng)
            }.third
            val stationLines = group.map { it.second }.distinctBy { it.lineId }
            RadarStation(key, nearest.name,
                GeoUtils.haversineMeters(location.lat, location.lng, nearest.lat, nearest.lng),
                bearing(location.lat, location.lng, nearest.lat, nearest.lng),
                stationLines.map { it.lineName }, stationLines.map { it.color })
        }.sortedBy { it.distanceMeters }.take(limit)
    }
}
