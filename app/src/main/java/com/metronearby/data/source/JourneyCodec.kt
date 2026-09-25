package com.metronearby.data.source

import com.metronearby.data.MetroJson
import com.metronearby.domain.JourneyPolicy
import com.metronearby.domain.SavedJourney
import kotlinx.serialization.builtins.ListSerializer

object JourneyCodec {
    fun decode(text: String): List<SavedJourney> = runCatching {
        MetroJson.instance.decodeFromString(ListSerializer(SavedJourney.serializer()), text)
            .filter(JourneyPolicy::valid).distinctBy { it.id }
    }.getOrDefault(emptyList())

    fun encode(items: List<SavedJourney>): String =
        MetroJson.instance.encodeToString(ListSerializer(SavedJourney.serializer()),
            items.filter(JourneyPolicy::valid).distinctBy { it.id })
}
