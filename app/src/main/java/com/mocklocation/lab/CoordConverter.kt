package com.mocklocation.lab

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local approximate GCJ-02 / WGS-84 conversion model, independent of the map SDK.
 * Numerical inversion checks consistency with this model; it does not establish survey accuracy.
 * Coordinates outside the existing model's bounds pass through unchanged.
 */
object CoordConverter {

    private const val PI = Math.PI
    private const val A = 6378245.0 // 长半轴
    private const val EE = 0.00669342162296594323 // 偏心率平方
    private const val INVERSE_TOLERANCE = 1e-8
    private const val MAX_INVERSE_STEPS = 12

    fun outOfChina(lat: Double, lng: Double): Boolean {
        requireValidCoordinates(lat, lng)
        return lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    /** WGS-84 -> GCJ-02 */
    fun wgs84ToGcj02(wgsLat: Double, wgsLng: Double): DoubleArray {
        if (outOfChina(wgsLat, wgsLng)) return doubleArrayOf(wgsLat, wgsLng)
        val dLat = transformLat(wgsLng - 105.0, wgsLat - 35.0)
        val dLng = transformLng(wgsLng - 105.0, wgsLat - 35.0)
        val radLat = wgsLat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        val offsetLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        val offsetLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return doubleArrayOf(wgsLat + offsetLat, wgsLng + offsetLng)
    }

    /** Solves the local forward model, rather than applying its offset once at a GCJ-02 point. */
    fun gcj02ToWgs84(gcjLat: Double, gcjLng: Double): DoubleArray {
        if (outOfChina(gcjLat, gcjLng)) return doubleArrayOf(gcjLat, gcjLng)
        var latitude = gcjLat
        var longitude = gcjLng
        repeat(MAX_INVERSE_STEPS) {
            val projected = wgs84ToGcj02(latitude, longitude)
            val latitudeError = projected[0] - gcjLat
            val longitudeError = projected[1] - gcjLng
            if (abs(latitudeError) <= INVERSE_TOLERANCE && abs(longitudeError) <= INVERSE_TOLERANCE) {
                return doubleArrayOf(latitude, longitude)
            }
            latitude -= latitudeError
            longitude -= longitudeError
        }
        throw IllegalArgumentException("坐标转换未收敛，请重新确认地点")
    }

    private fun requireValidCoordinates(lat: Double, lng: Double) {
        require(lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0) {
            "经纬度必须为有效范围内的有限数值"
        }
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
