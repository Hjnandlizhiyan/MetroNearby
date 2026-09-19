package com.metronearby.data

import com.metronearby.data.model.CityIndex
import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.UserOverrides
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import com.metronearby.data.model.StationSubscription
import com.metronearby.domain.StationSubscriptions

/**
 * 统一的 JSON 入口。集中配置容错策略，避免各处 Json 实例不一致。
 */
object MetroJson {
    fun parseLines(text: String): List<MetroLine> = runCatching {
        instance.decodeFromString(ListSerializer(MetroLine.serializer()), text)
    }.getOrDefault(emptyList())

    fun encodeLines(items: List<MetroLine>): String =
        instance.encodeToString(ListSerializer(MetroLine.serializer()), items)

    fun parseSubscriptions(text: String): List<StationSubscription> = runCatching {
        StationSubscriptions.normalize(instance.decodeFromString(ListSerializer(StationSubscription.serializer()), text))
    }.getOrDefault(emptyList())

    fun encodeSubscriptions(items: List<StationSubscription>): String =
        instance.encodeToString(ListSerializer(StationSubscription.serializer()), StationSubscriptions.normalize(items))

    val instance: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun parseCityIndex(text: String): CityIndex =
        instance.decodeFromString(CityIndex.serializer(), text)

    fun parseLine(text: String): MetroLine =
        instance.decodeFromString(MetroLine.serializer(), text)

    fun parseOverrides(text: String): UserOverrides =
        instance.decodeFromString(UserOverrides.serializer(), text)

    fun encodeOverrides(overrides: UserOverrides): String =
        instance.encodeToString(UserOverrides.serializer(), overrides)
}
