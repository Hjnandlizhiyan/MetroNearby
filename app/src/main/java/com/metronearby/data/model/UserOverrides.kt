package com.metronearby.data.model

import kotlinx.serialization.Serializable

/**
 * 用户对站点坐标的修正。
 */
@Serializable
data class StationCoordFix(
    val lat: Double,
    val lng: Double,
    val coordSystem: String = CoordSystems.WGS84
)

/**
 * 用户在站台实际观测到的一班车到站时间。
 *
 * 它不改写基础时刻表；同一站同一交路的观测会按运营日类型和通勤时段聚合，
 * 确认班次的新记录用于历史学习、衰减锚点和提前预测误差检验，用户可切换显示口径。
 */
@Serializable
data class ArrivalObservation(
    val stationId: String,
    val patternId: String,
    val observedTime: String,
    val recordedAt: String? = null,
    val note: String? = null,
    /** weekday / weekend；旧数据为空，作为全天回退样本。 */
    val serviceType: String? = null,
    /** morning_peak / off_peak / evening_peak；旧数据为空。 */
    val timeBand: String? = null,
    /** 用于判断近期锚点是否仍有效。 */
    val recordedAtEpochMillis: Long? = null,
    /** 记录动作发生时的本地当天秒数，用于识别是否为现场记录。 */
    val recordedSecondsOfDay: Int? = null,
    val observedSecondsOfDay: Int? = null,
    val observedEpochMillis: Long? = null,
    val observationDate: String? = null,
    val prediction: ArrivalPredictionSnapshot? = null,
    val matchConfirmed: Boolean = false,
    val liveArrival: Boolean = false
)

/** 在实际到站前冻结，后续校准不能改写这份预测。 */
@Serializable
data class ArrivalPredictionSnapshot(
    val baseSeconds: Int,
    val calibratedSeconds: Int? = null,
    val capturedAtEpochMillis: Long,
    val capturedSecondsOfDay: Int,
    val serviceDate: String,
    val serviceType: String
)

/** 一组明确没有漏车的同站同交路现场到站序列。 */
@Serializable
data class HeadwaySession(
    val id: String,
    val stationId: String,
    val patternId: String,
    val serviceType: String,
    val serviceDate: String,
    val referenceBaseSeconds: Int,
    val points: List<HeadwayPoint> = emptyList(),
    val ended: Boolean = false,
    val nextExpectedEpochMillis: Long? = null
)

@Serializable
data class HeadwayPoint(val secondsOfDay: Int, val epochMillis: Long, val predictedEpochMillis: Long? = null)

/**
 * 用户自定义的区间车：用户在某个区段实际见到的、只跑一段的交路。
 *
 * 用户填的是「几点、从哪、到哪有一班区间车」，模型需要的是一个交路（pattern），
 * 两者的换算见 [com.metronearby.domain.ShortTurnPlanner]。
 *
 * 运营方的区间车调度信息不对外公开，这是唯一的填补渠道，因此它属于用户修正、
 * 而不是内置数据。
 */
@Serializable
data class ShortTurn(
    val startStationId: String,
    val endStationId: String,
    /** 用户在起点站观察到的发车时刻（HH:mm），顺序无关，换算时会去重升序 */
    val departures: List<String> = emptyList()
)

/**
 * 某一趟交路班次。departureTime 是起点发车时刻；stationTimes 只保存用户设置的中间站锚点。
 * id 在修改起点时刻后保持不变，用来确认沿途各站仍属于同一趟车。
 */
@Serializable
data class ManagedTrip(
    val id: String,
    val departureTime: String,
    val stationTimes: Map<String, String> = emptyMap()
)

/**
 * 用户修正数据。与内置线路数据完全分离，升级内置数据不会冲掉用户改动。
 *
 * 优先级（高到低）：
 * 1. serviceTrips（逐班与沿途站）  2. serviceDepartures（兼容起点表）
 * 3. exactDepartures（用户）  4. exactDepartures（内置）
 * 5. headwayFix + stationServiceTimes/segments 推算
 *
 * arrivalObservations 不改写基础推算；用户模式在其上应用独立校准。
 */
@Serializable
data class UserOverrides(
    val schemaVersion: Int = 1,
    val lineId: String,
    val updatedAt: String? = null,
    val stationCoordFix: Map<String, StationCoordFix> = emptyMap(),
    /** 键为 "from->to" */
    val segmentRunSecondsFix: Map<String, Int> = emptyMap(),
    /** 键为 patternId -> serviceType -> 分时段间隔 */
    val headwayFix: Map<String, Map<String, List<HeadwayRule>>> = emptyMap(),
    /** 键为 stationId -> patternId -> 真实发车序列 */
    val exactDepartures: Map<String, Map<String, List<String>>> = emptyMap(),
    /** 键为 patternId -> serviceType -> 起点站逐班时刻；班次表管理页的最高优先级覆盖。 */
    val serviceDepartures: Map<String, Map<String, List<String>>> = emptyMap(),
    /** 新版逐班表：稳定班次 id + 中间站修正。存在时优先于 serviceDepartures。 */
    val serviceTrips: Map<String, Map<String, List<ManagedTrip>>> = emptyMap(),
    /** 用户实测样本，用于分时段校准与近期锚点；不改写基础预计 */
    val arrivalObservations: List<ArrivalObservation> = emptyList(),
    /** 用户自定义的区间车，由 [com.metronearby.domain.ShortTurnPlanner] 折算成交路 */
    val shortTurns: List<ShortTurn> = emptyList(),
    /** 键为 patternId，false 表示用户选择忽略该交路 */
    val patternEnabled: Map<String, Boolean> = emptyMap(),
    /** 保留其他线路的独立修正；内部各项的 otherLines 恒为空，兼容旧单线路文件。 */
    val otherLines: Map<String, UserOverrides> = emptyMap(),
    val headwaySessions: List<HeadwaySession> = emptyList()
) {
    fun isPatternEnabled(patternId: String): Boolean = patternEnabled[patternId] != false
}
