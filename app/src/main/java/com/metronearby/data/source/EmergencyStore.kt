package com.metronearby.data.source

import android.content.Context
import com.metronearby.data.*
import com.metronearby.domain.*

/** Dedicated preferences excluded from cloud backup and device transfer. */
class EmergencyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("emergency_card", Context.MODE_PRIVATE)
    fun personal(): EmergencyPersonal = prefs.getString("personal", null)?.let(EmergencyCodec::personal) ?: EmergencyPersonal()
    fun location(): UserLocation? = prefs.getString("location", null)?.let(EmergencyCodec::location)
    fun route(): EmergencyRoute? = prefs.getString("route", null)?.let(EmergencyCodec::route)
    fun savePersonal(value: EmergencyPersonal) {
        val clean = EmergencyCardPolicy.clean(value)
        check(prefs.edit().putString("personal", MetroJson.instance.encodeToString(EmergencyPersonal.serializer(), clean)).commit())
    }
    fun saveLocation(value: UserLocation) {
        require(EmergencyCardPolicy.validLocation(value))
        check(prefs.edit().putString("location", MetroJson.instance.encodeToString(UserLocation.serializer(), value)).commit())
    }
    fun saveRoute(value: EmergencyRoute) {
        check(prefs.edit().putString("route", MetroJson.instance.encodeToString(EmergencyRoute.serializer(), value)).commit())
    }
    fun clearPersonal() { check(prefs.edit().remove("personal").commit()) }
    fun clearSnapshots() { check(prefs.edit().remove("location").remove("route").commit()) }
}
