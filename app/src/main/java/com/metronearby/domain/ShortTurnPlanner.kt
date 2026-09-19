package com.metronearby.domain

import com.metronearby.data.model.HeadwayRule
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.Service
import com.metronearby.data.model.ServiceTypes
import com.metronearby.data.model.ShortTurn

/**
 * 把用户填写的区间车折算成一条可参与推算的交路（[Pattern]）。
 *
 * 用户填的是「几点、从哪、到哪有一班区间车」，而 [ArrivalEstimator] 消费的是
 * 「交路 + 首末班 + 分时段间隔」。换算集中在这里，因此整条链路可以纯 JVM 测试，
 * 也保证界面层不需要理解时刻表结构。
 *
 * 换算口径：
 * - 逐班时刻原样保留：相邻两班的间隔直接成为一条间隔规则，于是起点站的推算结果
 *   与用户填写的时刻逐班一致，中间站再各自叠加运行偏移。
 * - 只填一班时没有间隔可推导，用 [DEFAULT_INTERVAL_SECONDS] 兜底，
 *   保证该交路至少能发出一班车。
 * - 方向由起终点在站序中的先后自动推导，用户不需要选。
 * - 新增时工作日与周末共用同一份初始时刻；用户可随后在班次表管理页分别覆盖两种日型。
 */
object ShortTurnPlanner {

    /** 用户只填了一班、无法推导间隔时的兜底发车间隔（10 分钟） */
    const val DEFAULT_INTERVAL_SECONDS = 600

    /** 区间车交路的固定名称，界面上的「区间车」标签由 isShortTurn 决定，不读这里 */
    const val PATTERN_NAME = "区间车"

    /** 兜底间隔规则覆盖全天，避免只填一班时交路发不出车 */
    private const val ALL_DAY_FROM = "00:00"
    private const val ALL_DAY_TO = "24:00"

    private const val FALLBACK_FORWARD_DIRECTION_ID = "forward"
    private const val FALLBACK_REVERSE_DIRECTION_ID = "reverse"

    /** 折算结果：要么得到一个交路，要么得到一条可直接展示给用户的拒绝原因。 */
    sealed interface Plan {
        data class Planned(val pattern: Pattern) : Plan
        data class Rejected(val reason: String) : Plan
    }

    /**
     * 校验并折算单条区间车。
     *
     * 校验项与用户能填错的东西一一对应：起终点是否为本线路已有站、起终点是否相同、
     * 发车时刻是否为 `HH:mm`。任何一项不满足都返回 [Plan.Rejected]，界面直接展示原因。
     */
    fun plan(line: MetroLine, shortTurn: ShortTurn): Plan {
        if (shortTurn.startStationId == shortTurn.endStationId) {
            return Plan.Rejected("区间车的起点站与终点站不能相同")
        }

        val startIndex = line.orderIndexOf(shortTurn.startStationId)
        if (startIndex < 0) return Plan.Rejected("起点站不在该线路内")

        val endIndex = line.orderIndexOf(shortTurn.endStationId)
        if (endIndex < 0) return Plan.Rejected("终点站不在该线路内")

        val rawTimes = shortTurn.departures.map { it.trim() }.filter { it.isNotEmpty() }
        if (rawTimes.isEmpty()) return Plan.Rejected("请至少填写一个发车时刻")

        val parsed = rawTimes.map { TimeUtils.parseClockTime(it) }
        if (parsed.any { it == null }) return Plan.Rejected("时刻格式应为 HH:mm，例如 08:30")

        val times = parsed.filterNotNull().distinct().sorted()
        val firstText = TimeUtils.formatSecondsOfDay(times.first())
        val lastText = TimeUtils.formatSecondsOfDay(times.last())

        return Plan.Planned(
            Pattern(
                id = patternId(shortTurn, firstText, lastText),
                name = PATTERN_NAME,
                startStationId = shortTurn.startStationId,
                endStationId = shortTurn.endStationId,
                directionId = resolveDirectionId(line, startIndex, endIndex),
                directionLabel = null,
                isShortTurn = true,
                services = buildServices(times)
            )
        )
    }

    /**
     * 批量折算，跳过不合法的条目。
     *
     * 用在只有「能算出班次」才有意义的路径（如合并进线路数据）；
     * 需要向用户解释哪里填错时，请逐条调用 [plan]。
     */
    fun patterns(line: MetroLine, shortTurns: List<ShortTurn>): List<Pattern> =
        shortTurns.mapNotNull { (plan(line, it) as? Plan.Planned)?.pattern }

    /**
     * 交路 id 由内容推导，保证同一条区间车每次折算结果一致（用户重新打开设置页时
     * 观察到的时间不会因为 id 变化而丢失）。
     */
    private fun patternId(shortTurn: ShortTurn, firstText: String, lastText: String): String =
        "user-shortturn-${shortTurn.startStationId}-${shortTurn.endStationId}-" +
            "${firstText.replace(":", "")}-${lastText.replace(":", "")}"

    /**
     * 方向沿用「站序先后关系相同」的内置交路，这样区间车会与同向的全程车归为同一组，
     * 界面上仍是「开往 X」的两个方向，而不是多出一组意义不明的方向。
     */
    private fun resolveDirectionId(line: MetroLine, startIndex: Int, endIndex: Int): String {
        val forward = startIndex < endIndex
        val reference = line.patterns.firstOrNull { pattern ->
            val patternStart = line.orderIndexOf(pattern.startStationId)
            val patternEnd = line.orderIndexOf(pattern.endStationId)
            patternStart >= 0 && patternEnd >= 0 && (patternStart < patternEnd) == forward
        }
        if (reference != null) return reference.directionId
        return if (forward) FALLBACK_FORWARD_DIRECTION_ID else FALLBACK_REVERSE_DIRECTION_ID
    }

    private fun buildServices(times: List<Int>): List<Service> {
        val first = TimeUtils.formatSecondsOfDay(times.first())
        val last = TimeUtils.formatSecondsOfDay(times.last())
        val headways = headwaysOf(times)
        return listOf(
            Service(
                serviceType = ServiceTypes.WEEKDAY,
                firstDeparture = first,
                lastDeparture = last,
                headways = headways
            ),
            Service(
                serviceType = ServiceTypes.WEEKEND,
                firstDeparture = first,
                lastDeparture = last,
                headways = headways
            )
        )
    }

    /**
     * 相邻两班的间隔逐条成为间隔规则，于是 [ArrivalEstimator.buildDepartureSeconds]
     * 逐班推进时能精确复现用户填写的时刻。
     */
    private fun headwaysOf(times: List<Int>): List<HeadwayRule> {
        if (times.size < 2) {
            return listOf(HeadwayRule(ALL_DAY_FROM, ALL_DAY_TO, DEFAULT_INTERVAL_SECONDS))
        }
        return times.zipWithNext { earlier, later ->
            HeadwayRule(
                from = TimeUtils.formatSecondsOfDay(earlier),
                to = TimeUtils.formatSecondsOfDay(later),
                intervalSeconds = later - earlier
            )
        }
    }
}
