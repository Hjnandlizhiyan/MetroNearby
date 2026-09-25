package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import kotlinx.serialization.Serializable

@Serializable
data class StationFootprint(
    val stationKey: String,
    val stationName: String,
    val cityName: String,
    val firstVisitedAtMillis: Long,
    val source: String = "MANUAL"
) {
    enum class Source { MANUAL, NEARBY_LOCATION }
}

data class FootprintLineProgress(
    val lineId: String,
    val lineName: String,
    val color: String?,
    val cityName: String,
    val visited: Int,
    val total: Int
) {
    val complete: Boolean get() = total > 0 && visited == total
}

data class FootprintBadge(val title: String, val description: String)

/** 足迹只记录首次到访；自动点亮必须来自足够近、足够准的真实定位。 */
object StationFootprints {
    const val AUTO_MAX_DISTANCE_METERS = 180.0
    const val AUTO_MAX_ACCURACY_METERS = 100f

    fun canAutoMark(location: UserLocation, distanceMeters: Double): Boolean =
        !location.isMock &&
            distanceMeters in 0.0..AUTO_MAX_DISTANCE_METERS &&
            (location.accuracyMeters == null || location.accuracyMeters <= AUTO_MAX_ACCURACY_METERS)

    fun upsert(items: List<StationFootprint>, item: StationFootprint): List<StationFootprint> {
        if (!valid(item) || items.any { it.stationKey == item.stationKey }) return items
        return (items + item).sortedBy { it.firstVisitedAtMillis }
    }

    fun remove(items: List<StationFootprint>, stationKey: String): List<StationFootprint> =
        items.filterNot { it.stationKey == stationKey }

    fun progress(items: List<StationFootprint>, lines: List<MetroLine>): List<FootprintLineProgress> {
        val visitedKeys = items.mapTo(hashSetOf()) { it.stationKey }
        return lines.map { line ->
            val keys = line.stations.map { OfflineRoutePlanner.stationKey(line, it.name) }.distinct()
            FootprintLineProgress(
                lineId = line.lineId,
                lineName = line.lineName,
                color = line.color,
                cityName = line.cityName ?: line.cityId ?: "未设置城市",
                visited = keys.count { it in visitedKeys },
                total = keys.size
            )
        }.filter { it.total > 0 }
    }

    fun badges(items: List<StationFootprint>, progress: List<FootprintLineProgress>): List<FootprintBadge> = buildList {
        if (items.isNotEmpty()) add(FootprintBadge("第一站", "点亮第一座到访车站"))
        if (items.size >= 10) add(FootprintBadge("城市漫游者", "累计点亮10座车站"))
        if (items.size >= 50) add(FootprintBadge("线网探索家", "累计点亮50座车站"))
        progress.filter { it.complete }.forEach {
            add(FootprintBadge("线路通关", "点亮${it.cityName} · ${it.lineName}全部${it.total}站"))
        }
    }

    fun valid(item: StationFootprint): Boolean =
        item.stationKey.isNotBlank() && item.stationName.isNotBlank() &&
            item.cityName.isNotBlank() && item.firstVisitedAtMillis > 0 &&
            item.source in StationFootprint.Source.entries.map { it.name }
}
