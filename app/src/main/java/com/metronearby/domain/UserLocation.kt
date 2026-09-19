package com.metronearby.domain

import java.util.Locale
import kotlin.math.roundToInt

/** Android 定位结果在业务层的只读快照。 */
data class UserLocation(
    val lat: Double,
    val lng: Double,
    val accuracyMeters: Float? = null,
    val capturedAtMillis: Long,
    val provider: String? = null,
    val isMock: Boolean = false
)

/** 过滤过期或明显不可用的位置，避免把旧缓存误当成用户当前位置。 */
object UserLocationPolicy {
    const val MAX_CACHE_AGE_MILLIS = 2 * 60 * 1000L
    const val MAX_ACCURACY_METERS = 5_000f

    fun isUsable(location: UserLocation, nowMillis: Long): Boolean =
        location.lat in -90.0..90.0 &&
            location.lng in -180.0..180.0 &&
            location.capturedAtMillis in (nowMillis - MAX_CACHE_AGE_MILLIS)..(nowMillis + 10_000L) &&
            (location.accuracyMeters == null || location.accuracyMeters in 0f..MAX_ACCURACY_METERS)

    fun best(candidates: List<UserLocation>, nowMillis: Long): UserLocation? =
        candidates
            .filter { isUsable(it, nowMillis) }
            .minWithOrNull(
                compareBy<UserLocation> { it.accuracyMeters ?: Float.MAX_VALUE }
                    .thenByDescending { it.capturedAtMillis }
            )

    fun coordinatesText(location: UserLocation): String =
        String.format(Locale.US, "%.5f, %.5f", location.lat, location.lng)

    fun detailText(location: UserLocation, nowMillis: Long): String {
        val parts = buildList {
            location.accuracyMeters?.let { add("精度约 ${it.roundToInt()} 米") }
            val ageSeconds = ((nowMillis - location.capturedAtMillis).coerceAtLeast(0L) / 1_000L)
            add(if (ageSeconds < 60) "刚刚更新" else "${ageSeconds / 60} 分钟前更新")
            if (location.isMock) add("模拟位置")
        }
        return parts.joinToString(" · ")
    }
}
