package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.Service
import com.metronearby.data.model.UserOverrides
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ArrivalSource {
    /** 来自真实发车序列（内置或用户录入） */
    EXACT,

    /** 首末班车 + 分时段发车间隔推算得到 */
    ESTIMATED
}

data class ArrivalItem(
    val stationId: String,
    val patternId: String,
    val patternName: String,
    val directionId: String,
    val isShortTurn: Boolean,
    val terminalStationId: String,
    val terminalStationName: String,
    val arrivalSecondsOfDay: Int,
    val waitSeconds: Int,
    val source: ArrivalSource,
    /** 推算使用的运行偏移；本站精确序列不使用它，缺少辅助数据时为 0。 */
    val offsetSeconds: Int,
    /** 该班是该交路当天发车序列的第一班（首班车） */
    val isFirstDeparture: Boolean = false,
    /**
     * 该班是该交路当天发车序列的最后一班（末班车）。
     *
     * 当天只发一班时它与 [isFirstDeparture] 同时为 true。
     */
    val isLastDeparture: Boolean = false,
    /** 用户观测聚合出的平均偏差；null 表示该站该交路还没有用户样本 */
    val userAverage: UserObservationAverage? = null
)

/** 用户观测产生的校准结果；保留旧名称以兼容已有调用，基础预计不会被改写。 */
data class UserObservationAverage(
    /** 平均偏差（秒）。正数表示实际到站比推算晚 */
    val averageOffsetSeconds: Int,
    /** 参与平均的观测条数 */
    val sampleCount: Int,
    val scope: UserCalibrationScope = UserCalibrationScope.ALL_DAY,
    val timeBand: ObservationTimeBand? = null,
    val serviceType: String? = null
)

/**
 * 用户口径的到站时刻 = 推算时刻 + 平均偏差；没有任何样本时为 null。
 */
val ArrivalItem.userArrivalSecondsOfDay: Int?
    get() = userAverage?.let { arrivalSecondsOfDay + it.averageOffsetSeconds }

/**
 * 一个方向的到站信息。同一 directionId 下的全程车与区间车已合并排序。
 */
data class DirectionArrivals(
    val directionId: String,
    val directionLabel: String,
    val terminalStationId: String,
    val terminalStationName: String,
    val arrivals: List<ArrivalItem>
)

/** 本站一个可乘方向的完整展示状态；收车后 [arrivals] 为空但方向仍保留。 */
data class DirectionSchedule(
    val directionId: String,
    val directionLabel: String,
    val arrivals: List<ArrivalItem>,
    val serviceWindow: StationServiceWindow?
)

/**
 * 某站当天（某个 serviceType）的首末班车时刻，跨该站可乘的所有交路取最早与最晚。
 *
 * 它描述的是"今天这个站还有没有车"：当前时刻晚于 [lastSecondsOfDay] 即已收车，
 * 此时界面可以据此提示首班时刻，而不是干巴巴地说"没有班次"。
 */
data class StationServiceWindow(
    val firstSecondsOfDay: Int,
    val lastSecondsOfDay: Int
) {
    /**
     * 当前时刻是否已过末班车。
     *
     * 刻意用「大于」：末班车到站那一刻仍算有车（乘客还可能赶上），超过之后才算收车。
     * 凌晨（早于首班）返回 false，因为那时首班车就在未来。
     */
    fun isFinishedAt(nowSecondsOfDay: Int): Boolean = nowSecondsOfDay > lastSecondsOfDay
}

/**
 * 到站推算。
 *
 * 核心公式：到站时刻 = 交路始发站发车序列 + 本站相对始发站的运行偏移。
 *
 * 其中运行偏移 offset 优先由"官网各站首末班车"反推，其次由站间时长累加。
 *
 * 用户在站台记录的实际到站时间不会改写基础推算；它会形成分时段平均或短期锚点，
 * 在用户选择校准口径时使用（见 [UserObservationAverage]）。
 *
 * 区间车不需要任何特殊分支：它只是一个起终点不同的 pattern，
 * 各交路各自算出未来若干班后按方向合并排序，区间车便会自然混排在对应方向里。
 *
 * 两个方向由 directionId 区分；若当前站恰好是某交路的终点站，该交路不会返回结果
 * ——列车在此清客折返，乘客坐不了这一班，因此它对该站没有意义。
 * 环线全程车是例外：数据上的"终点站"只是站序闭合点，列车会继续开行。
 * 环线上的区间车仍在自己的终点清客，必须过滤。
 *
 * 列车到站后要在站台停留一段时间（开门上下客），这段时间内它依然是"本站当前那一班"，
 * 不该被下一班立刻顶掉。因此筛选口径是 `到站时刻 >= 当前时刻 - [dwellSeconds]`：
 * 窗口内已到站的班次予以保留，对应的 [ArrivalItem.waitSeconds] 为负值
 * （表示"已到站多少秒"），由 UI 负责区分呈现。
 */
class ArrivalEstimator(
    private val line: MetroLine,
    private val overrides: UserOverrides? = null,
    dwellSeconds: Int = DEFAULT_DWELL_SECONDS
) {

    /**
     * 停站窗口：列车到站后仍视为"本站当前班次"的秒数。
     *
     * 0 表示关闭窗口，回到"只保留未来班次"的口径；负值按 0 处理。
     */
    val dwellSeconds: Int = dwellSeconds.coerceAtLeast(0)

    /**
     * 是否为环线：数据里存在"站序末站 → 站序首站"的闭合段。
     *
     * 环线在数据上仍需指定一个站序起点（如西局之于 10 号线），但列车经过它时会继续开行，
     * 它并非真正的折返终点，因此不能套用"终点站不返回结果"的规则。
     */
    private val isRingLine: Boolean by lazy {
        val order = line.stationOrder
        order.size >= 2 && line.segments.any {
            it.from == order.last() && it.to == order.first()
        }
    }

    /**
     * 不区分方向的扁平列表，主要用于调试与测试。
     *
     * 结果可能包含停站窗口内已到站的班次（[ArrivalItem.waitSeconds] 为负）。
     */
    fun nextArrivals(
        stationId: String,
        serviceType: String,
        nowSecondsOfDay: Int,
        limit: Int = DEFAULT_LIMIT,
        currentEpochMillis: Long? = null
    ): List<ArrivalItem> =
        nextArrivalsByDirection(stationId, serviceType, nowSecondsOfDay, limit, currentEpochMillis)
            .flatMap { it.arrivals }
            .sortedWith(compareBy({ it.arrivalSecondsOfDay }, { it.patternId }))
            .take(limit)

    /**
     * 按方向分组返回。UI 的"某站某线两个方向倒计时"直接消费这个结果。
     *
     * 每个方向最多 [perDirectionLimit] 班，含停站窗口内已到站的班次。
     */
    fun nextArrivalsByDirection(
        stationId: String,
        serviceType: String,
        nowSecondsOfDay: Int,
        perDirectionLimit: Int = DEFAULT_LIMIT,
        currentEpochMillis: Long? = null,
        displayMode: ArrivalDisplayMode = ArrivalDisplayMode.DEFAULT
    ): List<DirectionArrivals> {
        if (line.orderIndexOf(stationId) < 0) return emptyList()

        val grouped = LinkedHashMap<String, MutableList<ArrivalItem>>()
        for (pattern in line.patterns) {
            if (overrides?.isPatternEnabled(pattern.id) == false) continue
            val arrivals = arrivalsForPattern(pattern, stationId, serviceType, nowSecondsOfDay, perDirectionLimit, currentEpochMillis, displayMode)
            if (arrivals.isEmpty()) continue
            grouped.getOrPut(pattern.directionId) { mutableListOf() }.addAll(arrivals)
        }

        return grouped.map { (directionId, items) ->
            val arrivals = items
                .sortedWith(compareBy({ it.present(displayMode, nowSecondsOfDay).arrivalSecondsOfDay }, { it.patternId }))
                .take(perDirectionLimit)
            val head = arrivals.first()
            DirectionArrivals(
                directionId = directionId,
                directionLabel = resolveDirectionLabel(directionId, stationId, head),
                terminalStationId = head.terminalStationId,
                terminalStationName = head.terminalStationName,
                arrivals = arrivals
            )
        }
    }

    /**
     * 某个交路在该站未来最多 [limit] 班车。
     */
    private fun arrivalsForPattern(
        pattern: Pattern,
        stationId: String,
        serviceType: String,
        nowSecondsOfDay: Int,
        limit: Int,
        currentEpochMillis: Long?,
        displayMode: ArrivalDisplayMode
    ): List<ArrivalItem> {
        val resolved = resolvePatternArrivals(pattern, stationId, serviceType)
            ?: return emptyList()
        val times = resolved.times

        // 该交路当天发车序列的首末两班，用于给这两班打「首班 / 末班」标志
        val firstSeconds = times.first()
        val lastSeconds = times.last()

        val upcoming = if (displayMode == ArrivalDisplayMode.SYSTEM_ESTIMATE) {
            times.filter { it >= nowSecondsOfDay - dwellSeconds }.take(limit)
        } else {
            val continuous = overrides?.headwaySessions.orEmpty().any {
                it.stationId == stationId && it.patternId == pattern.id && it.serviceType == serviceType &&
                    currentEpochMillis != null && it.points.lastOrNull()?.let { p ->
                        currentEpochMillis - p.epochMillis in 0..HeadwayLearning.LIVE_MILLIS
                    } == true
            }
            val allowance = if (continuous) (HeadwayLearning.LIVE_MILLIS / 1000).toInt() else CalibrationLearning.MAX_OFFSET
            // 推迟的班次不能被基础时刻提前删除；提前的班次也不能长期显示“已到站”。
            val horizon = (times.filter { it >= nowSecondsOfDay }.take(limit).lastOrNull() ?: times.last()) +
                2 * allowance
            times.filter { it >= nowSecondsOfDay - dwellSeconds - allowance && it <= horizon }
        }
        if (upcoming.isEmpty()) return emptyList()

        val terminal = line.stationById(pattern.endStationId)
        return upcoming.map { arrivalSeconds ->
            ArrivalItem(
                stationId = stationId,
                patternId = pattern.id,
                patternName = pattern.name,
                directionId = pattern.directionId,
                isShortTurn = pattern.isShortTurn,
                terminalStationId = pattern.endStationId,
                terminalStationName = terminal?.name ?: pattern.endStationId,
                arrivalSecondsOfDay = arrivalSeconds,
                waitSeconds = arrivalSeconds - nowSecondsOfDay,
                source = resolved.source,
                offsetSeconds = resolved.offsetSeconds,
                isFirstDeparture = arrivalSeconds == firstSeconds,
                isLastDeparture = arrivalSeconds == lastSeconds,
                userAverage = observationCalibration(
                    stationId = stationId,
                    pattern = pattern,
                    times = times,
                    serviceType = serviceType,
                    targetArrivalSeconds = arrivalSeconds,
                    currentEpochMillis = currentEpochMillis
                ).let { calibration ->
                    if (currentEpochMillis == null) calibration else HeadwayLearning.adjust(calibration,
                        overrides?.headwaySessions.orEmpty().filter { it.stationId == stationId && it.patternId == pattern.id },
                        overrides?.arrivalObservations.orEmpty().filter { it.stationId == stationId && it.patternId == pattern.id },
                        times, serviceType, arrivalSeconds, currentEpochMillis)
                }
            )
        }.filter { it.present(displayMode, nowSecondsOfDay).waitSeconds >= -dwellSeconds }
            .sortedBy { it.present(displayMode, nowSecondsOfDay).arrivalSecondsOfDay }.take(limit)
    }

    /**
     * 某站当天的首末班车时刻，跨该站可乘的所有交路取最早与最晚。
     *
     * 返回 null 表示该站当天没有任何可推算的班次（例如线路数据里没有这一天的时刻表），
     * 此时"没有班次"与"已收车"是两回事，调用方不该把它当成收车来提示。
     */
    fun serviceWindow(stationId: String, serviceType: String, directionId: String? = null): StationServiceWindow? {
        if (line.orderIndexOf(stationId) < 0) return null

        var first: Int? = null
        var last: Int? = null
        for (pattern in line.patterns) {
            if (directionId != null && pattern.directionId != directionId) continue
            if (overrides?.isPatternEnabled(pattern.id) == false) continue
            val times = resolvePatternArrivals(pattern, stationId, serviceType)?.times ?: continue
            if (times.isEmpty()) continue

            val patternFirst = times.first()
            val patternLast = times.last()
            first = first?.coerceAtMost(patternFirst) ?: patternFirst
            last = last?.coerceAtLeast(patternLast) ?: patternLast
        }

        if (first == null || last == null) return null
        return StationServiceWindow(firstSecondsOfDay = first, lastSecondsOfDay = last)
    }

    /** 一个交路在本站的完整推算结果：全天班次 + 偏移 + 来源。 */
    private data class PatternArrivals(
        val offsetSeconds: Int,
        val times: List<Int>,
        val source: ArrivalSource
    )

    /**
     * 算出某交路在本站的全天到站时刻，不做停站窗口过滤、不截断数量。
     *
     * 返回 null 表示该交路对本站无意义：缺该 serviceType 的时刻表、站点不在运行区间内、
     * 或本站是线性线路上的交路终点站（列车清客折返，乘客坐不了）。
     *
     * 把"全天班次"和"取最近几班"分开，是为了让 [serviceWindow] 能复用同一份计算，
     * 避免首末班时刻与界面展示的班次来自两套互相独立的推算。
     */
    private fun resolvePatternArrivals(
        pattern: Pattern,
        stationId: String,
        serviceType: String
    ): PatternArrivals? {
        if (!canBoard(pattern, stationId)) return null
        val stationIndex = line.orderIndexOf(stationId)
        val startIndex = line.orderIndexOf(pattern.startStationId)
        val service = pattern.services.firstOrNull { it.serviceType == serviceType }

        val managedTrips = overrides?.serviceTrips?.get(pattern.id)?.get(serviceType).orEmpty()
        if (managedTrips.isNotEmpty()) {
            val times = managedTrips.mapNotNull {
                TripStationPlanner.arrivalSeconds(line, pattern, serviceType, it, stationId)
            }.distinct().sorted()
            if (times.isNotEmpty()) {
                val firstOrigin = TimeUtils.parseClockTime(managedTrips.first().departureTime)
                val offset = if (firstOrigin == null) 0 else times.first() - firstOrigin
                return PatternArrivals(offset, times, ArrivalSource.EXACT)
            }
        }

        val managedDepartures = overrides?.serviceDepartures?.get(pattern.id)?.get(serviceType)
            ?.mapNotNull { TimeUtils.parseClockTime(it) }
            ?.distinct()
            ?.sorted()
            .orEmpty()
        if (managedDepartures.isNotEmpty()) {
            // 班次表管理页保存的是交路起点时刻；本站时刻统一叠加运行偏移。
            // 若该日型原本没有 service（常见于仅工作日运行的区间车），仍可用站间时长计算。
            val offset = service?.let { resolveBaseOffset(pattern, it, stationId, startIndex, stationIndex) }
                ?: offsetBySegments(startIndex, stationIndex)
                ?: return null
            return PatternArrivals(offset, managedDepartures.map { it + offset }, ArrivalSource.EXACT)
        }

        if (service == null) return null

        val exactTimes = resolveExactDepartures(stationId, pattern.id)
        if (exactTimes != null) {
            // 本站已有完整序列，不应因为缺少推算用的间隔或站间数据而丢弃。
            return PatternArrivals(
                offsetSeconds = resolveBaseOffset(pattern, service, stationId, startIndex, stationIndex) ?: 0,
                times = exactTimes,
                source = ArrivalSource.EXACT
            )
        }

        val schedule = DepartureSchedulePlanner.fromService(service)
        if (schedule.isEmpty()) return null
        val offset = resolveBaseOffset(pattern, service, stationId, startIndex, stationIndex)
            ?: return null
        return PatternArrivals(
            offsetSeconds = offset,
            times = schedule.map { it + offset },
            source = ArrivalSource.ESTIMATED
        )
    }

    /**
     * 方向标签：优先取数据里显式写的 directionLabel，
     * 否则用该方向上"离当前站最远的终点站"来命名。
     */
    /** 不依赖当前时刻；收车后仍可配置通勤方向。 */
    fun boardingDirections(stationId: String): Map<String, String> =
        line.patterns.filter { canBoard(it, stationId) }
            .associate { it.directionId to directionLabel(it.directionId, stationId, it.endStationId) }

    private fun canBoard(pattern: Pattern, stationId: String): Boolean {
        val station = line.orderIndexOf(stationId)
        val start = line.orderIndexOf(pattern.startStationId)
        val end = line.orderIndexOf(pattern.endStationId)
        return station >= 0 && start >= 0 && end >= 0 &&
            station in minOf(start, end)..maxOf(start, end) &&
            ((isRingLine && !pattern.isShortTurn) || station != end)
    }

    private fun resolveDirectionLabel(
        directionId: String,
        stationId: String,
        head: ArrivalItem
    ): String = directionLabel(directionId, stationId, head.terminalStationId)

    private fun directionLabel(directionId: String, stationId: String, fallbackTerminal: String): String {
        val patterns = line.patterns.filter { it.directionId == directionId }

        patterns.firstNotNullOfOrNull { it.directionLabel?.takeIf { label -> label.isNotBlank() } }
            ?.let { return it }

        val stationIndex = line.orderIndexOf(stationId)
        val farthestTerminalId = patterns
            .map { it.endStationId to line.orderIndexOf(it.endStationId) }
            .filter { it.second >= 0 }
            .maxByOrNull { abs(it.second - stationIndex) }
            ?.first
            ?: fallbackTerminal

        val name = line.stationById(farthestTerminalId)?.name ?: farthestTerminalId
        return "开往 $name"
    }

    /**
     * offset 来源优先级：各站首末班车反推 > 站间时长累加。
     */
    private fun resolveBaseOffset(
        pattern: Pattern,
        service: Service,
        stationId: String,
        startIndex: Int,
        stationIndex: Int
    ): Int? {
        val published = line.stationServiceTimes[stationId]?.get(pattern.id)
        if (published != null) {
            val publishedFirst = TimeUtils.parseToSecondsOfDay(published.firstDeparture)
            val originFirst = TimeUtils.parseToSecondsOfDay(service.firstDeparture)
            return publishedFirst - originFirst
        }
        return offsetBySegments(startIndex, stationIndex)
    }

    private fun offsetBySegments(startIndex: Int, stationIndex: Int): Int? {
        if (startIndex == stationIndex) return 0

        val forward = startIndex < stationIndex
        val slice = if (forward) {
            line.stationOrder.subList(startIndex, stationIndex + 1)
        } else {
            line.stationOrder.subList(stationIndex, startIndex + 1).reversed()
        }

        var total = 0
        for (i in 0 until slice.size - 1) {
            val from = slice[i]
            val to = slice[i + 1]
            val segment = line.segments.firstOrNull { it.from == from && it.to == to }
                ?: line.segments.firstOrNull { it.from == to && it.to == from }
                ?: return null
            total += segment.runSeconds
        }
        return total
    }

    /**
     * 用户校准优先级：两小时内的现场锚点 > 同运营日同通勤时段 > 同运营日全天 > 全部历史。
     * 新版记录交由 CalibrationLearning 学习；基础到站时刻保持不变。
     */
    private fun observationCalibration(
        stationId: String,
        pattern: Pattern,
        times: List<Int>,
        serviceType: String,
        targetArrivalSeconds: Int,
        currentEpochMillis: Long?
    ): UserObservationAverage? {
        if (times.isEmpty()) return null
        val observations = overrides?.arrivalObservations.orEmpty().filter {
            it.stationId == stationId && it.patternId == pattern.id
        }
        // 有新版记录后启用渐进学习；旧版记录不丢失，以较低权重参与。
        if (currentEpochMillis != null && observations.any { it.prediction != null }) {
            return CalibrationLearning.estimate(observations, times, serviceType, targetArrivalSeconds, currentEpochMillis)
        }
        val samples = overrides?.arrivalObservations.orEmpty().mapNotNull { observation ->
            if (observation.stationId != stationId || observation.patternId != pattern.id) return@mapNotNull null
            val observed = runCatching { TimeUtils.parseToSecondsOfDay(observation.observedTime) }
                .getOrNull() ?: return@mapNotNull null
            val observationTimes = observation.serviceType
                ?.takeIf { it != serviceType }
                ?.let { resolvePatternArrivals(pattern, stationId, it)?.times }
                ?: times
            val nearest = observationTimes.minByOrNull { abs(it - observed) } ?: return@mapNotNull null
            val deviation = observed - nearest
            if (abs(deviation) > MAX_OBSERVATION_DEVIATION_SECONDS) return@mapNotNull null
            ObservationSample(observation, observed, nearest, deviation)
        }
        if (samples.isEmpty()) return null

        val anchor = currentEpochMillis?.let { now ->
            samples.asSequence()
                .filter { it.observation.serviceType == serviceType }
                .filter { it.nearestBaseSeconds <= targetArrivalSeconds }
                .filter { it.isLiveObservation() }
                .filter {
                    val age = now - (it.observation.recordedAtEpochMillis ?: return@filter false)
                    age in 0..ANCHOR_VALID_MILLIS
                }
                .maxByOrNull { it.observation.recordedAtEpochMillis ?: Long.MIN_VALUE }
        }
        if (anchor != null) {
            return UserObservationAverage(
                averageOffsetSeconds = anchor.deviationSeconds,
                sampleCount = 1,
                scope = UserCalibrationScope.RECENT_ANCHOR,
                timeBand = ObservationTimeBand.fromSeconds(anchor.observedSeconds),
                serviceType = serviceType
            )
        }

        val targetBand = ObservationTimeBand.fromSeconds(targetArrivalSeconds)
        val bandSamples = samples.filter {
            it.observation.serviceType == serviceType &&
                ObservationTimeBand.fromStorageKey(it.observation.timeBand) == targetBand
        }
        if (bandSamples.size >= MIN_SCOPED_SAMPLE_COUNT) {
            return bandSamples.average(UserCalibrationScope.TIME_BAND, targetBand, serviceType)
        }

        val serviceSamples = samples.filter { it.observation.serviceType == serviceType }
        if (serviceSamples.size >= MIN_SCOPED_SAMPLE_COUNT) {
            return serviceSamples.average(UserCalibrationScope.SERVICE_TYPE, null, serviceType)
        }

        return samples.average(UserCalibrationScope.ALL_DAY, null, null)
    }

    private data class ObservationSample(
        val observation: com.metronearby.data.model.ArrivalObservation,
        val observedSeconds: Int,
        val nearestBaseSeconds: Int,
        val deviationSeconds: Int
    ) {
        fun isLiveObservation(): Boolean {
            val recorded = observation.recordedSecondsOfDay ?: return false
            val observedNormalized = ((observedSeconds % TimeUtils.SECONDS_PER_DAY) + TimeUtils.SECONDS_PER_DAY) % TimeUtils.SECONDS_PER_DAY
            val recordedNormalized = ((recorded % TimeUtils.SECONDS_PER_DAY) + TimeUtils.SECONDS_PER_DAY) % TimeUtils.SECONDS_PER_DAY
            val direct = abs(observedNormalized - recordedNormalized)
            val circular = minOf(direct, TimeUtils.SECONDS_PER_DAY - direct)
            return circular <= MAX_LIVE_RECORDING_DIFFERENCE_SECONDS
        }
    }

    /**
     * 返回本站所有可乘方向，包括当前已无后续班次的方向。
     * 环线的站序起点和闭合点因此始终保留内外环；线性线路终点仍过滤清客方向。
     */
    fun directionSchedules(
        stationId: String,
        serviceType: String,
        nowSecondsOfDay: Int,
        perDirectionLimit: Int = DEFAULT_LIMIT,
        currentEpochMillis: Long? = null,
        displayMode: ArrivalDisplayMode = ArrivalDisplayMode.DEFAULT
    ): List<DirectionSchedule> {
        val arrivalsByDirection = nextArrivalsByDirection(
            stationId = stationId,
            serviceType = serviceType,
            nowSecondsOfDay = nowSecondsOfDay,
            perDirectionLimit = perDirectionLimit,
            currentEpochMillis = currentEpochMillis,
            displayMode = displayMode
        ).associateBy { it.directionId }

        return boardingDirections(stationId).map { (directionId, label) ->
            DirectionSchedule(
                directionId = directionId,
                directionLabel = label,
                arrivals = arrivalsByDirection[directionId]?.arrivals.orEmpty(),
                serviceWindow = serviceWindow(stationId, serviceType, directionId)
            )
        }
    }

    private fun List<ObservationSample>.average(
        scope: UserCalibrationScope,
        timeBand: ObservationTimeBand?,
        serviceType: String?
    ): UserObservationAverage {
        return UserObservationAverage(
            averageOffsetSeconds = map { it.deviationSeconds }.average().roundToInt(),
            sampleCount = size,
            scope = scope,
            timeBand = timeBand,
            serviceType = serviceType
        )
    }

    private fun resolveExactDepartures(stationId: String, patternId: String): List<Int>? {
        val userTimes = overrides?.exactDepartures?.get(stationId)?.get(patternId)
        val builtinTimes = line.exactDepartures[stationId]?.get(patternId)
        val raw = userTimes ?: builtinTimes ?: return null

        val parsed = raw
            .mapNotNull { runCatching { TimeUtils.parseToSecondsOfDay(it) }.getOrNull() }
            .distinct()
            .sorted()
        return parsed.ifEmpty { null }
    }

    companion object {
        const val DEFAULT_LIMIT = 3

        /**
         * 默认停站时长：30~45 秒是常见的地铁停站区间，取上界以免乘客刚跑到站台就被切走。
         *
         * 该值来自公开资料估算，不是实测契约；若后续接入真实停站数据，应改为可配置。
         */
        const val DEFAULT_DWELL_SECONDS = 45

        /**
         * 单条观测与最近推算班次的偏差超过该值时，视为用户记错了班次，不参与平均。
         *
         * 取 5 分钟：正常运营波动远小于它，而"把下一班误记成本班"这类错误通常超出它。
         */
        const val MAX_OBSERVATION_DEVIATION_SECONDS = 300

        /** 分组至少两条才独立成平均，否则回退到更宽的样本范围。 */
        const val MIN_SCOPED_SAMPLE_COUNT = 2

        /** 现场锚点只校准接下来两小时，过期后转为普通历史样本。 */
        const val ANCHOR_VALID_MILLIS = 2 * 60 * 60 * 1000L

        /** 保存动作与所填到站时刻相差超过 15 分钟，视为补录，不作为现场锚点。 */
        const val MAX_LIVE_RECORDING_DIFFERENCE_SECONDS = 15 * 60

    }
}
