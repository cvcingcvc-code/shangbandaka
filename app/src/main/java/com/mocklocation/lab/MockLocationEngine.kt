package com.mocklocation.lab

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock

/**
 * 封装 LocationManager.addTestProvider / setTestProviderLocation / removeTestProvider。
 * 不使用 Root、Hook、反检测；只操作自己注册的测试 provider。
 */
object MockLocationEngine {

    const val PROVIDER_NAME = LocationManager.GPS_PROVIDER

    /** 注册测试 provider（幂等：先尝试移除再注册） */
    fun addTestProvider(lm: LocationManager): Boolean {
        return try {
            try { lm.removeTestProvider(PROVIDER_NAME) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 需要指定 properties
                lm.addTestProvider(
                    PROVIDER_NAME,
                    false,              // requiresNetwork
                    false,              // requiresSatellite
                    false,              // requiresCell
                    false,              // hasMonetaryCost
                    true,               // supportsAltitude
                    true,               // supportsSpeed
                    true,               // supportsBearing
                    android.location.provider.ProviderProperties.POWER_USAGE_LOW,
                    android.location.provider.ProviderProperties.ACCURACY_FINE
                )
            } else {
                @Suppress("DEPRECATION")
                lm.addTestProvider(
                    PROVIDER_NAME,
                    false, false, false, false, true, true, true,
                    android.location.Criteria.POWER_LOW,
                    android.location.Criteria.ACCURACY_FINE
                )
            }
            lm.setTestProviderEnabled(PROVIDER_NAME, true)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 设置一条模拟坐标 */
    fun setMockLocation(lm: LocationManager, lat: Double, lng: Double, accuracy: Float = 5f): Boolean {
        return try {
            val loc = Location(PROVIDER_NAME).apply {
                latitude = lat
                longitude = lng
                altitude = 50.0
                this.accuracy = accuracy
                bearing = 0f
                speed = 0f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                // 明确标记 isMock（API 31+ 公开 API）
                // API < 31：系统对 setTestProviderLocation 注入的坐标自动标记 isFromMockProvider
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    this.isMock = true
                }
            }
            lm.setTestProviderLocation(PROVIDER_NAME, loc)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 释放自己注册的测试 provider（不影响真实 GPS 硬件） */
    fun removeTestProvider(lm: LocationManager): Boolean {
        return try {
            lm.removeTestProvider(PROVIDER_NAME)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 模拟应用是否已在本 app 上启用（系统开发者选项） */
    fun isMockAppSelected(lm: LocationManager): Boolean {
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
                !lm.getProviders(false).none { it == LocationManager.GPS_PROVIDER }
        } catch (e: SecurityException) {
            false
        }
    }
}
