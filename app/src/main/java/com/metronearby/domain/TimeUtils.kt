package com.metronearby.domain

/**
 * 时刻统一用"当天 00:00 起的秒数"表示。
 *
 * 不处理跨零点：凌晨 00:15 时最近的车应该是当天 05:xx 的首班车，
 * 而不是前一天已开走的末班车，因此取"当天秒数"即可满足需求。
 */
object TimeUtils {

    const val SECONDS_PER_DAY = 24 * 3600

    /**
     * 解析 "HH:mm" 为当天秒数。允许小时超过 24，便于将来表达跨零点班次。
     */
    fun parseToSecondsOfDay(text: String): Int {
        val trimmed = text.trim()
        val parts = trimmed.split(":")
        require(parts.size >= 2) { "时间格式应为 HH:mm，实际为: $trimmed" }
        val hour = parts[0].toIntOrNull()
        val minute = parts[1].toIntOrNull()
        require(hour != null && minute != null) { "时间包含非数字内容: $trimmed" }
        require(hour in 0..47) { "小时超出支持范围(0..47): $trimmed" }
        require(minute in 0..59) { "分钟超出范围(0..59): $trimmed" }
        return hour * 3600 + minute * 60
    }

    /**
     * 校验用户输入的当天时刻 "HH:mm"，合法时返回秒数，否则返回 null。
     *
     * 与 [parseToSecondsOfDay] 的区别是小时只允许 0..23：用户记录的是当天在站台
     * 实际看到的到站时刻，不存在 24:xx 这类跨零点表达。
     */
    fun parseClockTime(text: String): Int? {
        val trimmed = text.trim()
        val parts = trimmed.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 3600 + minute * 60
    }

    /**
     * 把当天秒数格式化为 "HH:mm"，超过一天的部分自动取模。
     */
    fun formatSecondsOfDay(seconds: Int): String {
        val normalized = ((seconds % SECONDS_PER_DAY) + SECONDS_PER_DAY) % SECONDS_PER_DAY
        val hour = normalized / 3600
        val minute = (normalized % 3600) / 60
        return "%02d:%02d".format(hour, minute)
    }
}