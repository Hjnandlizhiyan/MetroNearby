package com.metronearby.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Coordinates 是 AndroidLocationProvider 中与平台无关的纯数据类，
 * 单独覆盖它，避免定位业务逻辑因依赖 Android 框架而完全无法度量。
 */
class CoordinatesTest {

    @Test
    fun `相同经纬度视为同一坐标`() {
        val xidan = Coordinates(39.9057386, 116.3682035)
        val sameSpot = Coordinates(39.9057386, 116.3682035)
        val tiananmenWest = Coordinates(39.9059702, 116.3851622)

        assertEquals("相同经纬度应相等", xidan, sameSpot)
        assertEquals("相等对象哈希应一致", xidan.hashCode(), sameSpot.hashCode())
        assertNotEquals("不同经纬度不应相等", xidan, tiananmenWest)
    }

    @Test
    fun `坐标可以复制并只替换一个维度`() {
        val origin = Coordinates(39.9057386, 116.3682035)
        val moved = origin.copy(lng = 116.4)

        assertEquals(39.9057386, moved.lat, 1e-9)
        assertEquals(116.4, moved.lng, 1e-9)
        assertNotEquals("修改经度后不应再与原坐标相等", origin, moved)
    }

    @Test
    fun `坐标按定义顺序解构出纬度与经度`() {
        val (lat, lng) = Coordinates(39.9059702, 116.3851622)

        assertEquals(39.9059702, lat, 1e-9)
        assertEquals(116.3851622, lng, 1e-9)
    }
}