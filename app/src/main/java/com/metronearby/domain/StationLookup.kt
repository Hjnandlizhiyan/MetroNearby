package com.metronearby.domain

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** Shared by home, route endpoints and favorite destinations. Never auto-selects fuzzy hits. */
object StationLookup {
    data class Hit(val station: OfflineRoutePlanner.StationChoice, val suggestion: Boolean = false)

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    private fun terms(station: OfflineRoutePlanner.StationChoice): List<String> =
        (listOf(station.stationName) + station.aliases).flatMap { listOf(it) + StationPinyin.keys(it) }
            .map(::normalize).filter { it.isNotEmpty() }.distinct()

    fun search(choices: List<OfflineRoutePlanner.StationChoice>, query: String,
               showAllWhenBlank: Boolean = false): List<Hit> = Index(choices).search(query, showAllWhenBlank)

    class Index(private val choices: List<OfflineRoutePlanner.StationChoice>) {
        private val indexedTerms = choices.associateWith { terms(it) }
        fun search(query: String, showAllWhenBlank: Boolean = false): List<Hit> {
            val key = normalize(query)
            if (key.isEmpty()) return if (showAllWhenBlank && query.isBlank()) choices.map { Hit(it) } else emptyList()
            val scored = choices.mapNotNull { station ->
                val values = indexedTerms.getValue(station)
                val score = when {
                    normalize(station.stationName) == key -> 0
                    values.any { it == key } -> 1
                    values.any { it.startsWith(key) } -> 2
                    values.any { it.contains(key) } -> 3
                    station.lineNames.any { normalize(it).contains(key) } -> 4
                    else -> return@mapNotNull null
                }
                score to station
            }
            if (scored.isNotEmpty()) return scored.sortedBy { it.first }.map { Hit(it.second) }
            // Very short input is too ambiguous. Initials are not fuzzy matched.
            val chinese = key.any { it in '一'..'鿿' }
            if (key.length < (if (chinese) 3 else 4) || key.length > 80) return emptyList()
            return choices.filter { station ->
                (listOf(station.stationName) + station.aliases).any { name ->
                    val values = if (chinese) listOf(normalize(name))
                        else StationPinyin.keys(name).take(1).map(::normalize)
                    values.any { oneEditAway(key, it) }
                }
            }.map { Hit(it, suggestion = true) }
        }
    }

    /** Exactly one insertion, deletion or substitution; no automatic correction. */
    internal fun oneEditAway(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1 || a == b) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                b.length > a.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) == 1
    }
}
