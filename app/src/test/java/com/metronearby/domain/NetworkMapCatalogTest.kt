package com.metronearby.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkMapCatalogTest {
    @Test
    fun `默认线网图是北京`() {
        val map = NetworkMapCatalog.find(null)
        assertEquals("beijing", map.id)
        assertEquals("北京", map.cityName)
        assertEquals("北京市", map.provinceName)
    }

    @Test
    fun `未知城市回退北京地图`() {
        assertEquals(NetworkMapCatalog.DEFAULT_CITY_ID, NetworkMapCatalog.find("missing").id)
    }

    @Test
    fun `目录里的城市标识互不重复`() {
        assertTrue(NetworkMapCatalog.available.map { it.id }.let { it.size == it.distinct().size })
    }
}
