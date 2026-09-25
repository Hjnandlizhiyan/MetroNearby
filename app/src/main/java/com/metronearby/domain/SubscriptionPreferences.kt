package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.StationSubscription

/** 收藏附加信息与路线端点，不参与旧预计功能的有效性判定。 */
object SubscriptionPreferences {
    val tags = listOf("家", "公司", "学校", "常去")
    const val MAX_EXIT = 80
    const val MAX_NOTE = 500

    fun originKey(item: StationSubscription, lines: List<MetroLine>): String? {
        val matches = StationSubscriptions.choices(lines, item.stationName)
            .filter { item.lineId == null || it.line.lineId == item.lineId }
        return matches.map { OfflineRoutePlanner.stationKey(it.line, item.stationName) }.distinct().singleOrNull()
    }

    fun destinations(item: StationSubscription, lines: List<MetroLine>): List<OfflineRoutePlanner.StationChoice> {
        val origin = originKey(item, lines) ?: return emptyList()
        return OfflineRoutePlanner.stationChoices(lines).filter {
            it.key != origin && it.key.substringBefore("|") == origin.substringBefore("|")
        }
    }

    fun error(item: StationSubscription, lines: List<MetroLine>): String? = when {
        item.tag.isNotEmpty() && item.tag !in tags -> "请重新选择标签"
        item.preferredExit.length > MAX_EXIT -> "常走出口最多 ${MAX_EXIT} 字"
        item.note.length > MAX_NOTE -> "备注最多 ${MAX_NOTE} 字"
        item.destinationKey != null && destinations(item, lines).none { it.key == item.destinationKey } ->
            "常用目的地不可用，请重新选择或清除"
        else -> null
    }
}
