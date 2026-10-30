package com.mocklocation.lab

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

class MockLocationService : Service() {
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var foreground = false
    private var lat = 0.0
    private var lng = 0.0
    private var listening = false
    private val verified = mutableSetOf<String>()
    private val reader = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val provider = location.provider ?: return
            val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
                else @Suppress("DEPRECATION") location.isFromMockProvider
            if (running && mock && kotlin.math.abs(location.latitude - lat) < 0.000001 &&
                kotlin.math.abs(location.longitude - lng) < 0.000001 &&
                MockLocationStatus.current.phase == MockLocationStatus.Phase.REGISTERING &&
                provider in PROVIDERS) {
                try {
                    verified.add(provider)
                    Log.i(TAG, "已验证 $provider 模拟坐标")
                    if (!verified.containsAll(PROVIDERS)) return
                    updateNotification("已注入 %.6f, %.6f".format(java.util.Locale.US, lat, lng))
                    MockLocationStatus.update(MockLocationStatus.Phase.SUCCESS,
                        "GPS 和网络模拟已成功：系统定位接口读取到 %.6f, %.6f".format(java.util.Locale.US, lat, lng))
                } catch (e: Exception) {
                    fail("更新定位服务通知失败", e)
                }
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                check(MockLocationEngine.isMockAppSelected(this@MockLocationService)) {
                    "模拟位置应用授权已撤销"
                }
                injectAll()
                check(MockLocationEngine.isMockAppSelected(this@MockLocationService)) {
                    "模拟位置应用授权已撤销"
                }
                handler.postDelayed(this, 1000)
            } catch (e: Exception) {
                fail("更新测试位置失败", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            if (intent == null || !intent.hasExtra(EXTRA_LAT) || !intent.hasExtra(EXTRA_LNG)) {
                throw IllegalArgumentException("缺少目标坐标；请重新选择位置")
            }
            lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
            lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
            require(lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0) {
                "目标坐标无效"
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("未授予精确定位权限")
            }
            if (!MockLocationEngine.isMockAppSelected(this)) {
                throw SecurityException("请进入开发者选项，选择 Mock Location Lab 作为模拟位置信息应用。")
            }
            startAsForeground()
            foreground = true
            running = false
            handler.removeCallbacksAndMessages(null)
            MockLocationStatus.update(MockLocationStatus.Phase.REGISTERING, "正在注册测试位置")
            verified.clear()
            for (provider in PROVIDERS) {
                // Persist before registration: Android can kill this process without onDestroy().
                check(setCleanupPending(this, provider, true)) { "无法保存 $provider 测试位置清理状态" }
                try { MockLocationEngine.addTestProvider(lm, provider) }
                catch (e: Exception) { throw IllegalStateException("$provider 注册失败：${e.message}", e) }
                check(MockLocationEngine.isMockAppSelected(this)) { "模拟位置应用授权已撤销" }
            }
            if (listening) lm.removeUpdates(reader)
            listening = true
            for (provider in PROVIDERS) lm.requestLocationUpdates(provider, 0L, 0f, reader, Looper.getMainLooper())
            running = true
            injectAll()
            handler.postDelayed(tick, 1000)
            handler.postDelayed({
                if (running && MockLocationStatus.current.phase == MockLocationStatus.Phase.REGISTERING) {
                    fail("验证测试位置失败", IllegalStateException("未在限时内验证 ${PROVIDERS - verified} 的所选模拟坐标"))
                }
            }, 10000)
        } catch (e: Exception) {
            fail("启动模拟失败", e)
        }
        // Never restart without the explicitly chosen coordinates.
        return START_NOT_STICKY
    }

    private fun fail(stage: String, e: Exception) {
        Log.e(TAG, stage, e)
        running = false
        handler.removeCallbacks(tick)
        val cleanupError = cleanupProvider()
        val detail = "$stage：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}" +
            (cleanupError?.let { "；清理测试位置失败：$it" } ?: "")
        MockLocationStatus.update(MockLocationStatus.Phase.FAILED, detail)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    private fun injectAll() {
        for (provider in PROVIDERS) {
            try { MockLocationEngine.setMockLocation(lm, lat, lng, provider = provider) }
            catch (e: Exception) { throw IllegalStateException("$provider 注入失败：${e.message}", e) }
        }
    }

    private fun cleanupProvider(): String? {
        if (listening) {
            try { lm.removeUpdates(reader) } catch (e: Exception) { Log.w(TAG, "Failed to remove location listener", e) }
            listening = false
        }
        return cleanupPendingProviders(this, lm)
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val cleanupError = cleanupProvider()
        if (cleanupError != null) {
            val previous = MockLocationStatus.current
            val detail = if (previous.phase == MockLocationStatus.Phase.FAILED) {
                if (previous.message.contains(cleanupError)) previous.message
                else "${previous.message}；清理测试位置失败：$cleanupError"
            } else "清理测试位置失败：$cleanupError"
            MockLocationStatus.update(MockLocationStatus.Phase.FAILED, detail)
        } else if (stopRequested) {
            MockLocationStatus.update(MockLocationStatus.Phase.STOPPED, "已请求停止并移除测试位置；请验证系统定位是否恢复")
        } else if (MockLocationStatus.current.phase != MockLocationStatus.Phase.FAILED) {
            MockLocationStatus.update(MockLocationStatus.Phase.FAILED, "定位服务已退出")
        }
        stopRequested = false
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Mock Location Lab")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private fun startAsForeground() {
        val n = notification("正在准备测试位置")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun updateNotification(text: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, notification(text))
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, "定位模拟", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIF_ID = 1001
        private const val PREFS = "mock_location_state"
        private const val KEY_CLEANUP_PENDING = "cleanup_pending"
        private const val KEY_NETWORK_CLEANUP_PENDING = "network_cleanup_pending"
        private val PROVIDERS = listOf(MockLocationEngine.PROVIDER_NAME, MockLocationEngine.NETWORK_PROVIDER)
        private const val CLEANUP_REQUIRED = "无法确认测试位置是否释放；请重新选择本应用为模拟位置应用后点击停止，重试移除当前 GPS 和网络测试位置"
        private const val CLEANUP_READY = "存在待清理的测试位置记录；请点击停止模拟，清理 GPS 和网络测试位置后再开始"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private var stopRequested = false
        private var requestId = 0L

        private fun isCleanupPending(ctx: Context): Boolean =
            PROVIDERS.any { isCleanupPending(ctx, it) }

        private fun cleanupKey(provider: String): String = when (provider) {
            MockLocationEngine.PROVIDER_NAME -> KEY_CLEANUP_PENDING // compatible with earlier GPS-only versions
            MockLocationEngine.NETWORK_PROVIDER -> KEY_NETWORK_CLEANUP_PENDING
            else -> error("未知测试位置 provider：$provider")
        }

        private fun isCleanupPending(ctx: Context, provider: String): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(cleanupKey(provider), false)

        fun hasPendingCleanup(ctx: Context): Boolean = isCleanupPending(ctx)

        private fun pendingCleanupMessage(ctx: Context): String = try {
            if (MockLocationEngine.isMockAppSelected(ctx)) CLEANUP_READY else CLEANUP_REQUIRED
        } catch (e: Exception) {
            Log.e(TAG, "Unable to check mock-location authorization", e)
            "无法检查模拟位置授权：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}；请检查授权并点击停止模拟"
        }

        private fun setCleanupPending(ctx: Context, provider: String, pending: Boolean): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(cleanupKey(provider), pending).commit()

        private fun cleanupPendingProviders(ctx: Context, lm: LocationManager): String? {
            val errors = mutableListOf<String>()
            for (provider in PROVIDERS) {
                if (!isCleanupPending(ctx, provider)) continue
                try {
                    if (!MockLocationEngine.isMockAppSelected(ctx)) {
                        errors.add("$provider：$CLEANUP_REQUIRED")
                        continue
                    }
                    MockLocationEngine.removeTestProvider(lm, provider)
                    if (!MockLocationEngine.isMockAppSelected(ctx) || !setCleanupPending(ctx, provider, false)) {
                        errors.add("$provider：$CLEANUP_REQUIRED")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove test provider $provider", e)
                    errors.add("$provider：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
                }
            }
            return errors.takeIf { it.isNotEmpty() }?.joinToString("；")
        }

        /** A persisted marker is not proof of a running service. Never restart from it. */
        fun reportPendingCleanup(ctx: Context) {
            if (isCleanupPending(ctx) && MockLocationStatus.current.phase == MockLocationStatus.Phase.IDLE) {
                MockLocationStatus.update(MockLocationStatus.Phase.FAILED, pendingCleanupMessage(ctx))
            }
        }

        fun start(ctx: Context, lat: Double, lng: Double) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (isCleanupPending(ctx)) {
                MockLocationStatus.update(MockLocationStatus.Phase.FAILED, pendingCleanupMessage(ctx))
                return
            }
            val id = ++requestId
            stopRequested = false
            MockLocationStatus.update(MockLocationStatus.Phase.PREPARING, "准备启动")
            try {
                val intent = Intent(ctx, MockLocationService::class.java)
                    .putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LNG, lng)
                ContextCompat.startForegroundService(ctx, intent)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (requestId == id && MockLocationStatus.current.phase in setOf(
                            MockLocationStatus.Phase.PREPARING, MockLocationStatus.Phase.REGISTERING)) {
                        Log.e(TAG, "Timed out waiting for foreground service")
                        MockLocationStatus.update(MockLocationStatus.Phase.FAILED, "启动模拟失败：未收到定位服务启动结果")
                        ctx.applicationContext.stopService(Intent(ctx, MockLocationService::class.java))
                    }
                }, 15000)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot start foreground service", e)
                MockLocationStatus.update(MockLocationStatus.Phase.FAILED,
                    "启动前台服务失败：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
            }
        }

        fun stop(ctx: Context) {
            check(Looper.myLooper() == Looper.getMainLooper())
            ++requestId
            stopRequested = true
            MockLocationStatus.update(MockLocationStatus.Phase.PREPARING, "正在停止并清理测试位置")
            try {
                if (!ctx.stopService(Intent(ctx, MockLocationService::class.java))) {
                    stopRequested = false
                    if (!isCleanupPending(ctx)) {
                        MockLocationStatus.update(MockLocationStatus.Phase.STOPPED, "没有待清理记录；请验证系统定位状态")
                    } else if (!MockLocationEngine.isMockAppSelected(ctx)) {
                        MockLocationStatus.update(MockLocationStatus.Phase.FAILED, CLEANUP_REQUIRED)
                    } else {
                        // Explicit user retry: the GPS test provider is system-wide and its owner
                        // cannot be verified via the public API.
                        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                        val error = cleanupPendingProviders(ctx, lm)
                        if (error != null) {
                            MockLocationStatus.update(MockLocationStatus.Phase.FAILED, "清理测试位置失败：$error")
                        } else {
                            MockLocationStatus.update(MockLocationStatus.Phase.STOPPED,
                                "已请求移除当前 GPS 和网络测试位置；请验证系统定位是否恢复")
                        }
                    }
                }
            } catch (e: Exception) {
                stopRequested = false
                Log.e(TAG, "Cannot stop service", e)
                MockLocationStatus.update(MockLocationStatus.Phase.FAILED,
                    "停止服务失败：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
            }
        }
    }
}
