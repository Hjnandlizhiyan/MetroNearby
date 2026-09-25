package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.StationSubscription
import com.metronearby.data.model.UserOverrides

/** 常用站与订阅是同一份列表；以工程现有的换乘站身份规则去重。 */
object StationSubscriptions {
    fun find(items: List<StationSubscription>, name: String): StationSubscription? =
        items.firstOrNull { TransferStationResolver.normalize(it.stationName) == TransferStationResolver.normalize(name) }

    fun remove(items: List<StationSubscription>, name: String): List<StationSubscription> =
        items.filterNot { TransferStationResolver.normalize(it.stationName) == TransferStationResolver.normalize(name) }

    fun upsert(items: List<StationSubscription>, item: StationSubscription): List<StationSubscription> {
        if (TransferStationResolver.normalize(item.stationName).isEmpty()) return items
        val cleaned = item.copy(stationName = item.stationName.trim(), directionId = item.directionId.takeIf { item.lineId != null },
            tag = item.tag.trim(), preferredExit = item.preferredExit.trim(), note = item.note.trim())
        val index = items.indexOf(find(items, item.stationName))
        return if (index < 0) items + cleaned else items.toMutableList().apply { set(index, cleaned) }
    }

    fun normalize(items: List<StationSubscription>): List<StationSubscription> =
        items.fold(emptyList()) { result, item -> upsert(result, item) }

    fun choices(lines: List<MetroLine>, name: String): List<TransferStationResolver.LineStation> =
        TransferStationResolver.match(lines, name)

    fun valid(item: StationSubscription, lines: List<MetroLine>): Boolean {
        val matches = choices(lines, item.stationName)
        if (item.lineId == null) return matches.isNotEmpty() && item.directionId == null
        val selected = matches.firstOrNull { it.line.lineId == item.lineId } ?: return false
        return item.directionId == null || item.directionId in ArrivalEstimator(selected.line).boardingDirections(selected.stationId)
    }

    fun description(item: StationSubscription, lines: List<MetroLine>): String {
        if (!valid(item, lines)) return "站点、线路或方向数据已失效，请编辑订阅"
        if (item.lineId == null) return "全部线路 · 全部方向"
        val selected = choices(lines, item.stationName).first { it.line.lineId == item.lineId }
        val direction = item.directionId?.let { ArrivalEstimator(selected.line).boardingDirections(selected.stationId)[it] } ?: "全部方向"
        return "${selected.line.lineName} · $direction"
    }

    data class Preview(
        val lineName: String,
        val lineColorHex: String?,
        val direction: String,
        val arrival: ArrivalItem?,
        val window: StationServiceWindow?
    )

    fun preview(item: StationSubscription, lines: List<MetroLine>, overrides: Map<String, UserOverrides?>,
                serviceType: String, now: Int, currentEpochMillis: Long? = null,
                displayMode: ArrivalDisplayMode = ArrivalDisplayMode.DEFAULT): List<Preview> {
        if (!valid(item, lines)) return emptyList()
        return choices(lines, item.stationName).filter { item.lineId == null || it.line.lineId == item.lineId }.flatMap { selected ->
            val estimator = ArrivalEstimator(selected.line, overrides[selected.line.lineId])
            val arrivals = estimator.nextArrivalsByDirection(selected.stationId, serviceType, now, 1, currentEpochMillis, displayMode).associateBy { it.directionId }
            estimator.boardingDirections(selected.stationId).filterKeys { item.directionId == null || it == item.directionId }.map { (id, label) ->
                Preview(
                    lineName = selected.line.lineName,
                    lineColorHex = selected.line.color,
                    direction = label,
                    arrival = arrivals[id]?.arrivals?.firstOrNull(),
                    window = estimator.serviceWindow(selected.stationId, serviceType, id)
                )
            }
        }
    }

    fun arrivalText(
        preview: Preview,
        now: Int,
        mode: ArrivalDisplayMode = ArrivalDisplayMode.SYSTEM_ESTIMATE
    ): String {
        val arrival = preview.arrival
        if (arrival != null) {
            val presentation = arrival.present(mode, now)
            val wait = presentation.waitSeconds
            val prefix = if (presentation.usesUserCalibration) presentation.sourceLabel else "预计"
            val countdown = if (wait <= 0) "$prefix 已到站" else "$prefix ${wait / 60}分${wait % 60}秒"
            val terminal = if (arrival.isShortTurn) " · 区间车至${arrival.terminalStationName}" else ""
            return "$countdown$terminal"
        }
        val window = preview.window
        return if (window != null && window.isFinishedAt(now)) "今日已收车 · 首班预计 ${TimeUtils.formatSecondsOfDay(window.firstSecondsOfDay)}"
        else "当前方向暂无预计班次"
    }

    fun arrivalModeNote(preview: Preview, now: Int, mode: ArrivalDisplayMode): String? {
        val arrival = preview.arrival ?: return null
        return if (arrival.present(mode, now).fellBackToSystem) "暂无用户校准，已使用系统预计" else null
    }
}
