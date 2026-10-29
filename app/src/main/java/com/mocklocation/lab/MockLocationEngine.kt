package com.mocklocation.lab

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.os.SystemClock

/** Android's supported test-provider APIs. Exceptions are propagated for the UI and logs. */
object MockLocationEngine {
    const val PROVIDER_NAME = LocationManager.GPS_PROVIDER
    const val NETWORK_PROVIDER = LocationManager.NETWORK_PROVIDER

    fun isMockAppSelected(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    // The legacy overload supports API 26; these Criteria constants have the same
    // integer values as the ProviderProperties constants introduced in API 31.
    @SuppressLint("WrongConstant")
    fun addTestProvider(lm: LocationManager, provider: String = PROVIDER_NAME) {
        @Suppress("DEPRECATION")
        lm.addTestProvider(
            provider, false, false, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE
        )
        try {
            lm.setTestProviderEnabled(provider, true)
        } catch (e: Exception) {
            try { lm.removeTestProvider(provider) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    fun setMockLocation(lm: LocationManager, lat: Double, lng: Double, accuracy: Float = 5f, provider: String = PROVIDER_NAME) {
        val location = Location(provider).apply {
            latitude = lat
            longitude = lng
            altitude = 50.0
            this.accuracy = accuracy
            bearing = 0f
            speed = 0f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        // Android marks locations delivered by a test provider as mock.
        lm.setTestProviderLocation(provider, location)
    }

    fun removeTestProvider(lm: LocationManager, provider: String = PROVIDER_NAME) {
        lm.removeTestProvider(provider)
    }
}
