package com.metronearby.data

import com.metronearby.domain.StationFootprint
import com.metronearby.domain.StationFootprints
import kotlinx.serialization.builtins.ListSerializer

object FootprintCodec {
    fun decode(text: String): List<StationFootprint> =
        MetroJson.instance.decodeFromString(ListSerializer(StationFootprint.serializer()), text).also { items ->
            require(items.all(StationFootprints::valid))
            require(items.distinctBy { it.stationKey }.size == items.size)
        }

    fun encode(items: List<StationFootprint>): String =
        MetroJson.instance.encodeToString(ListSerializer(StationFootprint.serializer()), items)
}
