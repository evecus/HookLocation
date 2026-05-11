package com.me.hooklocation.utils

import kotlin.math.*

/**
 * WGS-84 ↔ GCJ-02 coordinate conversion.
 * GCJ-02 (火星坐标系) is used by Amap (高德), Tencent Maps, etc.
 */
object CoordConverter {

    private const val PI = Math.PI
    private const val A = 6378245.0          // semi-major axis of Krasovsky ellipsoid
    private const val EE = 0.00669342162296594323  // eccentricity squared

    /** Returns true if the coordinate is outside China (no offset needed) */
    private fun isOutOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y +
                0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x +
                0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    /**
     * Convert WGS-84 → GCJ-02.
     * Returns Pair(gcjLat, gcjLon).
     */
    fun wgs84ToGcj02(wgsLat: Double, wgsLon: Double): Pair<Double, Double> {
        if (isOutOfChina(wgsLat, wgsLon)) return Pair(wgsLat, wgsLon)

        var dLat = transformLat(wgsLon - 105.0, wgsLat - 35.0)
        var dLon = transformLon(wgsLon - 105.0, wgsLat - 35.0)

        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)

        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (A / sqrtMagic * cos(radLat) * PI)

        return Pair(wgsLat + dLat, wgsLon + dLon)
    }

    /**
     * Convert GCJ-02 → WGS-84 (approximate inverse).
     */
    fun gcj02ToWgs84(gcjLat: Double, gcjLon: Double): Pair<Double, Double> {
        if (isOutOfChina(gcjLat, gcjLon)) return Pair(gcjLat, gcjLon)
        val (dLat, dLon) = wgs84ToGcj02(gcjLat, gcjLon)
        return Pair(2 * gcjLat - dLat, 2 * gcjLon - dLon)
    }
}
