package com.metronearby.domain

import com.metronearby.data.model.Station

/**
 * 站名检索。
 *
 * 空关键字返回空列表——它表示"用户没有在搜索"，此时 UI 应回落到定位推荐的最近站，
 * 而不是列出全部车站，避免首屏被整条线路的站名刷屏。
 */
object StationSearch {

    fun match(stations: List<Station>, keyword: String): List<Station> {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return emptyList()

        return stations.filter { station -> station.matches(trimmed) }
    }

    /**
     * 同 [match]，但按「站名归一化后的键」去重，用于跨线路检索。
     *
     * 换乘站在每条线路里各有一条站点记录（1 号线复兴门、2 号线复兴门），
     * 多线路一起搜时同一座车站会出现多次。这里只保留先出现的那条，
     * 让候选列表里「复兴门」只有一行。（匹配口径与去重口径保持一致，
     * 见 [TransferStationResolver.normalize]。）
     */
    fun matchDistinctByName(stations: List<Station>, keyword: String): List<Station> =
        match(stations, keyword).distinctBy { TransferStationResolver.normalize(it.name) }

    private fun Station.matches(keyword: String): Boolean =
        name.contains(keyword, ignoreCase = true) ||
            aliases.any { it.contains(keyword, ignoreCase = true) }
}