package com.metronearby.data.source

import android.content.Context
import com.metronearby.domain.ThemeMode
import com.metronearby.domain.ArrivalDisplayMode
import com.metronearby.data.MetroJson
import com.metronearby.data.model.StationSubscription
import com.metronearby.data.model.MetroLine

/**
 * 应用设置项的读写入口。
 *
 * 主题模式、当前线路、到站时间口径与站点订阅统一从此接口读写，后续新增设置项时在本接口上扩展即可，
 * 避免界面层直接碰具体的存储实现。
 */
interface SettingsStore {
    fun loadArrivalDisplayMode(): ArrivalDisplayMode
    fun saveArrivalDisplayMode(mode: ArrivalDisplayMode)
    fun loadSubscriptions(): List<StationSubscription>
    fun saveSubscriptions(items: List<StationSubscription>)
    fun loadCustomLines(): List<MetroLine>
    fun saveCustomLines(items: List<MetroLine>)
    fun loadNetworkMapCityId(): String?
    fun saveNetworkMapCityId(cityId: String)
    fun loadThemeMode(): ThemeMode

    fun saveThemeMode(mode: ThemeMode)

    /**
     * 当前线路的数据文件名（如 "line_beijing_1.json"）。
     *
     * 从未选择过时返回 null，由调用方决定默认线路——存储层不该知道哪条线是默认的。
     */
    fun loadLineDataFile(): String?

    fun saveLineDataFile(dataFile: String)
}

/**
 * 基于 SharedPreferences 的实现。
 *
 * 选 SharedPreferences 而不是 DataStore，是为了不给项目引入新依赖
 * （沿用「缺能力优先零依赖/原生实现」的口径）；订阅列表使用 JSON 字符串保存。
 */
class SharedPreferencesSettingsStore(context: Context) : SettingsStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun loadArrivalDisplayMode(): ArrivalDisplayMode =
        ArrivalDisplayMode.fromStorageKey(prefs.getString(KEY_ARRIVAL_DISPLAY_MODE, null))

    override fun saveArrivalDisplayMode(mode: ArrivalDisplayMode) {
        prefs.edit().putString(KEY_ARRIVAL_DISPLAY_MODE, mode.storageKey).apply()
    }

    override fun loadSubscriptions(): List<StationSubscription> =
        MetroJson.parseSubscriptions(prefs.getString("station_subscriptions", null) ?: "[]")

    override fun saveSubscriptions(items: List<StationSubscription>) {
        prefs.edit().putString("station_subscriptions", MetroJson.encodeSubscriptions(items)).apply()
    }

    override fun loadCustomLines(): List<MetroLine> =
        MetroJson.parseLines(prefs.getString(KEY_CUSTOM_LINES, null) ?: "[]")

    override fun saveCustomLines(items: List<MetroLine>) {
        prefs.edit().putString(KEY_CUSTOM_LINES, MetroJson.encodeLines(items)).apply()
    }

    override fun loadNetworkMapCityId(): String? =
        prefs.getString(KEY_NETWORK_MAP_CITY_ID, null)?.takeIf { it.isNotBlank() }

    override fun saveNetworkMapCityId(cityId: String) {
        prefs.edit().putString(KEY_NETWORK_MAP_CITY_ID, cityId).apply()
    }

    override fun loadThemeMode(): ThemeMode =
        ThemeMode.fromStorageKey(prefs.getString(KEY_THEME_MODE, null))

    override fun saveThemeMode(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.storageKey).apply()
    }

    override fun loadLineDataFile(): String? =
        prefs.getString(KEY_LINE_DATA_FILE, null)?.takeIf { it.isNotBlank() }

    override fun saveLineDataFile(dataFile: String) {
        prefs.edit().putString(KEY_LINE_DATA_FILE, dataFile).apply()
    }

    companion object {
        const val FILE_NAME = "metro_settings"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_LINE_DATA_FILE = "line_data_file"
        const val KEY_ARRIVAL_DISPLAY_MODE = "arrival_display_mode"
        const val KEY_CUSTOM_LINES = "custom_lines"
        const val KEY_NETWORK_MAP_CITY_ID = "network_map_city_id"
    }
}
