package com.metronearby.domain

/** 用户选择的主倒计时口径。 */
enum class ArrivalDisplayMode(val storageKey: String, val displayName: String) {
    SYSTEM_ESTIMATE("system_estimate", "系统预计"),
    USER_CALIBRATED("user_calibrated", "用户校准");

    companion object {
        val DEFAULT = SYSTEM_ESTIMATE

        fun fromStorageKey(key: String?): ArrivalDisplayMode {
            val normalized = key?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.storageKey == normalized } ?: DEFAULT
        }
    }
}

data class ArrivalPresentation(
    val arrivalSecondsOfDay: Int,
    val waitSeconds: Int,
    val sourceLabel: String,
    val usesUserCalibration: Boolean,
    val fellBackToSystem: Boolean
)

/** 把同一班车转换成用户当前选中的展示口径；基础推算对象保持不变。 */
fun ArrivalItem.present(mode: ArrivalDisplayMode, nowSecondsOfDay: Int): ArrivalPresentation {
    val calibration = userAverage
    val calibratedSeconds = userArrivalSecondsOfDay
    val useUser = mode == ArrivalDisplayMode.USER_CALIBRATED && calibration != null && calibratedSeconds != null
    val seconds = if (useUser) calibratedSeconds!! else arrivalSecondsOfDay
    return ArrivalPresentation(
        arrivalSecondsOfDay = seconds,
        waitSeconds = seconds - nowSecondsOfDay,
        sourceLabel = if (useUser) calibration!!.displayLabel() else "系统预计",
        usesUserCalibration = useUser,
        fellBackToSystem = mode == ArrivalDisplayMode.USER_CALIBRATED && !useUser
    )
}
