package com.metronearby.domain

import com.metronearby.data.model.MetroLine
import com.metronearby.data.model.Pattern
import com.metronearby.data.model.ShortTurn
import com.metronearby.data.model.Station
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortTurnBatchEditorTest {
    private val line = MetroLine(
        schemaVersion = 1,
        lineId = "test",
        lineName = "测试线",
        coordSystem = "WGS84",
        stationOrder = listOf("s1", "s2", "s3"),
        stations = listOf(
            Station("s1", "一站", 0.0, 0.0),
            Station("s2", "二站", 0.0, 0.0),
            Station("s3", "三站", 0.0, 0.0)
        ),
        patterns = listOf(Pattern("forward", "全程", "s1", "s3"))
    )
    private val turns = listOf(
        ShortTurn("s1", "s2", listOf("08:00", "08:10")),
        ShortTurn("s2", "s3", listOf("09:00"))
    )

    @Test fun `整体调整只改变选中的区间车`() {
        val result = ShortTurnBatchEditor.shift(line, turns, setOf(0), 5)
            as ShortTurnBatchEditor.Result.Updated
        assertEquals(listOf("08:05", "08:15"), result.shortTurns[0].departures)
        assertEquals(listOf("09:00"), result.shortTurns[1].departures)
    }

    @Test fun `整体调整拒绝跨越零点`() {
        val result = ShortTurnBatchEditor.shift(
            line,
            listOf(ShortTurn("s1", "s2", listOf("23:58"))),
            setOf(0),
            5
        )
        assertTrue(result is ShortTurnBatchEditor.Result.Rejected)
    }

    @Test fun `统一替换时刻会排序并去重`() {
        val result = ShortTurnBatchEditor.replaceDepartures(
            line, turns, setOf(0, 1), listOf("08:10", "08:00", "08:10")
        ) as ShortTurnBatchEditor.Result.Updated
        assertEquals(listOf("08:00", "08:10"), result.shortTurns[0].departures)
        assertEquals(listOf("08:00", "08:10"), result.shortTurns[1].departures)
    }

    @Test fun `批量删除保留未选中的区间车`() {
        assertEquals(listOf(turns[1]), ShortTurnBatchEditor.remove(turns, setOf(0)))
    }
}