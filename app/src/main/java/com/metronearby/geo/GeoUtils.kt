package com.metronearby.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 球面距离计算。站点库统一存 WGS-84，因此在未引入高德/百度坐标前
 * 不需要做坐标系转换，直接计算即可。
 */
object GeoUtils {

    private const val EARTH_RADIUS_METERS = 6_371_008.8

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val deltaLat = Math.toRadians(lat2 - lat1)
        val deltaLng = Math.toRadians(lng2 - lng1)
        val radLat1 = Math.toRadians(lat1)
        val radLat2 = Math.toRadians(lat2)

        val a = sin(deltaLat / 2).pow(2) +
            cos(radLat1) * cos(radLat2) * sin(deltaLng / 2).pow(2)
        return 2 * EARTH_RADIUS_METERS * asin(sqrt(a))
    }
}