package com.metronearby.domain

import com.metronearby.data.model.HeadwayPoint
import com.metronearby.data.model.HeadwaySession
import com.metronearby.data.model.ArrivalObservation
import java.util.Calendar
import java.util.UUID
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** 只学习用户明确连续观察到的到站间隔，不从零散记录猜测漏车数。 */
object HeadwayLearning {
    const val MIN_GAP = 60
    const val MAX_GAP = 1800
    const val LIVE_MILLIS = 7_200_000L

    fun point(clock: Calendar) = HeadwayPoint(ServiceTypeResolver.secondsOfDay(clock), clock.timeInMillis)

    fun start(item: ArrivalItem, clock: Calendar): HeadwaySession {
        require(abs(item.arrivalSecondsOfDay - ServiceTypeResolver.secondsOfDay(clock)) <= CalibrationLearning.MAX_OFFSET)
        return HeadwaySession(UUID.randomUUID().toString(), item.stationId, item.patternId,
            ServiceTypeResolver.from(clock), CalibrationLearning.date(clock), item.arrivalSecondsOfDay,
            listOf(point(clock)))
    }

    fun appendError(session: HeadwaySession, clock: Calendar, noMissed: Boolean): String? {
        if (session.ended) return "本组已结束，请重新开始"
        if (!noMissed) return "请先确认中间没有漏车；有漏车时请结束本组"
        if (session.serviceDate != CalibrationLearning.date(clock) || session.serviceType != ServiceTypeResolver.from(clock))
            return "已跨日期，请结束本组后重新开始"
        val last = session.points.lastOrNull() ?: return "本组缺少起点，请重新开始"
        val next = point(clock)
        val delta = next.epochMillis - last.epochMillis
        if (delta < MIN_GAP * 1000L) return "距上一班不足1分钟，请勿重复记录"
        if (delta > MAX_GAP * 1000L || next.epochMillis - session.points.first().epochMillis > LIVE_MILLIS)
            return "连续观察已超时，请结束本组后重新开始"
        if (abs(delta / 1000 - (next.secondsOfDay - last.secondsOfDay)) > 2)
            return "设备时间发生变化，请结束本组后重新开始"
        return null
    }

    fun append(session: HeadwaySession, clock: Calendar, noMissed: Boolean): HeadwaySession {
        require(appendError(session, clock, noMissed) == null)
        return session.copy(points = session.points + point(clock).copy(predictedEpochMillis = session.nextExpectedEpochMillis),
            nextExpectedEpochMillis = null)
    }

    /** 每次到站后冻结下一班预测；下一次追加时只读取已保存的预测。 */
    fun freezeNext(session: HeadwaySession, others: List<HeadwaySession>): HeadwaySession {
        if (session.ended) return session.copy(nextExpectedEpochMillis = null)
        val last = session.points.lastOrNull() ?: return session
        val relevant = others.filter { it.id != session.id && it.stationId == session.stationId && it.patternId == session.patternId }
        val model = learn(relevant + session, session.serviceType, last.secondsOfDay, last.epochMillis)
        val valid = model?.takeIf {
            last.secondsOfDay + it.seconds < TimeUtils.SECONDS_PER_DAY &&
                ObservationTimeBand.fromSeconds(last.secondsOfDay) == ObservationTimeBand.fromSeconds(last.secondsOfDay + it.seconds)
        }
        return session.copy(nextExpectedEpochMillis = valid?.let { last.epochMillis + it.seconds * 1000L })
    }

    fun forecastAccuracy(sessions: List<HeadwaySession>, now: Long): String {
        val errors = sessions.distinctBy { it.id }.flatMap { it.points.zipWithNext() }
            .sortedBy { it.second.epochMillis }.mapNotNull { (a, b) ->
            val predicted = b.predictedEpochMillis ?: return@mapNotNull null
            if (b.epochMillis > now || now - b.epochMillis > 30 * CalibrationLearning.DAY_MILLIS ||
                b.epochMillis - a.epochMillis < 30_000 || predicted <= a.epochMillis) null
            else abs(b.epochMillis - predicted) / 1000.0
        }.takeLast(50)
        return if (errors.isEmpty()) "尚无已完成的间隔预测验证；记录后续班次后自动核对。"
        else "近30天间隔预测验证 ${errors.size} 次 · 平均误差 ${errors.average().roundToInt()} 秒（仅代表已记录样本）"
    }

    fun active(sessions: List<HeadwaySession>, station: String, pattern: String): HeadwaySession? =
        sessions.lastOrNull { !it.ended && it.stationId == station && it.patternId == pattern }

    data class Learned(val seconds: Int, val intervals: Int, val days: Int)
    private data class Interval(val gap: Int, val at: Int, val epoch: Long, val date: String)

    private fun intervals(sessions: List<HeadwaySession>, service: String, target: Int, now: Long): List<Interval> =
        sessions.distinctBy { it.id }.filter { it.serviceType == service }.flatMap { session ->
            session.points.zipWithNext().mapNotNull { (a, b) ->
                val millis = b.epochMillis - a.epochMillis
                val gap = b.secondsOfDay - a.secondsOfDay
                if (gap !in MIN_GAP..MAX_GAP || abs(millis / 1000 - gap) > 2 || millis <= 0 ||
                    a.secondsOfDay !in 0 until TimeUtils.SECONDS_PER_DAY || b.secondsOfDay !in 0 until TimeUtils.SECONDS_PER_DAY ||
                    b.epochMillis > now || now - b.epochMillis > 90 * CalibrationLearning.DAY_MILLIS ||
                    ObservationTimeBand.fromSeconds(a.secondsOfDay) != ObservationTimeBand.fromSeconds(b.secondsOfDay) ||
                    ObservationTimeBand.fromSeconds(b.secondsOfDay) != ObservationTimeBand.fromSeconds(target) ||
                    abs(b.secondsOfDay - target) > 3600) null
                else Interval(gap, b.secondsOfDay, b.epochMillis, session.serviceDate)
            }
        }

    fun learn(sessions: List<HeadwaySession>, service: String, target: Int, now: Long): Learned? {
        val values = intervals(sessions, service, target, now)
        if (values.size < 2) return null
        val median = median(values.map { it.gap.toDouble() })
        val mad = median(values.map { abs(it.gap - median) })
        val accepted = values.filter { abs(it.gap - median) <= maxOf(30.0, mad * 3) }
        if (accepted.size < 2 || mad > median * 0.25) return null
        fun weight(i: Interval): Double = exp(-(now - i.epoch).toDouble() / (14 * CalibrationLearning.DAY_MILLIS)) /
            (1 + abs(i.at - target) / 1800.0)
        val learned = (accepted.sumOf { it.gap * weight(it) } / accepted.sumOf { weight(it) }).roundToInt()
        return Learned(learned, accepted.size, accepted.map { it.date }.distinct().size)
    }

    fun report(sessions: List<HeadwaySession>, service: String, target: Int, now: Long): String {
        val model = learn(sessions, service, target, now)
        if (model != null) return "学习间隔约 ${model.seconds / 60}分${model.seconds % 60}秒 · ${model.intervals} 个有效间隔 · ${model.days} 天\n结合近期到站锚点推算未来30分钟；没有有效锚点时继续使用原校准。"
        val count = intervals(sessions, service, target, now).size
        return if (count < 2) "已有 $count 个适用间隔；至少连续记录3班，才能学习2个间隔。"
        else "间隔波动较大，暂不用于预测；请继续积累相近时段的记录。"
    }

    /** 间隔来自连续观察；相位只来自两小时内明确匹配班次的现场锚点。 */
    fun adjust(original: UserObservationAverage?, sessions: List<HeadwaySession>, observations: List<ArrivalObservation>,
               times: List<Int>, service: String, target: Int, now: Long): UserObservationAverage? {
        val model = learn(sessions, service, target, now) ?: return original
        // 组内已亲眼见到的车已经发生，不能继续按旧基础时刻显示成未来班次。
        val observed = sessions.filter { it.serviceType == service }.flatMap { s ->
            val first = times.indexOf(s.referenceBaseSeconds)
            if (first < 0) emptyList() else s.points.mapIndexedNotNull { i, p ->
                if (times.getOrNull(first + i) == target && now - p.epochMillis in 0..LIVE_MILLIS) p else null
            }
        }.maxByOrNull { it.epochMillis }
        if (observed != null) return UserObservationAverage(observed.secondsOfDay - target,
            model.intervals, UserCalibrationScope.LEARNED_HEADWAY, ObservationTimeBand.fromSeconds(target), service)
        data class Anchor(val baseIndex: Int, val seconds: Int, val epoch: Long)
        val sessionAnchors = sessions.filter { it.serviceType == service }.mapNotNull { session ->
            val firstIndex = times.indexOf(session.referenceBaseSeconds)
            val last = session.points.lastOrNull() ?: return@mapNotNull null
            val index = firstIndex + session.points.lastIndex
            if (firstIndex < 0 || index !in times.indices || now - last.epochMillis !in 0..LIVE_MILLIS) null
            else Anchor(index, last.secondsOfDay, last.epochMillis)
        }
        val observationAnchors = observations.mapNotNull { o ->
            val index = times.indexOf(o.prediction?.baseSeconds)
            val epoch = o.observedEpochMillis ?: return@mapNotNull null
            val seconds = CalibrationLearning.seconds(o) ?: return@mapNotNull null
            if (!o.liveArrival || !o.matchConfirmed || o.serviceType != service || index < 0 ||
                now - epoch !in 0..LIVE_MILLIS) null else Anchor(index, seconds, epoch)
        }
        val targetIndex = times.indexOf(target)
        val anchor = (sessionAnchors + observationAnchors).filter {
            it.baseIndex <= targetIndex && target - times[it.baseIndex] in 0..1800 &&
                ObservationTimeBand.fromSeconds(it.seconds) == ObservationTimeBand.fromSeconds(target)
        }.maxByOrNull { it.epoch } ?: return original
        val proposed = anchor.seconds + (targetIndex - anchor.baseIndex) * model.seconds
        if (proposed - anchor.seconds !in 0..1800) return original
        val correction = proposed - target
        // 连续无漏车记录确定了班次序号，不能按普通偏差限幅破坏学到的间隔。
        if (abs(correction) > LIVE_MILLIS / 1000) return original
        return UserObservationAverage(correction,
            model.intervals, UserCalibrationScope.LEARNED_HEADWAY,
            ObservationTimeBand.fromSeconds(target), service)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return (sorted[(sorted.size - 1) / 2] + sorted[sorted.size / 2]) / 2
    }
}
