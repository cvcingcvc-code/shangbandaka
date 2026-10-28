package com.mocklocation.lab

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.ParcelFileDescriptor
import android.test.InstrumentationTestCase
import java.io.FileInputStream

/** Run on an emulator: temporarily changes this app's mock-location AppOp and restores it. */
@Suppress("DEPRECATION")
class MockServiceIntegrationTest : InstrumentationTestCase() {
    private var originalMode = "default"
    private var hadFine = false
    private var hadCoarse = false
    private val ctx get() = instrumentation.targetContext

    private fun shell(command: String): String {
        val descriptor: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return FileInputStream(descriptor.fileDescriptor).bufferedReader().use { it.readText() }.also { descriptor.close() }
    }

    override fun setUp() {
        super.setUp()
        // These tests modify the mock-location AppOp; never run them on a physical device.
        check(shell("getprop ro.kernel.qemu").trim() == "1") {
            "MockServiceIntegrationTest may only run on an Android emulator"
        }
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
            instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
            awaitStatus(setOf(MockLocationStatus.Phase.STOPPED, MockLocationStatus.Phase.FAILED), 5000)
            shell("appops set com.mocklocation.lab android:mock_location $originalMode")
            if (!hadFine) shell("pm revoke com.mocklocation.lab android.permission.ACCESS_FINE_LOCATION")
            if (!hadCoarse) shell("pm revoke com.mocklocation.lab android.permission.ACCESS_COARSE_LOCATION")
        } finally {
            super.tearDown()
        }
    }

    private fun awaitStatus(phases: Set<MockLocationStatus.Phase>, timeout: Long = 15000): MockLocationStatus.Status {
        val deadline = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < deadline) {
            val state = MockLocationStatus.current
            if (state.phase in phases) return state
            Thread.sleep(100)
        }
        fail("Timed out waiting for $phases; last status: ${MockLocationStatus.current}")
        throw AssertionError("unreachable")
    }

    fun testDeniedAppOpDoesNotReportSuccess() {
        shell("appops set com.mocklocation.lab android:mock_location ignore")
        instrumentation.runOnMainSync { MockLocationService.start(ctx, 35.681236, 139.767125) }
        val status = awaitStatus(setOf(MockLocationStatus.Phase.FAILED, MockLocationStatus.Phase.SUCCESS))
        assertEquals(status.message, MockLocationStatus.Phase.FAILED, status.phase)
        assertTrue(status.message, status.message.contains("模拟位置信息应用"))
    }

    fun testInjectionWithoutRegisteredProviderPropagatesError() {
        shell("appops set com.mocklocation.lab android:mock_location allow")
        instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
        val stopped = awaitStatus(setOf(MockLocationStatus.Phase.STOPPED, MockLocationStatus.Phase.FAILED), 5000)
        assertEquals(stopped.message, MockLocationStatus.Phase.STOPPED, stopped.phase)
        val manager = ctx.getSystemService(LocationManager::class.java)
        val error = runCatching {
            MockLocationEngine.setMockLocation(manager, 35.681236, 139.767125)
        }.exceptionOrNull()
        assertNotNull("Injection without a registered test provider must propagate an error", error)
    }

    fun testRepeatedStartStopAndRevokedPermission() {
        shell("appops set com.mocklocation.lab android:mock_location allow")
        repeat(2) { iteration ->
            val lat = 35.681236 + iteration * 0.01
            instrumentation.runOnMainSync { MockLocationService.start(ctx, lat, 139.767125) }
            val started = awaitStatus(setOf(MockLocationStatus.Phase.SUCCESS, MockLocationStatus.Phase.FAILED))
            assertEquals(started.message, MockLocationStatus.Phase.SUCCESS, started.phase)
            assertTrue(started.message, started.message.contains("%.6f".format(java.util.Locale.US, lat)))
            instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
            val stopped = awaitStatus(setOf(MockLocationStatus.Phase.STOPPED, MockLocationStatus.Phase.FAILED))
            assertEquals(stopped.message, MockLocationStatus.Phase.STOPPED, stopped.phase)
        }
        instrumentation.runOnMainSync { MockLocationService.start(ctx, 35.681236, 139.767125) }
        val started = awaitStatus(setOf(MockLocationStatus.Phase.SUCCESS, MockLocationStatus.Phase.FAILED))
        assertEquals(started.message, MockLocationStatus.Phase.SUCCESS, started.phase)
        shell("appops set com.mocklocation.lab android:mock_location ignore")
        val failed = awaitStatus(setOf(MockLocationStatus.Phase.FAILED), 10000)
        assertTrue(failed.message, failed.message.contains("授权已撤销"))
        assertTrue("Revoked authorization must preserve the pending cleanup marker",
            MockLocationService.hasPendingCleanup(ctx))
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
        val blocked = awaitStatus(setOf(MockLocationStatus.Phase.FAILED), 5000)
        assertTrue("A stop without authorization must not claim cleanup",
            MockLocationService.hasPendingCleanup(ctx))
        assertTrue(blocked.message, blocked.message.contains("无法确认"))
        shell("appops set com.mocklocation.lab android:mock_location allow")
        instrumentation.runOnMainSync { MockLocationService.stop(ctx) }
        val recovered = awaitStatus(setOf(MockLocationStatus.Phase.STOPPED), 10000)
        assertFalse("Cleanup should be requested again after authorization is restored",
            MockLocationService.hasPendingCleanup(ctx))
        assertTrue(recovered.message, recovered.message.contains("请验证"))
    }
}
