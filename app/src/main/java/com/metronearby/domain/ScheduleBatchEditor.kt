package com.metronearby.domain

import com.metronearby.data.model.ManagedTrip
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern

/** 批量修改逐班表；保留班次编号，并让同车沿途锚点随起点一起平移。 */
object ScheduleBatchEditor {
    sealed interface Result {
        data class Updated(val trips: List<ManagedTrip>) : Result
        data class Rejected(val reason: String) : Result
    }

    fun shift(
        line: MetroLine,
        pattern: Pattern,
        serviceType: String,
        trips: List<ManagedTrip>,
        selectedTripIds: Set<String>,
        offsetMinutes: Int
    ): Result {
        if (selectedTripIds.isEmpty()) return Result.Rejected("请先勾选要修改的班次")
        if (offsetMinutes == 0) return Result.Rejected("调整分钟数不能为 0")
        val offsetSeconds = offsetMinutes * 60
        val updated = trips.map { trip ->
            if (trip.id !in selectedTripIds) trip
            else {
                val departure = TimeUtils.parseClockTime(trip.departureTime)
                    ?: return Result.Rejected("班次 ${trip.departureTime} 的时刻格式无效")
                TripStationPlanner.updateStation(
                    line = line,
                    pattern = pattern,
                    serviceType = serviceType,
                    trip = trip,
                    stationId = pattern.startStationId,
                    newSeconds = departure + offsetSeconds
                ) ?: return Result.Rejected("调整后不能跨越当天 00:00，且沿途站时刻必须仍在当天")
            }
        }.sortedBy { TimeUtils.parseClockTime(it.departureTime) }

        return when (val validation = TripStationPlanner.validate(line, pattern, serviceType, updated)) {
            TripStationPlanner.Validation.Valid -> Result.Updated(updated)
            is TripStationPlanner.Validation.Rejected -> Result.Rejected(validation.reason)
        }
    }

    fun remove(
        line: MetroLine,
        pattern: Pattern,
        serviceType: String,
        trips: List<ManagedTrip>,
        selectedTripIds: Set<String>
    ): Result {
        if (selectedTripIds.isEmpty()) return Result.Rejected("请先勾选要删除的班次")
        val remaining = trips.filterNot { it.id in selectedTripIds }
        if (remaining.isEmpty()) return Result.Rejected("班次表至少保留一班；如需恢复系统表，请使用恢复默认班次")
        return when (val validation = TripStationPlanner.validate(line, pattern, serviceType, remaining)) {
            TripStationPlanner.Validation.Valid -> Result.Updated(remaining)
            is TripStationPlanner.Validation.Rejected -> Result.Rejected(validation.reason)
        }
    }
}