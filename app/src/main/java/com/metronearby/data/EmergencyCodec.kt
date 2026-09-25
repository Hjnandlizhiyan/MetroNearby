package com.metronearby.data

import com.metronearby.domain.*

object EmergencyCodec {
    fun personal(text: String): EmergencyPersonal =
        MetroJson.instance.decodeFromString<EmergencyPersonal>(text).also { require(EmergencyCardPolicy.error(it) == null) }
    fun location(text: String): UserLocation =
        MetroJson.instance.decodeFromString<UserLocation>(text).also { require(EmergencyCardPolicy.validLocation(it)) }
    fun route(text: String): EmergencyRoute =
        MetroJson.instance.decodeFromString<EmergencyRoute>(text).also {
            require(it.origin.isNotBlank() && it.destination.isNotBlank() && it.instructions.isNotEmpty() && it.capturedAtMillis > 0)
        }
}
