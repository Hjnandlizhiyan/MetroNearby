package com.metronearby.data.source

import android.content.Context
import com.metronearby.data.FootprintCodec
import com.metronearby.domain.StationFootprint

class FootprintStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("metro_footprints", Context.MODE_PRIVATE)

    fun load(): List<StationFootprint> =
        FootprintCodec.decode(prefs.getString("visited_stations", null) ?: "[]")

    fun save(items: List<StationFootprint>) {
        check(prefs.edit().putString("visited_stations", FootprintCodec.encode(items)).commit()) {
            "足迹保存失败"
        }
    }
}
