package com.metronearby.data.model

import kotlinx.serialization.Serializable

/** null 线路表示全部线路，null 方向表示该线路全部可乘方向。 */
@Serializable
data class StationSubscription(
    val stationName: String,
    val lineId: String? = null,
    val directionId: String? = null,
    val tag: String = "",
    val destinationKey: String? = null,
    val preferredExit: String = "",
    val note: String = ""
)
