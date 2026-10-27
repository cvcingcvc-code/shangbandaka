package com.mocklocation.lab

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

    fun addTestProvider(lm: LocationManager) {
        @Suppress("DEPRECATION")
        lm.addTestProvider(
            PROVIDER_NAME, false, false, false, false, true, true, true,
            Criteria.POWER_LOW, Criteria.ACCURACY_FINE
        )
        try {
            lm.setTestProviderEnabled(PROVIDER_NAME, true)
        } catch (e: Exception) {
            try { lm.removeTestProvider(PROVIDER_NAME) } catch (cleanup: Exception) { e.addSuppressed(cleanup) }
            throw e
        }
    }

    fun setMockLocation(lm: LocationManager, lat: Double, lng: Double, accuracy: Float = 5f) {
        val location = Location(PROVIDER_NAME).apply {
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
        lm.setTestProviderLocation(PROVIDER_NAME, location)
    }

    fun removeTestProvider(lm: LocationManager) {
        lm.removeTestProvider(PROVIDER_NAME)
    }
}
