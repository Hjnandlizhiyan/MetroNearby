package com.metronearby.domain

/** 用户观测按运营日类型和通勤时段分组；存储键保持稳定，显示文案可以独立调整。 */
enum class ObservationTimeBand(val storageKey: String, val displayName: String) {
    MORNING_PEAK("morning_peak", "早高峰"),
    OFF_PEAK("off_peak", "平峰"),
    EVENING_PEAK("evening_peak", "晚高峰");

    companion object {
        fun fromSeconds(secondsOfDay: Int): ObservationTimeBand {
            val normalized = ((secondsOfDay % TimeUtils.SECONDS_PER_DAY) + TimeUtils.SECONDS_PER_DAY) % TimeUtils.SECONDS_PER_DAY
            return when (normalized) {
                in 7 * 3600 until 10 * 3600 -> MORNING_PEAK
                in 17 * 3600 until 20 * 3600 -> EVENING_PEAK
                else -> OFF_PEAK
            }
        }

        fun fromStorageKey(key: String?): ObservationTimeBand? = entries.firstOrNull { it.storageKey == key }
    }
}

enum class UserCalibrationScope {
    LEARNED_HEADWAY,
    LEARNED_HISTORY,
    BLENDED_ANCHOR,
    RECENT_ANCHOR,
    TIME_BAND,
    SERVICE_TYPE,
    ALL_DAY
}

fun serviceTypeDisplayName(serviceType: String?): String? = when (serviceType) {
    "weekday" -> "工作日"
    "weekend" -> "周末"
    else -> null
}

fun UserObservationAverage.displayLabel(): String = when (scope) {
    UserCalibrationScope.LEARNED_HEADWAY -> "用户间隔学习预计"
    UserCalibrationScope.LEARNED_HISTORY -> "用户历史学习预计"
    UserCalibrationScope.BLENDED_ANCHOR -> "用户历史＋锚点预计"
    UserCalibrationScope.RECENT_ANCHOR -> "用户近期锚点"
    UserCalibrationScope.TIME_BAND -> "用户${timeBand?.displayName ?: "分时段"}校准"
    UserCalibrationScope.SERVICE_TYPE -> "用户${serviceTypeDisplayName(serviceType) ?: "当日"}平均"
    UserCalibrationScope.ALL_DAY -> "用户全天平均"
}
