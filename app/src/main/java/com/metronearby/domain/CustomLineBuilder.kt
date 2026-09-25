package com.metronearby.domain

import com.metronearby.data.model.CoordSystems
import com.metronearby.data.model.HeadwayRule
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.Segment
import com.metronearby.data.model.Service
import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.Station

data class CustomStationInput(val name: String, val lat: Double, val lng: Double)

data class CustomLineInput(
    val stableId: String,
    val cityName: String,
    val lineName: String,
    val color: String,
    val stations: List<CustomStationInput>,
    val firstDeparture: String,
    val lastDeparture: String,
    val intervalMinutes: Int,
    val runMinutesPerStop: Int
)

object CustomLineBuilder {
    /** 新版仅创建静态线网，兼容结构保留双方向但不填入运营时刻。 */
    fun buildTopology(stableId: String, cityName: String, lineName: String,
        color: String, stations: List<CustomStationInput>): Result {
        return when (val result = build(CustomLineInput(stableId, cityName, lineName,
            color, stations, "06:00", "23:00", 6, 2))) {
            is Result.Rejected -> result
            is Result.Built -> Result.Built(result.line.copy(
                patterns = result.line.patterns.map { it.copy(services = emptyList()) },
                stationServiceTimes = emptyMap(), exactDepartures = emptyMap(),
                note = "用户自定义静态线网，仅用于定位和路线规划"
            ))
        }
    }

    sealed interface Result {
        data class Built(val line: MetroLine) : Result
        data class Rejected(val reason: String) : Result
    }

    fun build(input: CustomLineInput): Result {
        val cityName = input.cityName.trim()
        val lineName = input.lineName.trim()
        if (cityName.isEmpty()) return Result.Rejected("请输入城市名称")
        if (lineName.isEmpty()) return Result.Rejected("请输入线路名称")
        if (!input.color.matches(Regex("^#[0-9A-Fa-f]{6}$"))) {
            return Result.Rejected("线路颜色应为 #RRGGBB，例如 #2F80ED")
        }
        if (input.stations.size < 2) return Result.Rejected("至少需要两个站点")
        val stations = input.stations.map { it.copy(name = it.name.trim()) }
        if (stations.any { it.name.isEmpty() }) return Result.Rejected("站点名称不能为空")
        if (stations.map { it.name }.distinct().size != stations.size) {
            return Result.Rejected("同一线路不能有重名站点")
        }
        if (stations.any { it.lat !in -90.0..90.0 || it.lng !in -180.0..180.0 || (it.lat == 0.0 && it.lng == 0.0) }) {
            return Result.Rejected("站点经纬度无效")
        }
        val first = TimeUtils.parseClockTime(input.firstDeparture)
            ?: return Result.Rejected("首班时间应为 HH:mm")
        val last = TimeUtils.parseClockTime(input.lastDeparture)
            ?: return Result.Rejected("末班时间应为 HH:mm")
        if (last <= first) return Result.Rejected("末班时间必须晚于首班时间")
        if (input.intervalMinutes !in 1..120) return Result.Rejected("发车间隔应为 1 至 120 分钟")
        if (input.runMinutesPerStop !in 1..60) return Result.Rejected("站间时间应为 1 至 60 分钟")

        val safeId = input.stableId.filter { it.isLetterOrDigit() }.ifEmpty { "line" }
        val lineId = "custom_$safeId"
        val stationModels = stations.mapIndexed { index, station ->
            Station(
                id = "${lineId}_${(index + 1).toString().padStart(2, '0')}",
                name = station.name,
                lat = station.lat,
                lng = station.lng,
                aliases = if (station.name.endsWith("站")) listOf(station.name) else listOf(station.name, "${station.name}站")
            )
        }
        val order = stationModels.map { it.id }
        val runSeconds = input.runMinutesPerStop * 60
        val segments = stationModels.zipWithNext { from, to -> Segment(from.id, to.id, runSeconds) }
        val firstText = TimeUtils.formatSecondsOfDay(first)
        val lastText = TimeUtils.formatSecondsOfDay(last)
        fun services(): List<Service> = listOf(ServiceTypes.WEEKDAY, ServiceTypes.WEEKEND).map { type ->
            Service(
                serviceType = type,
                firstDeparture = firstText,
                lastDeparture = lastText,
                headways = listOf(HeadwayRule(firstText, lastText, input.intervalMinutes * 60))
            )
        }
        val firstStation = stationModels.first()
        val lastStation = stationModels.last()
        val patterns = listOf(
            Pattern("forward", "全程车", firstStation.id, lastStation.id, "forward", "开往 ${lastStation.name}", services = services()),
            Pattern("reverse", "全程车", lastStation.id, firstStation.id, "reverse", "开往 ${firstStation.name}", services = services())
        )
        return Result.Built(
            MetroLine(
                cityId = "custom_${safeId}_city",
                cityName = cityName,
                lineId = lineId,
                lineName = lineName,
                color = input.color.uppercase(),
                coordSystem = CoordSystems.WGS84,
                dataSource = "用户自定义线路",
                accuracyLevel = "user_defined",
                needsReview = true,
                note = "站序、坐标、基础首末班、发车间隔和站间时间均由用户填写；可在班次表管理中按工作日、周末和沿途站继续修改。",
                stationOrder = order,
                stations = stationModels,
                segments = segments,
                patterns = patterns
            )
        )
    }

    fun parseStations(text: String): Pair<List<CustomStationInput>?, String?> {
        val rows = text.lines().map(String::trim).filter(String::isNotEmpty)
        val result = rows.mapIndexed { index, row ->
            val parts = row.split(',', '，').map(String::trim)
            if (parts.size != 3) return null to "第 ${index + 1} 行应为：站名,纬度,经度"
            val lat = parts[1].toDoubleOrNull() ?: return null to "第 ${index + 1} 行纬度无效"
            val lng = parts[2].toDoubleOrNull() ?: return null to "第 ${index + 1} 行经度无效"
            CustomStationInput(parts[0], lat, lng)
        }
        return result to null
    }
}
