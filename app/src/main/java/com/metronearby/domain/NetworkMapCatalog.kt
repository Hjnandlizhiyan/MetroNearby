package com.metronearby.domain

/** 可用线网图目录。资源文件在 UI 层按 id 映射，城市元数据保持纯 JVM 可测。 */
data class NetworkMapInfo(
    val id: String,
    val provinceName: String,
    val cityName: String,
    val title: String,
    val sourceLabel: String,
    val updatedAt: String
)

object NetworkMapCatalog {
    const val DEFAULT_CITY_ID = "beijing"

    val available: List<NetworkMapInfo> = listOf(
        NetworkMapInfo(
            id = DEFAULT_CITY_ID,
            provinceName = "北京市",
            cityName = "北京",
            title = "北京地铁",
            sourceLabel = "北京京港地铁官网",
            updatedAt = "2026-05-16"
        )
    )

    fun find(cityId: String?): NetworkMapInfo =
        available.firstOrNull { it.id == cityId } ?: available.first()
}
