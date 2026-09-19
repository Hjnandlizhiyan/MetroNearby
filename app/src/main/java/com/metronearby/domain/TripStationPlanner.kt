package com.metronearby.domain

import com.metronearby.data.model.ManagedTrip
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern

/** 同一趟车的沿途时刻传播，以及站序倒置和相邻班次超车校验。 */
object TripStationPlanner {
    data class StopTime(
        val stationId: String,
        val stationName: String,
        val systemSeconds: Int,
        val finalSeconds: Int,
        val isAnchor: Boolean
    )

    sealed interface Validation {
        data object Valid : Validation
        data class Rejected(val reason: String) : Validation
    }

    fun tripsFromDepartures(
        patternId: String,
        serviceType: String,
        departures: List<Int>
    ): List<ManagedTrip> = departures.distinct().sorted().mapIndexed { index, seconds ->
        val time = TimeUtils.formatSecondsOfDay(seconds)
        ManagedTrip(
            id = "$patternId-$serviceType-${index + 1}-${time.replace(":", "")}",
            departureTime = time
        )
    }

    fun routeStationIds(line: MetroLine, pattern: Pattern): List<String> {
        val start = line.orderIndexOf(pattern.startStationId)
        val end = line.orderIndexOf(pattern.endStationId)
        if (start < 0 || end < 0) return emptyList()
        return if (start <= end) line.stationOrder.subList(start, end + 1)
        else (start downTo end).map { line.stationOrder[it] }
    }

    fun timeline(
        line: MetroLine,
        pattern: Pattern,
        serviceType: String,
        trip: ManagedTrip
    ): List<StopTime> {
        val origin = TimeUtils.parseClockTime(trip.departureTime) ?: return emptyList()
        val route = routeStationIds(line, pattern)
        if (route.isEmpty()) return emptyList()
        var activeDelta = 0
        return route.mapNotNull { stationId ->
            val offset = offsetSeconds(line, pattern, serviceType, stationId) ?: return@mapNotNull null
            val system = origin + offset
            val anchor = trip.stationTimes[stationId]?.let(TimeUtils::parseClockTime)
            if (anchor != null && stationId != pattern.startStationId) activeDelta = anchor - system
            StopTime(
                stationId = stationId,
                stationName = line.stationById(stationId)?.name ?: stationId,
                systemSeconds = system,
                finalSeconds = system + activeDelta,
                isAnchor = stationId != pattern.startStationId && anchor != null
            )
        }
    }

    fun arrivalSeconds(
        line: MetroLine,
        pattern: Pattern,
        serviceType: String,
        trip: ManagedTrip,
        stationId: String
    ): Int? = timeline(line, pattern, serviceType, trip)
        .firstOrNull { it.stationId == stationId }?.finalSeconds

    /** 修改起点会整体平移已有锚点；修改中间站则从该站起形成新的延误锚点。 */
    fun updateStation(
        line: MetroLine,
        pattern: Pattern,
        serviceType: String,
        trip: ManagedTrip,
        stationId: String,
        newSeconds: Int
    ): ManagedTrip? {
        if (newSeconds !in 0 until TimeUtils.SECONDS_PER_DAY) return null
        if (stationId == pattern.startStationId) {
            val old = TimeUtils.parseClockTime(trip.departureTime) ?: return null
            val delta = newSeconds - old
            val shifted = trip.stationTimes.mapValues { (_, text) ->
                val value = TimeUtils.parseClockTime(text) ?: return null
                val next = value + delta
                if (next !in 0 until TimeUtils.SECONDS_PER_DAY) return null
                TimeUtils.formatSecondsOfDay(next)
            }
            return trip.copy(
                departureTime = TimeUtils.formatSecondsOfDay(newSeconds),
                stationTimes = shifted
            )
        }
        if (stationId !in routeStationIds(line, pattern)) return null
        return trip.copy(stationTimes = trip.stationTimes +
            (stationId to TimeUtils.formatSecondsOfDay(newSeconds)))
    }

    fun clearStation(pattern: Pattern, trip: ManagedTrip, stationId: String): ManagedTrip =
        if (stationId == pattern.startStationId) trip
        else trip.copy(stationTimes = trip.stationTimes - stationId)

    fun validate(
        line: MetroLine,
        pattern: Pattern,
        serviceType: String,
        trips: List<ManagedTrip>
    ): Validation {
        if (trips.isEmpty()) return Validation.Rejected("班次表不能为空")
        if (trips.map { it.id }.distinct().size != trips.size) {
            return Validation.Rejected("存在重复的班次编号")
        }
        val timelines = trips.map { trip ->
            val stops = timeline(line, pattern, serviceType, trip)
            if (stops.size != routeStationIds(line, pattern).size) {
                return Validation.Rejected("班次 ${trip.departureTime} 缺少可用的站间运行数据")
            }
            val inversion = stops.zipWithNext().firstOrNull { (earlier, later) ->
                later.finalSeconds <= earlier.finalSeconds
            }
            if (inversion != null) {
                return Validation.Rejected(
                    "班次 ${trip.departureTime} 在${inversion.first.stationName}与${inversion.second.stationName}之间发生时间倒置"
                )
            }
            stops
        }
        for (index in 0 until timelines.lastIndex) {
            val earlierTrip = trips[index]
            val laterTrip = trips[index + 1]
            val earlierStops = timelines[index].associateBy { it.stationId }
            val laterStops = timelines[index + 1]
            val conflict = laterStops.firstOrNull { later ->
                val earlier = earlierStops[later.stationId]
                earlier != null && later.finalSeconds <= earlier.finalSeconds
            }
            if (conflict != null) {
                return Validation.Rejected(
                    "${laterTrip.departureTime} 班次在${conflict.stationName}追上或超过了 ${earlierTrip.departureTime} 班次"
                )
            }
        }
        return Validation.Valid
    }

    private fun offsetSeconds(
        line: MetroLine,
        pattern: Pattern,
        serviceType: String,
        stationId: String
    ): Int? {
        if (stationId == pattern.startStationId) return 0
        val service = pattern.services.firstOrNull { it.serviceType == serviceType }
        val published = line.stationServiceTimes[stationId]?.get(pattern.id)
        if (service != null && published != null) {
            return TimeUtils.parseToSecondsOfDay(published.firstDeparture) -
                TimeUtils.parseToSecondsOfDay(service.firstDeparture)
        }
        val route = routeStationIds(line, pattern)
        val target = route.indexOf(stationId)
        if (target < 0) return null
        var total = 0
        for (index in 0 until target) {
            val from = route[index]
            val to = route[index + 1]
            val segment = line.segments.firstOrNull { it.from == from && it.to == to }
                ?: line.segments.firstOrNull { it.from == to && it.to == from }
                ?: return null
            total += segment.runSeconds
        }
        return total
    }
}
