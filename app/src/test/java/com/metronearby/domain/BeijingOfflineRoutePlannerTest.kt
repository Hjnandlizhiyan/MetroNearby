package com.metronearby.domain

import com.metronearby.data.MetroJson
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BeijingOfflineRoutePlannerTest {
    private val city = MetroJson.parseCityIndex(
        File("src/main/assets/metro/city_beijing.json").readText()
    )
    private val lines = city.lines.mapNotNull { reference ->
        reference.dataFile?.let { fileName ->
            MetroJson.parseLine(File("src/main/assets/metro/$fileName").readText())
        }
    }

    @Test
    fun plansAcrossRealBeijingNetwork() {
        val choices = OfflineRoutePlanner.stationChoices(lines)
        val origin = OfflineRoutePlanner.findChoice(choices, "古城", "北京")!!
        val destination = OfflineRoutePlanner.findChoice(choices, "北工大西门", "北京")!!

        val result = OfflineRoutePlanner.plan(
            lines,
            origin.key,
            destination.key,
            OfflineRoutePlanner.Preference.FEWER_TRANSFERS
        ) as OfflineRoutePlanner.PlanResult.Found

        assertEquals("古城", result.route.originName)
        assertEquals("北工大西门", result.route.destinationName)
        assertTrue(result.route.stopCount > 0)
        assertTrue(result.route.legs.isNotEmpty())
    }
}