package com.metronearby.data.source

import android.content.Context
import com.metronearby.data.StationFacilityCodec
import com.metronearby.data.model.StationPersonalNotes
import com.metronearby.domain.StationFacilityPolicy

class StationFacilityStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("metro_settings", Context.MODE_PRIVATE)

    fun loadCatalog() = appContext.assets.open("metro/station_facilities_beijing.json")
        .bufferedReader(Charsets.UTF_8).use { StationFacilityCodec.decodeCatalog(it.readText()) }

    fun loadNotes(): List<StationPersonalNotes> =
        StationFacilityCodec.decodeNotes(prefs.getString("station_personal_notes", null) ?: "[]")

    fun save(draft: StationPersonalNotes): List<StationPersonalNotes> {
        val updated = StationFacilityPolicy.upsert(loadNotes(), draft, System.currentTimeMillis())
        check(prefs.edit().putString("station_personal_notes", StationFacilityCodec.encodeNotes(updated)).commit()) {
            "备注保存失败"
        }
        return updated
    }

    fun remove(key: String): List<StationPersonalNotes> {
        val updated = StationFacilityPolicy.remove(loadNotes(), key)
        check(prefs.edit().putString("station_personal_notes", StationFacilityCodec.encodeNotes(updated)).commit()) {
            "备注清除失败"
        }
        return updated
    }
}
