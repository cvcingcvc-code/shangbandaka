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
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 模拟期间前台服务：常驻通知，约每秒更新一次测试坐标。
 * 停止时释放所有自己注册的测试 provider。
 */
class MockLocationService : Service() {

    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var lat = 0.0
    private var lng = 0.0
    private var running = false
    private var tickCount = 0L

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            tickCount++
            val ok = MockLocationEngine.setMockLocation(lm, lat, lng)
            updateNotification(ok)
            handler.postDelayed(this, 1000L) // 约 1 秒
        }
    }

    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            ACTION_STOP -> {
                stopMock()
                return START_NOT_STICKY
            }
            else -> {
                lat = intent?.getDoubleExtra(EXTRA_LAT, 39.908722) ?: 39.908722
                lng = intent?.getDoubleExtra(EXTRA_LNG, 116.397499) ?: 116.397499
                startMockInternal()
            }
        }
        return START_STICKY
    }

    private fun startMockInternal() {
        // 前台服务必须先 startForeground（合规）
        startAsForeground()
        // 定位前台服务在 API 34+ 需要 ACCESS_FINE_LOCATION 已授予
        val fineGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val registered = if (fineGranted) MockLocationEngine.addTestProvider(lm) else false
        running = registered
        if (registered) {
            // 立即注入第一条
            MockLocationEngine.setMockLocation(lm, lat, lng)
            handler.postDelayed(tickRunnable, 1000L)
            updateNotification(true)
        } else {
            updateNotification(false)
        }
    }

    private fun stopMock() {
        running = false
        handler.removeCallbacks(tickRunnable)
        // 释放自己注册的 provider（不影响真实 GPS 硬件）
        try { MockLocationEngine.removeTestProvider(lm) } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground() {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification: Notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("定位模拟中")
                    .setContentText("每秒更新测试坐标 · 点击返回应用")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
            } else {
                Notification.Builder(this)
                    .setContentTitle("定位模拟中")
                    .setContentText("每秒更新测试坐标 · 点击返回应用")
                    .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build()
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(ok: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (ok) {
            "lat=%.5f lng=%.5f · 已更新 %d 次".format(lat, lng, tickCount)
        } else {
            "注入失败：请检查定位权限与模拟位置应用设置"
        }
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("定位模拟中" + if (ok) "" else "（异常）")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "定位模拟", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(ch)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tickRunnable)
        try { MockLocationEngine.removeTestProvider(lm) } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIF_ID = 1001
        const val ACTION_STOP = "com.mocklocation.lab.STOP"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"

        fun start(ctx: Context, lat: Double, lng: Double) {
            val i = Intent(ctx, MockLocationService::class.java)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LNG, lng)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, MockLocationService::class.java).setAction(ACTION_STOP)
            ctx.startService(i)
        }
    }
}
