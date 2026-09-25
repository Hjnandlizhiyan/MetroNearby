package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.geo.GeoUtils

/** 基于离线线网与本机足迹推荐未到访车站。 */
object RandomExplorer {
    data class Filters(
        val lineId: String? = null,
        val maxDistanceKm: Double? = null,
        val maxTransfers: Int? = null
    )

    data class Recommendation(
        val station: OfflineRoutePlanner.StationChoice,
        val distanceMeters: Double,
        val route: OfflineRoutePlanner.RouteResult
    )

    sealed interface Result {
        data class Found(val recommendation: Recommendation) : Result
        data class Empty(val reason: String) : Result
    }

    fun recommend(
        lines: List<MetroLine>,
        footprints: List<StationFootprint>,
        originKey: String,
        filters: Filters = Filters(),
        seed: Int = 0
    ): Result {
        val choices = OfflineRoutePlanner.stationChoices(lines)
        val origin = choices.firstOrNull { it.key == originKey }
            ?: return Result.Empty("请先选择一个有效的起点站")
        val coordinates = stationCoordinates(lines)
        val originCoordinate = coordinates[originKey]
            ?: return Result.Empty("起点站缺少坐标，暂时无法随机推荐")
        val visited = footprints.mapTo(hashSetOf()) { it.stationKey }
        val allowedByLine = filters.lineId?.let { lineId ->
            lines.asSequence()
                .filter { it.lineId == lineId && cityName(it) == origin.cityName }
                .flatMap { line ->
                    line.stations.asSequence().map { OfflineRoutePlanner.stationKey(line, it.name) }
                }
                .toSet()
        }

        val candidates = choices.asSequence()
            .filter { it.cityName == origin.cityName && it.key != originKey && it.key !in visited }
            .filter { allowedByLine == null || it.key in allowedByLine }
            .mapNotNull { choice ->
                val coordinate = coordinates[choice.key] ?: return@mapNotNull null
                val distance = GeoUtils.haversineMeters(
                    originCoordinate.first,
                    originCoordinate.second,
                    coordinate.first,
                    coordinate.second
                )
                if (filters.maxDistanceKm != null && distance > filters.maxDistanceKm * 1_000.0) null
                else choice to distance
            }
            .sortedBy { stableRank(it.first.key, seed) }
            .toList()

        if (candidates.isEmpty()) {
            return Result.Empty("当前筛选条件下没有尚未点亮的车站")
        }

        candidates.forEach { (choice, distance) ->
            val planned = OfflineRoutePlanner.plan(
                lines = lines,
                originKey = originKey,
                destinationKey = choice.key,
                preference = OfflineRoutePlanner.Preference.FEWER_TRANSFERS
            )
            if (planned is OfflineRoutePlanner.PlanResult.Found &&
                (filters.maxTransfers == null || planned.route.transferCount <= filters.maxTransfers)
            ) {
                return Result.Found(Recommendation(choice, distance, planned.route))
            }
        }
        return Result.Empty("未找到符合换乘限制且可达的未到访车站")
    }

    private fun stationCoordinates(lines: List<MetroLine>): Map<String, Pair<Double, Double>> {
        val coordinates = linkedMapOf<String, Pair<Double, Double>>()
        lines.forEach { line ->
            line.stations.forEach { station ->
                coordinates.putIfAbsent(
                    OfflineRoutePlanner.stationKey(line, station.name),
                    station.lat to station.lng
                )
            }
        }
        return coordinates
    }

    private fun stableRank(key: String, seed: Int): Long {
        var value = key.hashCode().toLong() xor seed.toLong()
        value = (value xor (value ushr 33)) * -49064778989728563L
        value = (value xor (value ushr 33)) * -4265267296055464877L
        return value xor (value ushr 33)
    }

    private fun cityName(line: MetroLine): String =
        line.cityName?.takeIf { it.isNotBlank() }
            ?: line.cityId?.takeIf { it.isNotBlank() }
            ?: "未设置城市"
}
