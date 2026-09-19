package com.metronearby.domain

import com.metronearby.data.model.ArrivalObservation
import com.metronearby.data.model.ArrivalPredictionSnapshot
import java.util.Calendar
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** 离线学习与前瞻误差检验。记录归属由调用方按线路、站点、交路隔离。 */
object CalibrationLearning {
    const val DAY_MILLIS = 86_400_000L
    const val MAX_OFFSET = 600
    private const val ANCHOR_MILLIS = 7_200_000L
    private const val MANUAL_ANCHOR_LAG_MILLIS = 900_000L

    fun date(clock: Calendar): String = String.format(Locale.ROOT, "%04d-%02d-%02d",
        clock.get(Calendar.YEAR), clock.get(Calendar.MONTH) + 1, clock.get(Calendar.DAY_OF_MONTH))

    fun snapshot(item: ArrivalItem, clock: Calendar) = ArrivalPredictionSnapshot(
        item.arrivalSecondsOfDay, item.userArrivalSecondsOfDay, clock.timeInMillis,
        ServiceTypeResolver.secondsOfDay(clock), date(clock), ServiceTypeResolver.from(clock)
    )

    fun record(item: ArrivalItem, snapshot: ArrivalPredictionSnapshot, clock: Calendar,
               manualTime: String?, confirmed: Boolean): ArrivalObservation {
        val seconds = manualTime?.let { requireNotNull(TimeUtils.parseClockTime(it)) }
            ?: ServiceTypeResolver.secondsOfDay(clock)
        val epoch = clock.timeInMillis - (ServiceTypeResolver.secondsOfDay(clock) - seconds) * 1000L
        return ArrivalObservation(
            stationId = item.stationId, patternId = item.patternId,
            observedTime = if (manualTime == null) "%s:%02d".format(TimeUtils.formatSecondsOfDay(seconds), seconds % 60) else manualTime,
            recordedAt = date(clock), serviceType = ServiceTypeResolver.from(clock),
            timeBand = ObservationTimeBand.fromSeconds(seconds).storageKey,
            recordedAtEpochMillis = clock.timeInMillis,
            recordedSecondsOfDay = ServiceTypeResolver.secondsOfDay(clock),
            observedSecondsOfDay = seconds, observedEpochMillis = epoch,
            observationDate = date(clock), prediction = snapshot,
            matchConfirmed = confirmed && snapshot.serviceDate == date(clock),
            liveArrival = manualTime == null
        )
    }

    /** 同一天同一班重复记录只保留最近一次，防止反复点击提高样本量。 */
    fun append(existing: List<ArrivalObservation>, observation: ArrivalObservation): List<ArrivalObservation> {
        // 重复现场点击不能把已发生的到站挪到未来，再伪造一次“提前预测”。
        if (observation.liveArrival && existing.any { it.liveArrival && sameTrain(it, observation) }) return existing
        return existing.filterNot { sameTrain(it, observation) } + observation
    }

    private fun sameTrain(a: ArrivalObservation, b: ArrivalObservation): Boolean =
        a.stationId == b.stationId && a.patternId == b.patternId &&
            a.prediction != null && b.prediction != null && a.observationDate != null &&
            a.observationDate == b.observationDate && a.prediction.baseSeconds == b.prediction.baseSeconds

    private fun unique(observations: List<ArrivalObservation>): List<ArrivalObservation> =
        observations.sortedBy { it.recordedAtEpochMillis ?: Long.MIN_VALUE }
            .fold(emptyList()) { acc, observation -> append(acc, observation) }

    fun seconds(observation: ArrivalObservation): Int? = observation.observedSecondsOfDay
        ?.takeIf { it in 0 until TimeUtils.SECONDS_PER_DAY }
        ?: runCatching { TimeUtils.parseClockTime(observation.observedTime) }.getOrNull()

    data class Accuracy(val count: Int, val days: Int, val systemMae: Int, val userMae: Int) {
        val supportsLearning: Boolean get() = count >= 5 && days >= 2 && userMae < systemMae
    }

    /** 只比较同班、现场记录、至少提前30秒保存的预测；不拿拟合误差充当验证。 */
    fun accuracy(observations: List<ArrivalObservation>, now: Long): Accuracy? {
        val valid = unique(observations).filter { o ->
            val p = o.prediction
            val actual = o.observedEpochMillis
            o.matchConfirmed && o.liveArrival && p?.calibratedSeconds != null && actual != null &&
                seconds(o) != null && p.serviceDate == o.observationDate && p.serviceType == o.serviceType &&
                actual - p.capturedAtEpochMillis in 30_000..DAY_MILLIS && actual <= now &&
                now - actual <= 30 * DAY_MILLIS
        }.takeLast(50)
        if (valid.isEmpty()) return null
        return Accuracy(valid.size, valid.mapNotNull { it.observationDate }.distinct().size,
            valid.map { abs(seconds(it)!! - it.prediction!!.baseSeconds) }.average().roundToInt(),
            valid.map { abs(seconds(it)!! - it.prediction!!.calibratedSeconds!!) }.average().roundToInt())
    }

    fun report(observations: List<ArrivalObservation>, now: Long): String {
        val eligible = unique(observations).filter {
            it.matchConfirmed && it.prediction != null && seconds(it) != null &&
                it.observedEpochMillis?.let { epoch -> epoch <= now && now - epoch <= 90 * DAY_MILLIS } == true &&
                abs(seconds(it)!! - it.prediction!!.baseSeconds) <= MAX_OFFSET
        }
        val days = eligible.mapNotNull { it.observationDate }.distinct().size
        val header = "近90天已确认记录 ${eligible.size} 条 · 覆盖 $days 天"
        val stats = accuracy(observations, now) ?: return "$header\n尚无可比较的提前预测；在车到前打开班次，再记录实际到站。"
        val verdict = if (stats.supportsLearning) "已有改善证据，逐步提高历史权重"
            else if (stats.count < 5 || stats.days < 2) "验证样本不足，暂不判断改善"
            else "尚未改善，降低历史修正权重"
        return "$header\n近30天提前预测 ${stats.count} 次：系统平均误差 ${stats.systemMae} 秒，校准 ${stats.userMae} 秒。\n$verdict"
    }

    private data class Sample(val observation: ArrivalObservation, val base: Int, val offset: Int, val weight: Double)

    fun estimate(observations: List<ArrivalObservation>, times: List<Int>, service: String,
                 target: Int, now: Long): UserObservationAverage? {
        val samples = unique(observations).mapNotNull { o ->
            if (o.serviceType != null && o.serviceType != service) return@mapNotNull null
            val observed = seconds(o) ?: return@mapNotNull null
            val epoch = o.observedEpochMillis ?: o.recordedAtEpochMillis
            if (epoch != null && (epoch > now || now - epoch > 90 * DAY_MILLIS)) return@mapNotNull null
            val base = o.prediction?.baseSeconds ?: times.minByOrNull { abs(it - observed) } ?: return@mapNotNull null
            if (o.prediction != null && base !in times) return@mapNotNull null
            val offset = observed - base
            if (abs(offset) > if (o.matchConfirmed) MAX_OFFSET else 300) return@mapNotNull null
            val distance = abs(base - target).toDouble()
            val local = 1.0 / (1.0 + (distance / 1800.0) * (distance / 1800.0))
            val ageDays = epoch?.let { (now - it).toDouble() / DAY_MILLIS } ?: 30.0
            val quality = if (o.matchConfirmed) 1.0 else 0.25
            Sample(o, base, offset, quality * local * exp(-ageDays / 14.0) * if (o.serviceType == null) 0.5 else 1.0)
        }
        if (samples.isEmpty()) return null
        // 加权中位数/MAD压低孤立误记；当天锚点单独处理，可响应真实临时变化。
        val center = median(samples.map { it.offset.toDouble() to it.weight })
        val mad = median(samples.map { abs(it.offset - center) to it.weight })
        val accepted = samples.filter { abs(it.offset - center) <= maxOf(60.0, 3 * mad) }
        val history = accepted.sumOf { it.offset * it.weight } / accepted.sumOf { it.weight }
        // 使用相近日型与时刻的验证结果决定信任程度，其他时段不能替本时段背书。
        val evidence = accuracy(observations.filter {
            it.serviceType == service && abs((it.prediction?.baseSeconds ?: -100000) - target) <= 3600
        }, now)
        val trust = when {
            evidence?.supportsLearning == true -> 0.85
            evidence != null && evidence.count >= 5 && evidence.days >= 2 -> 0.2
            else -> 0.5
        }
        val effective = accepted.sumOf { it.weight }
        val historicalCorrection = history * trust * (effective / 3.0).coerceAtMost(1.0)
        val anchor = samples.filter { s ->
            val o = s.observation
            o.matchConfirmed && o.serviceType == service && s.base <= target &&
                anchorEpoch(o, now) != null
        }.maxByOrNull { it.observation.observedEpochMillis!! }
        val anchorWeight = anchor?.let { 0.8 * (1.0 - (now - it.observation.observedEpochMillis!!).toDouble() / ANCHOR_MILLIS) } ?: 0.0
        // 已确认的实际到站时刻就是该班车的事实值，不能再被历史平均稀释。
        // 后续班次仍使用衰减融合，避免一次临时延误长期平移整条时刻表。
        val correction = when {
            anchor == null -> historicalCorrection
            anchor.base == target -> anchor.offset.toDouble()
            else -> historicalCorrection * (1 - anchorWeight) + anchor.offset * anchorWeight
        }
        return UserObservationAverage(correction.roundToInt().coerceIn(-MAX_OFFSET, MAX_OFFSET),
            (accepted.map { it.observation } + listOfNotNull(anchor?.observation)).distinct().size,
            if (anchor == null) UserCalibrationScope.LEARNED_HISTORY else UserCalibrationScope.BLENDED_ANCHOR,
            ObservationTimeBand.fromSeconds(target), service)
    }

    /**
     * 现场按钮天然可作锚点；确认同班且在实际到站后 15 分钟内保存的手动时刻也可作锚点。
     * 手动记录仍不进入 [accuracy]，避免把事后填写当成提前预测验证。
     */
    private fun anchorEpoch(observation: ArrivalObservation, now: Long): Long? {
        val observed = observation.observedEpochMillis ?: return null
        if (now - observed !in 0 until ANCHOR_MILLIS) return null
        if (observation.liveArrival) return observed
        val recorded = observation.recordedAtEpochMillis ?: return null
        return observed.takeIf { recorded - observed in 0..MANUAL_ANCHOR_LAG_MILLIS }
    }

    private fun median(values: List<Pair<Double, Double>>): Double {
        val sorted = values.sortedBy { it.first }
        val halfway = sorted.sumOf { it.second } / 2
        var cumulative = 0.0
        for ((value, weight) in sorted) {
            cumulative += weight
            if (cumulative >= halfway) return value
        }
        return sorted.last().first
    }
}
