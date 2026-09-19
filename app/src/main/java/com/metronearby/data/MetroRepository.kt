package com.metronearby.data

import com.metronearby.data.model.ArrivalObservation
import com.metronearby.data.model.HeadwaySession
import com.metronearby.data.model.CityIndex
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.ManagedTrip
import com.metronearby.data.model.ShortTurn
import com.metronearby.data.model.UserOverrides
import com.metronearby.data.source.MetroDataSource
import com.metronearby.data.source.OverrideStore
import com.metronearby.domain.ShortTurnPlanner
import com.metronearby.domain.TimeUtils
import com.metronearby.domain.ObservationTimeBand
import com.metronearby.domain.CalibrationLearning
import com.metronearby.domain.HeadwayLearning
import com.metronearby.domain.DepartureSchedulePlanner
import com.metronearby.domain.TripStationPlanner

/**
 * 内置数据只读，用户修正叠加。
 *
 * 这里只负责"加载 + 应用用户修正（坐标 / 站间时长 / 发车间隔 / 用户区间车）"，
 * 与时间相关的推算（含 offset 校正、真实发车序列覆盖）交给 ArrivalEstimator。
 *
 * 用户区间车与内置交路是同一层概念：折算成 pattern 后追加进 line.patterns，
 * 因此推算与界面都不需要为它开分支。
 */
class MetroRepository(
    private val dataSource: MetroDataSource,
    private val overrideStore: OverrideStore? = null
) {

    data class ResolvedLine(
        val line: MetroLine,
        val overrides: UserOverrides?
    )

    fun loadCityIndex(cityAssetPath: String = DEFAULT_CITY_INDEX): CityIndex =
        MetroJson.parseCityIndex(dataSource.readText(cityAssetPath))

    fun loadLine(lineDataFile: String): MetroLine =
        MetroJson.parseLine(dataSource.readText(assetPath(lineDataFile)))

    fun loadResolvedLine(lineDataFile: String): ResolvedLine {
        val line = loadLine(lineDataFile)
        return resolveLine(line)
    }

    fun resolveLine(line: MetroLine): ResolvedLine {
        val overrides = storedOverrides(line.lineId)
        return ResolvedLine(line = merge(line, overrides), overrides = overrides)
    }

    /**
     * 把用户修正合并进内置线路数据，返回一份新的不可变对象。
     */
    fun merge(line: MetroLine, overrides: UserOverrides?): MetroLine {
        if (overrides == null) return line

        val stations = line.stations.map { station ->
            val fix = overrides.stationCoordFix[station.id]
            if (fix == null) station else station.copy(lat = fix.lat, lng = fix.lng)
        }

        val segments = line.segments.map { segment ->
            val fix = overrides.segmentRunSecondsFix["${segment.from}->${segment.to}"]
            if (fix == null) segment else segment.copy(runSeconds = fix)
        }

        val patterns = line.patterns.map { pattern ->
            val headwayByService = overrides.headwayFix[pattern.id] ?: return@map pattern
            val services = pattern.services.map { service ->
                val fixed = headwayByService[service.serviceType]
                if (fixed == null) service else service.copy(headways = fixed)
            }
            pattern.copy(services = services)
        }

        // 用户区间车是「追加」而非「修改」：内置数据一个字节都不动，只在其后补上新交路
        val userPatterns = ShortTurnPlanner.patterns(line, overrides.shortTurns)

        return line.copy(
            stations = stations,
            segments = segments,
            patterns = patterns + userPatterns
        )
    }

    // ---------------------------------------------------------------------
    // 用户反馈入口：写入后立即落盘，下一次推算即生效
    // ---------------------------------------------------------------------

    fun recordObservation(lineId: String, observation: ArrivalObservation): UserOverrides {
        val current = currentOverrides(lineId)
        val updated = current.copy(
            updatedAt = observation.recordedAt ?: current.updatedAt,
            arrivalObservations = CalibrationLearning.append(current.arrivalObservations, observation)
        )
        persist(updated)
        return updated
    }

    fun saveHeadwaySession(lineId: String, session: HeadwaySession) {
        val current = currentOverrides(lineId)
        val sessions = current.headwaySessions.map {
            if (it.id != session.id && !it.ended && it.stationId == session.stationId && it.patternId == session.patternId)
                it.copy(ended = true) else it
        }.filterNot { it.id == session.id } + HeadwayLearning.freezeNext(session, current.headwaySessions)
        persist(current.copy(headwaySessions = sessions))
    }

    fun removeHeadwaySession(lineId: String, id: String) {
        val current = currentOverrides(lineId)
        persist(current.copy(headwaySessions = current.headwaySessions.filterNot { it.id == id }))
    }

    /**
     * 记录用户在站台实际看到的一班车，作为校正锚点。
     */
    fun recordObservation(
        lineId: String,
        stationId: String,
        patternId: String,
        observedTime: String,
        recordedAt: String? = null,
        serviceType: String? = null,
        timeBand: String? = null,
        recordedAtEpochMillis: Long? = null,
        recordedSecondsOfDay: Int? = null
    ): UserOverrides {
        val current = currentOverrides(lineId)
        val observation = ArrivalObservation(
            stationId = stationId,
            patternId = patternId,
            observedTime = observedTime,
            recordedAt = recordedAt,
            serviceType = serviceType,
            timeBand = timeBand ?: TimeUtils.parseClockTime(observedTime)
                ?.let { ObservationTimeBand.fromSeconds(it).storageKey },
            recordedAtEpochMillis = recordedAtEpochMillis,
            recordedSecondsOfDay = recordedSecondsOfDay
        )
        val updated = current.copy(
            updatedAt = recordedAt ?: current.updatedAt,
            arrivalObservations = current.arrivalObservations + observation
        )
        persist(updated)
        return updated
    }

    /**
     * 删除该站该交路下第 [index] 条观测。
     *
     * [index] 是「按站点 + 交路过滤后」的位置，与界面列出的顺序一致；
     * 越界时原样返回，不做任何修改。
     */
    fun removeObservation(
        lineId: String,
        stationId: String,
        patternId: String,
        index: Int
    ): UserOverrides {
        val current = currentOverrides(lineId)
        val target = current.arrivalObservations.withIndex()
            .filter { matches(it.value, stationId, patternId) }
            .getOrNull(index)
            ?: return current

        val remaining = current.arrivalObservations.toMutableList().apply { removeAt(target.index) }
        val updated = current.copy(arrivalObservations = remaining)
        persist(updated)
        return updated
    }

    /**
     * 清空该站该交路下的全部观测，其它站与其它交路的样本不受影响。
     */
    fun clearObservations(
        lineId: String,
        stationId: String,
        patternId: String
    ): UserOverrides {
        val current = currentOverrides(lineId)
        if (current.arrivalObservations.none { matches(it, stationId, patternId) }) return current

        val updated = current.copy(
            arrivalObservations = current.arrivalObservations.filterNot {
                matches(it, stationId, patternId)
            }
        )
        persist(updated)
        return updated
    }

    private fun matches(
        observation: ArrivalObservation,
        stationId: String,
        patternId: String
    ): Boolean = observation.stationId == stationId && observation.patternId == patternId

    /**
     * 记录一班真实发车时刻。当某站某交路的真实序列足够完整时，
     * 它将完全替代推算结果。
     */
    fun recordExactDeparture(
        lineId: String,
        stationId: String,
        patternId: String,
        departureTime: String,
        recordedAt: String? = null
    ): UserOverrides {
        val current = currentOverrides(lineId)
        val byPattern = current.exactDepartures[stationId].orEmpty().toMutableMap()
        val merged = (byPattern[patternId].orEmpty() + departureTime)
            .distinct()
            .sortedBy { TimeUtils.parseToSecondsOfDay(it) }
        byPattern[patternId] = merged

        val updated = current.copy(
            updatedAt = recordedAt ?: current.updatedAt,
            exactDepartures = current.exactDepartures + (stationId to byPattern)
        )
        persist(updated)
        return updated
    }

    fun clearExactDepartures(
        lineId: String,
        stationId: String,
        patternId: String
    ): UserOverrides {
        val current = currentOverrides(lineId)
        val byPattern = current.exactDepartures[stationId].orEmpty().toMutableMap()
        byPattern.remove(patternId)
        val updated = current.copy(
            exactDepartures = current.exactDepartures + (stationId to byPattern)
        )
        persist(updated)
        return updated
    }

    /** 保存某交路、某日型的完整起点站班次表；全程车与区间车使用同一结构。 */
    fun setServiceDepartures(
        lineId: String,
        patternId: String,
        serviceType: String,
        departures: List<String>
    ): UserOverrides {
        val normalized = requireNotNull(DepartureSchedulePlanner.normalize(departures)) {
            "班次时刻格式应为 HH:mm"
        }
        require(normalized.isNotEmpty()) { "班次表不能为空" }
        val current = currentOverrides(lineId)
        val byService = current.serviceDepartures[patternId].orEmpty().toMutableMap()
        val formatted = normalized.map(TimeUtils::formatSecondsOfDay)
        byService[serviceType] = formatted
        val tripsByService = current.serviceTrips[patternId].orEmpty().toMutableMap()
        tripsByService[serviceType] = TripStationPlanner.tripsFromDepartures(patternId, serviceType, normalized)
        val updated = current.copy(
            serviceDepartures = current.serviceDepartures + (patternId to byService),
            serviceTrips = current.serviceTrips + (patternId to tripsByService)
        )
        persist(updated)
        return updated
    }

    /** 保存带稳定班次编号和中间站锚点的完整班次表。 */
    fun setServiceTrips(
        line: MetroLine,
        patternId: String,
        serviceType: String,
        trips: List<ManagedTrip>
    ): UserOverrides {
        val pattern = requireNotNull(line.patternById(patternId)) { "交路不存在" }
        val normalized = trips.map { trip ->
            val departure = requireNotNull(TimeUtils.parseClockTime(trip.departureTime)) { "发车时刻格式应为 HH:mm" }
            val stationTimes = trip.stationTimes.mapValues { (_, value) ->
                val seconds = requireNotNull(TimeUtils.parseClockTime(value)) { "到站时刻格式应为 HH:mm" }
                TimeUtils.formatSecondsOfDay(seconds)
            }
            trip.copy(departureTime = TimeUtils.formatSecondsOfDay(departure), stationTimes = stationTimes)
        }
        val validation = TripStationPlanner.validate(line, pattern, serviceType, normalized)
        require(validation is TripStationPlanner.Validation.Valid) {
            (validation as TripStationPlanner.Validation.Rejected).reason
        }
        val current = currentOverrides(line.lineId)
        val tripsByService = current.serviceTrips[patternId].orEmpty().toMutableMap()
        tripsByService[serviceType] = normalized
        val departuresByService = current.serviceDepartures[patternId].orEmpty().toMutableMap()
        departuresByService[serviceType] = normalized.map { it.departureTime }
        val updated = current.copy(
            serviceTrips = current.serviceTrips + (patternId to tripsByService),
            serviceDepartures = current.serviceDepartures + (patternId to departuresByService)
        )
        persist(updated)
        return updated
    }

    /** 仅恢复指定交路与日型，另一日型及其它交路不受影响。 */
    fun clearServiceDepartures(
        lineId: String,
        patternId: String,
        serviceType: String
    ): UserOverrides {
        val current = currentOverrides(lineId)
        val byPattern = current.serviceDepartures.toMutableMap()
        val byService = byPattern[patternId].orEmpty().toMutableMap()
        byService.remove(serviceType)
        if (byService.isEmpty()) byPattern.remove(patternId) else byPattern[patternId] = byService
        val tripsByPattern = current.serviceTrips.toMutableMap()
        val tripsByService = tripsByPattern[patternId].orEmpty().toMutableMap()
        tripsByService.remove(serviceType)
        if (tripsByService.isEmpty()) tripsByPattern.remove(patternId) else tripsByPattern[patternId] = tripsByService
        val updated = current.copy(serviceDepartures = byPattern, serviceTrips = tripsByPattern)
        persist(updated)
        return updated
    }

    /**
     * 新增一条用户自定义的区间车。合法性（站点是否存在、时刻格式）由
     * [ShortTurnPlanner] 在折算时校验，这里只做落盘。
     */
    fun addShortTurn(
        lineId: String,
        startStationId: String,
        endStationId: String,
        departures: List<String>,
        recordedAt: String? = null
    ): UserOverrides {
        val current = currentOverrides(lineId)
        val shortTurn = ShortTurn(
            startStationId = startStationId,
            endStationId = endStationId,
            departures = departures.map { it.trim() }.filter { it.isNotEmpty() }
        )
        val updated = current.copy(
            updatedAt = recordedAt ?: current.updatedAt,
            shortTurns = current.shortTurns + shortTurn
        )
        persist(updated)
        return updated
    }

    /**
     * 删除第 [index] 条区间车，位置与设置界面列出的顺序一致；越界时原样返回。
     */
    fun removeShortTurn(lineId: String, index: Int): UserOverrides {
        val current = currentOverrides(lineId)
        if (index !in current.shortTurns.indices) return current

        val remaining = current.shortTurns.toMutableList().apply { removeAt(index) }
        val updated = current.copy(shortTurns = remaining)
        persist(updated)
        return updated
    }

    fun setPatternEnabled(lineId: String, patternId: String, enabled: Boolean): UserOverrides {
        val current = currentOverrides(lineId)
        val updated = current.copy(
            patternEnabled = current.patternEnabled + (patternId to enabled)
        )
        persist(updated)
        return updated
    }

    private fun currentOverrides(lineId: String): UserOverrides =
        storedOverrides(lineId) ?: UserOverrides(lineId = lineId)

    private fun storedOverrides(lineId: String): UserOverrides? {
        val saved = overrideStore?.load() ?: return null
        return (if (saved.lineId == lineId) saved else saved.otherLines[lineId])
            ?.takeIf { it.lineId == lineId }?.copy(otherLines = emptyMap())
    }

    private fun persist(overrides: UserOverrides) {
        val previous = overrideStore?.load()
        val others = previous?.otherLines.orEmpty().toMutableMap()
        if (previous != null && previous.lineId != overrides.lineId) {
            others[previous.lineId] = previous.copy(otherLines = emptyMap())
        }
        others.remove(overrides.lineId)
        overrideStore?.save(overrides.copy(otherLines = others))
    }

    companion object {
        const val ASSET_DIR = "metro"
        const val DEFAULT_CITY_INDEX = "$ASSET_DIR/city_beijing.json"

        fun assetPath(lineDataFile: String): String = "$ASSET_DIR/$lineDataFile"
    }
}
