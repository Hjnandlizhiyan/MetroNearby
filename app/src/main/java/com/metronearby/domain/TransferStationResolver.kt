package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Station

/**
 * 换乘站匹配。
 *
 * 换乘站在各条线路里是**各自独立**的站点记录（1 号线复兴门 `bj1_12`、2 号线复兴门 `bj2_16`），
 * 站点 id 只在单条线路内唯一，跨线路无法直接对应。因此以「站名」作为跨线路的同一性依据：
 * 站名（或别名）归一化后相等，就认为是同一座车站。
 *
 * 归一化只做两件事——去掉首尾空白、去掉末尾的「站」字。后者用于消化
 * 「复兴门」与「复兴门站」这类写法差异，避免同一座车站因后缀不同而漏配。
 * 代价是理论上「北京站」与假想的「北京」站会被视为同一站；实际线路数据里不存在
 * 这种站名冲突，若将来出现，应改为由数据显式给出换乘站分组 id。
 */
object TransferStationResolver {

    /** 「某条线路里的某个站」——换乘站匹配的结果单元。 */
    data class LineStation(
        val line: MetroLine,
        val stationId: String
    )

    /**
     * 找出 [stationName] 在 [lines] 里对应的所有站点。
     *
     * 返回顺序与 [lines] 一致，调用方据此控制展示优先级（例如「我的线路」置顶）；
     * 某条线路里没有同名站时该线路不出现在结果里。
     */
    fun match(lines: List<MetroLine>, stationName: String): List<LineStation> {
        val key = normalize(stationName)
        if (key.isEmpty()) return emptyList()

        return lines.mapNotNull { line ->
            val station = line.stations.firstOrNull { it.matches(key) } ?: return@mapNotNull null
            LineStation(line = line, stationId = station.id)
        }
    }

    /** 把站名归一化成可比较的键：去首尾空白 + 去末尾「站」字。 */
    fun normalize(name: String): String = name.trim().removeSuffix("站").trim()

    private fun Station.matches(key: String): Boolean =
        normalize(name) == key || aliases.any { normalize(it) == key }
}