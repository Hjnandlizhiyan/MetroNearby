package com.metronearby.domain

import kotlinx.serialization.Serializable

@Serializable
data class EmergencyPersonal(val contactName: String = "", val phone: String = "", val note: String = "")

@Serializable
data class EmergencyRoute(
    val origin: String, val destination: String,
    val instructions: List<String>, val colors: List<String?>,
    val capturedAtMillis: Long
)

object EmergencyCardPolicy {
    fun dialNumber(raw: String): String? {
        val number = raw.trim().filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
        return number.takeIf { it.matches(Regex("""\+?[0-9]{3,20}""")) }
    }
    fun error(personal: EmergencyPersonal): String? = when {
        personal.contactName.length > 40 -> "联系人名称最多40字"
        personal.phone.isNotBlank() && dialNumber(personal.phone) == null -> "号码请使用3至20位数字，可带空格、短横线和开头的+"
        personal.note.length > 500 -> "备注最多500字"
        else -> null
    }
    fun clean(personal: EmergencyPersonal): EmergencyPersonal {
        require(error(personal) == null)
        return personal.copy(contactName = personal.contactName.trim(),
            phone = personal.phone.trim(), note = personal.note.trim())
    }
    fun validLocation(location: UserLocation): Boolean =
        location.lat.isFinite() && location.lat in -90.0..90.0 &&
            location.lng.isFinite() && location.lng in -180.0..180.0 &&
            location.capturedAtMillis > 0 &&
            (location.accuracyMeters == null || (location.accuracyMeters.isFinite() && location.accuracyMeters >= 0))
    fun outdated(location: UserLocation, now: Long): Boolean =
        !UserLocationPolicy.isUsable(location, now)

    fun snapshot(route: OfflineRoutePlanner.RouteResult, now: Long) = EmergencyRoute(
        route.originName, route.destinationName, TravelGuide.cardLines(route),
        route.legs.map { it.lineColor }, now
    )
}
