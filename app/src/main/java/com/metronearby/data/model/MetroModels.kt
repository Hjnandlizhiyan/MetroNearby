package com.metronearby.data.model

import kotlinx.serialization.Serializable

/**
 * 城市索引：描述一个城市下有哪些线路，以及各自的数据文件。
 */
@Serializable
data class CityIndex(
    val schemaVersion: Int = 1,
    val cityId: String,
    val cityName: String,
    val coordSystem: String = CoordSystems.WGS84,
    val lines: List<LineRef> = emptyList()
)

@Serializable
data class LineRef(
    val lineId: String,
    val name: String,
    /** 线路主题色（如 "#A4343A"），UI 的徽标/色带/首班高亮都取这里，禁止在 Compose 里硬编码 */
    val color: String? = null,
    /** 站点数据文件名；为 null 表示该线尚未收录站点数据，仅登记名称与主题色 */
    val dataFile: String? = null
)

@Serializable
data class Station(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val aliases: List<String> = emptyList()
)

/**
 * 官网公布的"某站首末班车"。
 * 它有两个用途：展示给用户；以及反推该站相对交路始发站的运行偏移 offset。
 */
@Serializable
data class StationServiceTime(
    val firstDeparture: String,
    val lastDeparture: String? = null
)

/**
 * 相邻两站之间的运行时长，作为 stationServiceTimes 无法提供 offset 时的兜底。
 */
@Serializable
data class Segment(
    val from: String,
    val to: String,
    val runSeconds: Int
)

@Serializable
data class HeadwayRule(
    val from: String,
    val to: String,
    val intervalSeconds: Int
)

/**
 * 一套分时段的发车方案，weekday 与 weekend 分开描述，以表达早晚高峰密、平峰疏。
 */
@Serializable
data class Service(
    val serviceType: String,
    val firstDeparture: String,
    val lastDeparture: String,
    val headways: List<HeadwayRule> = emptyList()
)

/**
 * 交路。全程车与区间车都是同等地位的 pattern，区间车用 isShortTurn 标记。
 */
/** 未指定方向时的兜底分组标识 */
const val DEFAULT_DIRECTION_ID = "default"

@Serializable
data class Pattern(
    val id: String,
    val name: String,
    val startStationId: String,
    val endStationId: String,
    /**
     * 方向标识。同一方向下的多个交路（全程车 + 区间车）在 UI 上归为同一组，
     * 因此「某站某条线的两个方向倒计时」就是两个 directionId 各一组。
     */
    val directionId: String = DEFAULT_DIRECTION_ID,
    /** 方向标签，如「开往 环球度假区」；缺省时由该方向最远的终点站推导 */
    val directionLabel: String? = null,
    val isShortTurn: Boolean = false,
    val services: List<Service> = emptyList()
)

@Serializable
data class MetroLine(
    val schemaVersion: Int = 1,
    /** 城市归属随线路数据携带，供多城市列表和线路卡片直接展示。 */
    val cityId: String? = null,
    val cityName: String? = null,
    val lineId: String,
    val lineName: String,
    /** 线路标识色（如 "#A4343A"），与 CityIndex 里的 LineRef.color 同源 */
    val color: String? = null,
    val coordSystem: String = CoordSystems.WGS84,
    val updatedAt: String? = null,
    val dataSource: String? = null,
    val accuracyLevel: String? = null,
    val needsReview: Boolean = false,
    val note: String? = null,
    val stationOrder: List<String> = emptyList(),
    val stations: List<Station> = emptyList(),
    val stationServiceTimes: Map<String, Map<String, StationServiceTime>> = emptyMap(),
    val segments: List<Segment> = emptyList(),
    val patterns: List<Pattern> = emptyList(),
    val exactDepartures: Map<String, Map<String, List<String>>> = emptyMap()
) {
    fun stationById(stationId: String): Station? = stations.firstOrNull { it.id == stationId }

    fun patternById(patternId: String): Pattern? = patterns.firstOrNull { it.id == patternId }

    /** 返回该站在整条线路中的顺序号，不存在时返回 -1。 */
    fun orderIndexOf(stationId: String): Int = stationOrder.indexOf(stationId)
}

object CoordSystems {
    const val WGS84 = "wgs84"
    const val GCJ02 = "gcj02"
    const val BD09 = "bd09"
}

object ServiceTypes {
    const val WEEKDAY = "weekday"
    const val WEEKEND = "weekend"
}
