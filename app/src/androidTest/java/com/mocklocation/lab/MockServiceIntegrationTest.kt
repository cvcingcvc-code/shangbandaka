package com.mocklocation.lab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.test.InstrumentationTestCase
import java.io.FileInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Emulator-only service regression. This is not physical-device or third-party-app acceptance. */
@Suppress("DEPRECATION")
class MockServiceIntegrationTest : InstrumentationTestCase() {
    private var originalMode = "default"
    private var hadFine = false
    private var hadCoarse = false
    private var emulatorVerified = false
    private val ctx get() = instrumentation.targetContext
    private val stopResults = setOf(MockLocationStatus.Phase.STOPPED_WAITING,
        MockLocationStatus.Phase.CLEANUP_FAILED, MockLocationStatus.Phase.FAILED)
    private val startResults = setOf(MockLocationStatus.Phase.RUNNING,
        MockLocationStatus.Phase.CLEANUP_FAILED, MockLocationStatus.Phase.FAILED)

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }.also { descriptor.close() }
    }

    override fun setUp() {
        super.setUp()
        // These tests change AppOps. Do not remove this physical-device guard.
        check(shell("getprop ro.kernel.qemu").trim() == "1") {
            "MockServiceIntegrationTest may only run on an Android emulator"
        }
        emulatorVerified = true
        val appops = shell("appops get com.mocklocation.lab android:mock_location")
        originalMode = Regex("(?i)mock_location:\\s*(allow|ignore|deny|default)")
            .find(appops)?.groupValues?.get(1)?.lowercase() ?: "default"
        hadFine = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        hadCoarse = ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        shell("pm grant com.mocklocation.lab android.permission.ACCESS_FINE_LOCATION")
        shell("pm grant com.mocklocation.lab android.permission.ACCESS_COARSE_LOCATION")
        val activity = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.startActivitySync(activity)
        instrumentation.waitForIdleSync()
    }

    override fun tearDown() {
        try {
            if (emulatorVerified) {
                try {
                    // A revoked-AppOp test must still clean its providers before restoring the original mode.
                    shell("appops set com.mocklocation.lab android:mock_location allow")
                    instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
                    val stopped = awaitStatus(stopResults)
                    assertEquals(stopped.message, MockLocationStatus.Phase.STOPPED_WAITING, stopped.phase)
                    assertFalse("Teardown must not leave pending providers", MockLocationService.hasPendingCleanup(ctx))
                } finally {
                    shell("appops set com.mocklocation.lab android:mock_location $originalMode")
                    if (!hadFine) shell("pm revoke com.mocklocation.lab android.permission.ACCESS_FINE_LOCATION")
                    if (!hadCoarse) shell("pm revoke com.mocklocation.lab android.permission.ACCESS_COARSE_LOCATION")
                }
            }
        } finally {
            super.tearDown()
        }
    }

    private fun awaitStatus(phases: Set<MockLocationStatus.Phase>, timeout: Long = 15000): MockLocationStatus.Status {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = MockLocationStatus.current
            if (state.phase in phases) return state
            Thread.sleep(100)
        }
        fail("Timed out waiting for $phases; last status: ${MockLocationStatus.current}")
        throw AssertionError("unreachable")
    }

    /** Reject cached callbacks predating this subscription, even if they match the target. */
    private fun readFreshLocation(provider: String): Location {
        val manager = ctx.getSystemService(LocationManager::class.java)
        val received = AtomicReference<Location>()
        val latch = CountDownLatch(1)
        var subscribedAtNanos = Long.MAX_VALUE
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (location.elapsedRealtimeNanos > subscribedAtNanos) {
                    received.set(Location(location))
                    latch.countDown()
                }
            }
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        try {
            instrumentation.runOnMainSync {
                subscribedAtNanos = SystemClock.elapsedRealtimeNanos()
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            }
            assertTrue("No fresh $provider callback after subscription", latch.await(5000, TimeUnit.MILLISECONDS))
            val location = received.get()
            assertNotNull("$provider callback missing", location)
            assertTrue("$provider callback must be newer than subscription", location.elapsedRealtimeNanos > subscribedAtNanos)
            return location
        } finally {
            instrumentation.runOnMainSync { manager.removeUpdates(listener) }
        }
    }

    private fun assertFreshTarget(lat: Double, lng: Double) {
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            val location = readFreshLocation(provider)
            assertEquals("$provider source", provider, location.provider)
            assertTrue("$provider must report a mock", location.isFromMockProvider)
            assertEquals("$provider latitude", lat, location.latitude, 0.000001)
            assertEquals("$provider longitude", lng, location.longitude, 0.000001)
        }
    }

    private fun assertStoppedWaiting() {
        val stopped = awaitStatus(stopResults)
        assertEquals(stopped.message, MockLocationStatus.Phase.STOPPED_WAITING, stopped.phase)
        assertFalse("Provider removal must clear the cleanup markers", MockLocationService.hasPendingCleanup(ctx))
        assertTrue("Stop must leave normal location unverified", stopped.message.contains("等待"))
    }

    fun testDeniedAppOpDoesNotReportRunning() {
        shell("appops set com.mocklocation.lab android:mock_location ignore")
        instrumentation.runOnMainSync { MockLocationService.start(ctx, 35.681236, 139.767125) }
        val status = awaitStatus(startResults)
        assertEquals(status.message, MockLocationStatus.Phase.FAILED, status.phase)
        assertTrue(status.message, status.message.contains("模拟位置信息应用"))
    }

    fun testManifestDeclaresMockLocationForSystemPicker() {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS)
        assertTrue("Mock location declaration is required for the system app picker",
            info.requestedPermissions?.contains("android.permission.ACCESS_MOCK_LOCATION") == true)
    }

    fun testInjectionWithoutRegisteredProviderPropagatesError() {
        shell("appops set com.mocklocation.lab android:mock_location allow")
        instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
        assertStoppedWaiting()
        val manager = ctx.getSystemService(LocationManager::class.java)
        val error = runCatching {
            MockLocationEngine.setMockLocation(manager, 35.681236, 139.767125)
        }.exceptionOrNull()
        assertNotNull("Injection without a registered test provider must propagate an error", error)
    }

    fun testTenStartStopCyclesAndRevokedPermission() {
        shell("appops set com.mocklocation.lab android:mock_location allow")
        repeat(10) { iteration ->
            val lat = 35.681236 + iteration * 0.01
            instrumentation.runOnMainSync { MockLocationService.start(ctx, lat, 139.767125) }
            val started = awaitStatus(startResults)
            assertEquals(started.message, MockLocationStatus.Phase.RUNNING, started.phase)
            assertFreshTarget(lat, 139.767125)
            instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
            assertStoppedWaiting()
        }
        instrumentation.runOnMainSync { MockLocationService.start(ctx, 35.681236, 139.767125) }
        val started = awaitStatus(startResults)
        assertEquals(started.message, MockLocationStatus.Phase.RUNNING, started.phase)
        shell("appops set com.mocklocation.lab android:mock_location ignore")
        val failed = awaitStatus(setOf(MockLocationStatus.Phase.CLEANUP_FAILED, MockLocationStatus.Phase.FAILED), 10000)
        assertTrue(failed.message, failed.message.contains("授权已撤销"))
        assertEquals("Removal cannot be confirmed without authorization", MockLocationStatus.Phase.CLEANUP_FAILED, failed.phase)
        assertTrue("Revoked authorization must preserve the pending cleanup marker", MockLocationService.hasPendingCleanup(ctx))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
        val blocked = awaitStatus(setOf(MockLocationStatus.Phase.CLEANUP_FAILED), 5000)
        assertTrue("A stop without authorization must not claim cleanup", MockLocationService.hasPendingCleanup(ctx))
        assertTrue(blocked.message, blocked.message.contains("无法确认"))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { MockLocationService.start(ctx, 35.681236, 139.767125) }
        val denied = awaitStatus(setOf(MockLocationStatus.Phase.FAILED, MockLocationStatus.Phase.CLEANUP_FAILED))
        assertTrue(denied.message, denied.message.contains("重新选择") || denied.message.contains("模拟位置信息应用"))
        assertTrue(MockLocationService.hasPendingCleanup(ctx))
        instrumentation.waitForIdleSync()
        shell("appops set com.mocklocation.lab android:mock_location allow")
        instrumentation.runOnMainSync { MockLocationService.start(ctx, 35.681236, 139.767125) }
        val recovered = awaitStatus(startResults)
        assertEquals("Start should recover after authorization is restored: ${recovered.message}", MockLocationStatus.Phase.RUNNING, recovered.phase)
        instrumentation.runOnMainSync {
            MockLocationService.stop(ctx)
            MockLocationService.stop(ctx)
        }
        assertStoppedWaiting()
    }

    fun testStartStopStartCancelsUndeliveredStartAndUsesNewTarget() {
        shell("appops set com.mocklocation.lab android:mock_location allow")
        instrumentation.runOnMainSync {
            MockLocationService.start(ctx, 35.681236, 139.767125)
            MockLocationService.stop(ctx)
            MockLocationService.start(ctx, 31.230416, 121.473701)
        }
        val started = awaitStatus(startResults)
        assertEquals(started.message, MockLocationStatus.Phase.RUNNING, started.phase)
        assertFreshTarget(31.230416, 121.473701)
        instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
        assertStoppedWaiting()
    }

    fun testDuplicateStartsKeepFirstTargetAndDuplicateStopsDoNotRestart() {
        shell("appops set com.mocklocation.lab android:mock_location allow")
        instrumentation.runOnMainSync {
            MockLocationService.start(ctx, 31.230416, 121.473701)
            MockLocationService.start(ctx, 35.681236, 139.767125)
        }
        val started = awaitStatus(startResults)
        assertEquals(started.message, MockLocationStatus.Phase.RUNNING, started.phase)
        assertFreshTarget(31.230416, 121.473701)
        instrumentation.runOnMainSync { MockLocationService.start(ctx, 35.681236, 139.767125) }
        assertEquals("An active session must keep its target", MockLocationStatus.Phase.RUNNING, MockLocationStatus.current.phase)
        assertFreshTarget(31.230416, 121.473701)
        instrumentation.runOnMainSync {
            MockLocationService.stop(ctx)
            MockLocationService.stop(ctx)
        }
        assertStoppedWaiting()
        instrumentation.waitForIdleSync()
        assertEquals("A duplicate stop must not revive the session", MockLocationStatus.Phase.STOPPED_WAITING, MockLocationStatus.current.phase)
    }
}
