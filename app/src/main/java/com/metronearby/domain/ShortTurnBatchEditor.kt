package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.ShortTurn

/** 多条区间车的安全批量编辑；起终点始终保持原值。 */
object ShortTurnBatchEditor {

    sealed interface Result {
        data class Updated(val shortTurns: List<ShortTurn>) : Result
        data class Rejected(val reason: String) : Result
    }

    fun shift(
        line: MetroLine,
        shortTurns: List<ShortTurn>,
        selectedIndices: Set<Int>,
        offsetMinutes: Int
    ): Result {
        if (selectedIndices.none { it in shortTurns.indices }) return Result.Rejected("请先选择区间车")
        if (offsetMinutes == 0) return Result.Rejected("调整分钟数不能为 0")
        val offsetSeconds = offsetMinutes * 60
        val updated = shortTurns.mapIndexed { index, shortTurn ->
            if (index !in selectedIndices) shortTurn else {
                val shifted = shortTurn.departures.map { value ->
                    val seconds = TimeUtils.parseClockTime(value)
                        ?: return Result.Rejected("存在无法识别的发车时刻：$value")
                    val target = seconds + offsetSeconds
                    if (target !in 0 until TimeUtils.SECONDS_PER_DAY) {
                        return Result.Rejected("调整后不能跨越当天 00:00")
                    }
                    TimeUtils.formatSecondsOfDay(target)
                }.distinct().sortedBy { TimeUtils.parseClockTime(it) }
                shortTurn.copy(departures = shifted)
            }
        }
        return validateSelected(line, updated, selectedIndices)
    }

    fun replaceDepartures(
        line: MetroLine,
        shortTurns: List<ShortTurn>,
        selectedIndices: Set<Int>,
        departures: List<String>
    ): Result {
        if (selectedIndices.none { it in shortTurns.indices }) return Result.Rejected("请先选择区间车")
        val normalized = departures.map(String::trim).filter(String::isNotEmpty).distinct()
            .sortedBy { TimeUtils.parseClockTime(it) ?: Int.MAX_VALUE }
        if (normalized.isEmpty()) return Result.Rejected("请至少填写一个发车时刻")
        if (normalized.any { TimeUtils.parseClockTime(it) == null }) {
            return Result.Rejected("发车时刻应使用 24 小时制 HH:mm")
        }
        val updated = shortTurns.mapIndexed { index, shortTurn ->
            if (index in selectedIndices) shortTurn.copy(departures = normalized) else shortTurn
        }
        return validateSelected(line, updated, selectedIndices)
    }

    fun remove(shortTurns: List<ShortTurn>, selectedIndices: Set<Int>): List<ShortTurn> =
        shortTurns.filterIndexed { index, _ -> index !in selectedIndices }

    private fun validateSelected(
        line: MetroLine,
        shortTurns: List<ShortTurn>,
        selectedIndices: Set<Int>
    ): Result {
        selectedIndices.filter { it in shortTurns.indices }.forEach { index ->
            val rejection = ShortTurnPlanner.plan(line, shortTurns[index]) as? ShortTurnPlanner.Plan.Rejected
            if (rejection != null) return Result.Rejected(rejection.reason)
        }
        return Result.Updated(shortTurns)
    }
}