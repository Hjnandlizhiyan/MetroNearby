package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import java.util.PriorityQueue

/** 仅基于离线站序和相邻区间规划路线，不使用班次或实时到站数据。 */
object OfflineRoutePlanner {
    enum class Preference(val displayName: String) {
        FEWER_TRANSFERS("少换乘"),
        FEWER_STOPS("少经过站")
    }

    data class StationChoice(
        val key: String,
        val cityName: String,
        val stationName: String,
        val lineNames: List<String>
    )

    data class RouteLeg(
        val lineId: String,
        val lineName: String,
        val lineColor: String?,
        val fromStation: String,
        val toStation: String,
        val stationNames: List<String>
    ) {
        val stopCount: Int get() = (stationNames.size - 1).coerceAtLeast(0)
    }

    data class RouteResult(
        val originName: String,
        val destinationName: String,
        val legs: List<RouteLeg>
    ) {
        val stopCount: Int get() = legs.sumOf { it.stopCount }
        val transferCount: Int get() = (legs.size - 1).coerceAtLeast(0)
    }

    sealed interface PlanResult {
        data class Found(val route: RouteResult) : PlanResult
        data class Rejected(val reason: String) : PlanResult
    }

    private data class Edge(
        val from: String,
        val to: String,
        val fromName: String,
        val toName: String,
        val lineId: String,
        val lineName: String,
        val lineColor: String?
    )

    private data class State(val stationKey: String, val lineId: String?)
    private data class Cost(val transfers: Int, val stops: Int) {
        fun score(preference: Preference): Long = when (preference) {
            Preference.FEWER_TRANSFERS -> transfers.toLong() * 1_000_000L + stops
            Preference.FEWER_STOPS -> stops.toLong() * 1_000_000L + transfers
        }
    }
    private data class QueueItem(val state: State, val cost: Cost, val score: Long)
    private data class Previous(val state: State, val edge: Edge)

    fun stationChoices(lines: List<MetroLine>): List<StationChoice> {
        val lineNamesByStation = linkedMapOf<String, MutableSet<String>>()
        val names = linkedMapOf<String, Pair<String, String>>()
        lines.forEach { line ->
            line.stations.forEach { station ->
                val key = stationKey(line, station.name)
                names.putIfAbsent(key, cityName(line) to station.name)
                lineNamesByStation.getOrPut(key) { linkedSetOf() }.add(line.lineName)
            }
        }
        return names.map { (key, cityAndStation) ->
            StationChoice(
                key = key,
                cityName = cityAndStation.first,
                stationName = cityAndStation.second,
                lineNames = lineNamesByStation[key].orEmpty().toList()
            )
        }.sortedWith(compareBy<StationChoice> { it.cityName }.thenBy { it.stationName })
    }

    fun findChoice(
        choices: List<StationChoice>,
        stationName: String,
        cityName: String? = null
    ): StationChoice? {
        val normalized = TransferStationResolver.normalize(stationName)
        return choices.firstOrNull {
            TransferStationResolver.normalize(it.stationName) == normalized &&
                (cityName == null || it.cityName == cityName)
        }
    }

    fun plan(
        lines: List<MetroLine>,
        originKey: String,
        destinationKey: String,
        preference: Preference
    ): PlanResult {
        if (originKey == destinationKey) return PlanResult.Rejected("起点和终点不能相同")
        val choices = stationChoices(lines)
        val choiceByKey = choices.associateBy { it.key }
        val origin = choiceByKey[originKey] ?: return PlanResult.Rejected("没有找到起点站")
        val destination = choiceByKey[destinationKey] ?: return PlanResult.Rejected("没有找到终点站")
        if (origin.cityName != destination.cityName) {
            return PlanResult.Rejected("离线路线规划暂不支持跨城市")
        }

        val graph = buildGraph(lines)
        val start = State(originKey, null)
        val queue = PriorityQueue(compareBy<QueueItem> { it.score })
        val best = mutableMapOf(start to Cost(0, 0))
        val previous = mutableMapOf<State, Previous>()
        queue += QueueItem(start, Cost(0, 0), 0)
        var finish: State? = null

        while (queue.isNotEmpty()) {
            val item = queue.remove()
            if (best[item.state] != item.cost) continue
            if (item.state.stationKey == destinationKey) {
                finish = item.state
                break
            }
            graph[item.state.stationKey].orEmpty().forEach { edge ->
                val transfer = if (item.state.lineId != null && item.state.lineId != edge.lineId) 1 else 0
                val nextCost = Cost(item.cost.transfers + transfer, item.cost.stops + 1)
                val next = State(edge.to, edge.lineId)
                val existing = best[next]
                if (existing == null || nextCost.score(preference) < existing.score(preference)) {
                    best[next] = nextCost
                    previous[next] = Previous(item.state, edge)
                    queue += QueueItem(next, nextCost, nextCost.score(preference))
                }
            }
        }

        val finalState = finish ?: return PlanResult.Rejected("当前离线线网中没有找到可达路线")
        val traversed = mutableListOf<Edge>()
        var cursor = finalState
        while (cursor != start) {
            val step = previous[cursor] ?: return PlanResult.Rejected("路线数据不完整")
            traversed += step.edge
            cursor = step.state
        }
        traversed.reverse()
        val legs = groupLegs(traversed)
        return PlanResult.Found(RouteResult(origin.stationName, destination.stationName, legs))
    }

    private fun buildGraph(lines: List<MetroLine>): Map<String, List<Edge>> {
        val graph = linkedMapOf<String, MutableList<Edge>>()
        lines.forEach { line ->
            val stations = line.stations.associateBy { it.id }
            line.segments.forEach { segment ->
                val from = stations[segment.from] ?: return@forEach
                val to = stations[segment.to] ?: return@forEach
                val fromKey = stationKey(line, from.name)
                val toKey = stationKey(line, to.name)
                val forward = Edge(fromKey, toKey, from.name, to.name, line.lineId, line.lineName, line.color)
                val reverse = Edge(toKey, fromKey, to.name, from.name, line.lineId, line.lineName, line.color)
                if (graph.getOrPut(fromKey) { mutableListOf() }.none { it.to == toKey && it.lineId == line.lineId }) {
                    graph.getValue(fromKey) += forward
                }
                if (graph.getOrPut(toKey) { mutableListOf() }.none { it.to == fromKey && it.lineId == line.lineId }) {
                    graph.getValue(toKey) += reverse
                }
            }
        }
        return graph
    }

    private fun groupLegs(edges: List<Edge>): List<RouteLeg> {
        if (edges.isEmpty()) return emptyList()
        val result = mutableListOf<RouteLeg>()
        var lineId = edges.first().lineId
        var lineName = edges.first().lineName
        var lineColor = edges.first().lineColor
        var names = mutableListOf(edges.first().fromName, edges.first().toName)
        edges.drop(1).forEach { edge ->
            if (edge.lineId == lineId) {
                names += edge.toName
            } else {
                result += RouteLeg(lineId, lineName, lineColor, names.first(), names.last(), names.toList())
                lineId = edge.lineId
                lineName = edge.lineName
                lineColor = edge.lineColor
                names = mutableListOf(edge.fromName, edge.toName)
            }
        }
        result += RouteLeg(lineId, lineName, lineColor, names.first(), names.last(), names.toList())
        return result
    }

    fun stationKey(line: MetroLine, stationName: String): String =
        cityKey(line) + "|" + TransferStationResolver.normalize(stationName)

    private fun cityKey(line: MetroLine): String =
        line.cityId?.takeIf { it.isNotBlank() }
            ?: line.cityName?.takeIf { it.isNotBlank() }
            ?: "unknown"

    private fun cityName(line: MetroLine): String =
        line.cityName?.takeIf { it.isNotBlank() } ?: line.cityId?.takeIf { it.isNotBlank() } ?: "未设置城市"
}