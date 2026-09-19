package com.metronearby.domain

import com.metronearby.data.model.ServiceTypes
import java.util.Calendar

/**
 * 判断当前该用工作日还是周末时刻表。
 *
 * 第一版只按星期几区分，不含法定节假日调休；节假日表可作为后续
 * 独立的 serviceType（如 "holiday"）加入，数据结构已支持。
 */
object ServiceTypeResolver {

    fun from(calendar: Calendar): String {
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        return if (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) {
            ServiceTypes.WEEKEND
        } else {
            ServiceTypes.WEEKDAY
        }
    }

    fun fromNow(): String = from(Calendar.getInstance())

    /** 当前时间对应的"当天秒数"，与时刻表的时间基准一致。 */
    fun secondsOfDay(calendar: Calendar): Int =
        calendar.get(Calendar.HOUR_OF_DAY) * 3600 +
            calendar.get(Calendar.MINUTE) * 60 +
            calendar.get(Calendar.SECOND)

    fun currentSecondsOfDay(): Int = secondsOfDay(Calendar.getInstance())
}