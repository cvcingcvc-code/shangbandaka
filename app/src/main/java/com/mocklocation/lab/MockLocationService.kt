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
    private var registered = false
    private var running = false
    private var foreground = false
    private var lat = 0.0
    private var lng = 0.0
    private var listening = false
    private val reader = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
                else @Suppress("DEPRECATION") location.isFromMockProvider
            if (running && mock && kotlin.math.abs(location.latitude - lat) < 0.000001 &&
                kotlin.math.abs(location.longitude - lng) < 0.000001 &&
                MockLocationStatus.current.phase == MockLocationStatus.Phase.REGISTERING) {
                MockLocationStatus.update(MockLocationStatus.Phase.SUCCESS,
                    "模拟已成功：系统定位接口读取到 %.6f, %.6f".format(java.util.Locale.US, lat, lng))
                updateNotification("已注入 %.6f, %.6f".format(java.util.Locale.US, lat, lng))
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
                MockLocationEngine.setMockLocation(lm, lat, lng)
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
            if (registered) {
                MockLocationEngine.removeTestProvider(lm)
                registered = false
            }
            MockLocationStatus.update(MockLocationStatus.Phase.REGISTERING, "正在注册测试位置")
            MockLocationEngine.addTestProvider(lm)
            registered = true
            // App-op may change between the preflight and the injection.
            check(MockLocationEngine.isMockAppSelected(this)) { "模拟位置应用授权已撤销" }
            if (listening) lm.removeUpdates(reader)
            lm.requestLocationUpdates(MockLocationEngine.PROVIDER_NAME, 0L, 0f, reader, Looper.getMainLooper())
            listening = true
            running = true
            MockLocationEngine.setMockLocation(lm, lat, lng)
            handler.postDelayed(tick, 1000)
            handler.postDelayed({
                if (running && MockLocationStatus.current.phase == MockLocationStatus.Phase.REGISTERING) {
                    fail("验证测试位置失败", IllegalStateException("系统定位接口未在限时内返回所选模拟坐标"))
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

    private fun cleanupProvider(): String? {
        if (listening) {
            try { lm.removeUpdates(reader) } catch (e: Exception) { Log.w(TAG, "Failed to remove location listener", e) }
            listening = false
        }
        if (!registered) return null
        return try {
            MockLocationEngine.removeTestProvider(lm)
            registered = false
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove test provider", e)
            "${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}"
        }
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val cleanupError = cleanupProvider()
        if (cleanupError != null) {
            MockLocationStatus.update(MockLocationStatus.Phase.FAILED, "清理测试位置失败：$cleanupError")
        } else if (stopRequested) {
            MockLocationStatus.update(MockLocationStatus.Phase.STOPPED, "已停止；测试位置已释放")
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
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private var stopRequested = false
        private var requestId = 0L

        fun start(ctx: Context, lat: Double, lng: Double) {
            check(Looper.myLooper() == Looper.getMainLooper())
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
            try {
                if (!ctx.stopService(Intent(ctx, MockLocationService::class.java))) {
                    stopRequested = false
                    MockLocationStatus.update(MockLocationStatus.Phase.STOPPED, "已停止")
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
