package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.Service
import kotlin.math.floor
import kotlin.math.roundToInt

/** 生成、校验与按总班次数重排逐班时刻；不依赖 Android，可直接单元测试。 */
object DepartureSchedulePlanner {
    private const val MAX_DEPARTURES_PER_SERVICE = 2000

    sealed interface ResizeResult {
        data class Resized(val departures: List<Int>) : ResizeResult
        data class Rejected(val reason: String) : ResizeResult
    }

    /** 读取某交路某日型的系统起点站班次。内置逐班表优先于间隔推算。 */
    fun systemDepartures(line: MetroLine, pattern: Pattern, serviceType: String): List<Int> {
        val service = pattern.services.firstOrNull { it.serviceType == serviceType } ?: return emptyList()
        val exact = line.exactDepartures[pattern.startStationId]?.get(pattern.id)
            ?.mapNotNull { runCatching { TimeUtils.parseToSecondsOfDay(it) }.getOrNull() }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        return exact.ifEmpty { fromService(service) }
    }

    /** 按首末班与各时段间隔生成完整班次；首末班始终保留。 */
    fun fromService(service: Service): List<Int> {
        if (service.headways.isEmpty()) return emptyList()
        val first = runCatching { TimeUtils.parseToSecondsOfDay(service.firstDeparture) }.getOrNull()
            ?: return emptyList()
        val last = runCatching { TimeUtils.parseToSecondsOfDay(service.lastDeparture) }.getOrNull()
            ?: return emptyList()
        if (last < first) return emptyList()
        val rules = service.headways.mapNotNull { rule ->
            val from = runCatching { TimeUtils.parseToSecondsOfDay(rule.from) }.getOrNull()
            val to = runCatching { TimeUtils.parseToSecondsOfDay(rule.to) }.getOrNull()
            if (from == null || to == null || rule.intervalSeconds <= 0) null
            else Triple(from, to, rule.intervalSeconds)
        }
        if (rules.size != service.headways.size || rules.isEmpty()) return emptyList()

        val result = ArrayList<Int>()
        var current = first
        var guard = 0
        while (current <= last && guard++ < MAX_DEPARTURES_PER_SERVICE) {
            result += current
            val interval = rules.firstOrNull { current >= it.first && current < it.second }?.third
                ?: rules.last().third
            current += interval
        }
        if (guard < MAX_DEPARTURES_PER_SERVICE && current > last && result.lastOrNull() != last) {
            result += last
        }
        return result
    }

    /** 用户逐班输入：只接收当天 HH:mm，去重并升序。 */
    fun normalize(times: List<String>): List<Int>? {
        val trimmed = times.map { it.trim() }.filter { it.isNotEmpty() }
        if (trimmed.isEmpty()) return emptyList()
        val parsed = trimmed.map { TimeUtils.parseClockTime(it) }
        if (parsed.any { it == null }) return null
        return parsed.filterNotNull().distinct().sorted()
    }

    /**
     * 调整总班次数，同时保持现有首末班和高峰/平峰的相对密度。
     * 通过对原班次序列做分位插值实现；分钟级列表保证严格递增。
     */
    fun resize(current: List<Int>, targetCount: Int): ResizeResult {
        val source = current.distinct().sorted()
        if (targetCount < 1) return ResizeResult.Rejected("班次数至少为 1")
        if (source.isEmpty()) return ResizeResult.Rejected("当前没有可用于生成的班次，请先手动添加时刻")
        if (targetCount == 1) return ResizeResult.Resized(listOf(source.first()))
        val firstMinute = source.first() / 60
        val lastMinute = source.last() / 60
        if (targetCount > lastMinute - firstMinute + 1) {
            return ResizeResult.Rejected("首末班范围内最多只能放 ${lastMinute - firstMinute + 1} 个分钟级班次")
        }

        val minutes = ArrayList<Int>(targetCount)
        for (index in 0 until targetCount) {
            if (index == 0) {
                minutes += firstMinute
                continue
            }
            if (index == targetCount - 1) {
                minutes += lastMinute
                continue
            }
            val position = index.toDouble() * source.lastIndex / (targetCount - 1)
            val lower = floor(position).toInt()
            val upper = (lower + 1).coerceAtMost(source.lastIndex)
            val fraction = position - lower
            val interpolatedSeconds = source[lower] + (source[upper] - source[lower]) * fraction
            val proposed = (interpolatedSeconds / 60.0).roundToInt()
            val minAllowed = minutes.last() + 1
            val maxAllowed = lastMinute - (targetCount - 1 - index)
            minutes += proposed.coerceIn(minAllowed, maxAllowed)
        }
        return ResizeResult.Resized(minutes.map { it * 60 })
    }
}
