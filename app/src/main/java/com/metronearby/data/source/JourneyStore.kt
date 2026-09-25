package com.metronearby.data.source

import android.content.Context
import com.metronearby.data.MetroJson
import com.metronearby.domain.JourneyPolicy
import com.metronearby.domain.SavedJourney
import kotlinx.serialization.builtins.ListSerializer

/** 独立键保存行程，不迁移或清除旧时刻数据。 */
class JourneyStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("metro_settings", Context.MODE_PRIVATE)
    fun load(): List<SavedJourney> = JourneyCodec.decode(prefs.getString("saved_journeys", null) ?: "[]")
    fun save(items: List<SavedJourney>) {
        prefs.edit().putString("saved_journeys", JourneyCodec.encode(items)).apply()
    }
}
