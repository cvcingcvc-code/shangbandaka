package com.mocklocation.lab

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import android.util.Log
import androidx.core.content.ContextCompat

/** Requests and callbacks are serialized by the main looper. Never resume a persisted target. */
class MockLocationService : Service() {
    private data class Target(val latitude: Double, val longitude: Double)
    private lateinit var lm: LocationManager
    private val handler = Handler(Looper.getMainLooper())
    private var target: Target? = null
    private var verifier: MockSessionVerifier? = null
    private var running = false
    private var listening = false
    private var foreground = false
    private var finishing = false
    private var terminal: MockLocationStatus.Status? = null
    private var lastWallMillis = 0L
    private var lastElapsedNanos = 0L
    private var sessionId = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewWakeAtNanos = 0L
    private val reader = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!running) return
            val mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock
                else @Suppress("DEPRECATION") location.isFromMockProvider
            val error = verifier?.accept(location.provider ?: return, location.latitude,
                location.longitude, mock, location.elapsedRealtimeNanos, SystemClock.elapsedRealtimeNanos())
            if (error != null) finishWithFailure("验证定位失败", IllegalStateException(error))
            else checkHealth()
        }
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {
            if (running && provider in PROVIDERS)
                finishWithFailure("定位入口被关闭", IllegalStateException("$provider 不再可用"))
        }
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                check(MockLocationEngine.isMockAppSelected(this@MockLocationService)) { "模拟位置应用授权已撤销" }
                check(ContextCompat.checkSelfPermission(this@MockLocationService, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) { "精确定位权限已撤销" }
                keepCpuAwake()
                injectAll()
                check(MockLocationEngine.isMockAppSelected(this@MockLocationService)) { "模拟位置应用授权已撤销" }
                checkHealth()
                if (running) handler.postDelayed(this, 1000)
            } catch (e: Exception) { finishWithFailure("更新测试位置失败", e) }
        }
    }
    override fun onCreate() {
        super.onCreate()
        lm = getSystemService(LocationManager::class.java)
        active = this
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getLongExtra(EXTRA_REQUEST, -1L) ?: -1L
        // Stop cancels even a start Intent Android has not delivered yet.
        if (id != commandId || !startPending || finishing || running) {
            Log.w(TAG, "Ignored stale/duplicate start request=$id current=$commandId")
            if (!startPending && !running && !finishing) finishStopped()
            return START_NOT_STICKY
        }
        startPending = false
        sessionId = id
        try {
            val lat = intent!!.getDoubleExtra(EXTRA_LAT, Double.NaN)
            val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
            require(lat.isFinite() && lat in -90.0..90.0 && lng.isFinite() && lng in -180.0..180.0) { "目标坐标无效" }
            check(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) { "未授予精确定位权限" }
            check(MockLocationEngine.isMockAppSelected(this)) { AUTH_REQUIRED }
            createChannel()
            startAsForeground()
            foreground = true
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.mocklocation.lab:FixedLocation")
                .apply { setReferenceCounted(false) }
            keepCpuAwake()
            target = Target(lat, lng)
            val recovery = cleanupPendingProviders(this, lm)
            check(recovery == null) { "清理上次测试位置失败：$recovery" }
            verifier = MockSessionVerifier(PROVIDERS.toSet(), SystemClock.elapsedRealtimeNanos(), lat, lng)
            Log.i(TAG, "session=$sessionId targetWgs84=$lat,$lng wall=${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtimeNanos()}")
            MockLocationStatus.update(MockLocationStatus.Phase.VERIFYING, "验证中：注册并检查 GPS、网络定位")
            for (provider in PROVIDERS) {
                // A crash between recording and registering leaves an uncertainty marker.
                check(setCleanupPending(this, provider, true)) { "无法保存 $provider 清理状态" }
                try { MockLocationEngine.addTestProvider(lm, provider) }
                catch (e: Exception) { throw IllegalStateException("$provider 注册失败：${e.message}", e) }
                check(MockLocationEngine.isMockAppSelected(this)) { "模拟位置应用授权已撤销" }
            }
            listening = true // Partial listener registration must also be rolled back.
            for (provider in PROVIDERS) lm.requestLocationUpdates(provider, 0L, 0f, reader, Looper.getMainLooper())
            running = true
            tick.run()
        } catch (e: Exception) { finishWithFailure("启动模拟失败", e) }
        return START_NOT_STICKY
    }
    private fun injectAll() {
        val fixed = checkNotNull(target)
        val wall = maxOf(System.currentTimeMillis(), lastWallMillis + 1)
        val elapsed = maxOf(SystemClock.elapsedRealtimeNanos(), lastElapsedNanos + 1)
        lastWallMillis = wall
        lastElapsedNanos = elapsed
        for (provider in PROVIDERS) {
            try { MockLocationEngine.setMockLocation(lm, fixed.latitude, fixed.longitude,
                provider = provider, timeMillis = wall, elapsedNanos = elapsed) }
            catch (e: Exception) { throw IllegalStateException("$provider 注入失败：${e.message}", e) }
        }
    }
    private fun checkHealth() {
        if (!running) return
        when (val health = verifier?.health(SystemClock.elapsedRealtimeNanos())) {
            is MockSessionVerifier.Health.Failure -> finishWithFailure("定位验证中断", IllegalStateException(health.reason))
            MockSessionVerifier.Health.Healthy -> if (MockLocationStatus.current.phase == MockLocationStatus.Phase.VERIFYING) {
                try {
                    updateNotification("固定地点模拟运行中")
                    MockLocationStatus.update(MockLocationStatus.Phase.RUNNING,
                        "运行中：GPS、网络定位持续验证通过；地图和微信需独立验证")
                    Log.i(TAG, "session=$sessionId verified gps,network elapsed=${SystemClock.elapsedRealtimeNanos()}")
                } catch (e: Exception) { finishWithFailure("更新通知失败", e) }
            }
            else -> Unit
        }
    }
    private fun keepCpuAwake() {
        val now = SystemClock.elapsedRealtimeNanos()
        if (now >= renewWakeAtNanos) {
            wakeLock?.acquire(30_000L)
            renewWakeAtNanos = now + 15_000_000_000L
        }
    }
    /** Explicit stop cleans here. onDestroy is a fallback, not the only cleanup path. */
    private fun release(): String? {
        running = false
        handler.removeCallbacksAndMessages(null)
        val errors = mutableListOf<String>()
        if (listening) {
            try { lm.removeUpdates(reader); listening = false }
            catch (e: Exception) { Log.e(TAG, "remove listener failed", e); errors.add("移除监听器失败：${e.message}") }
        }
        cleanupPendingProviders(this, lm)?.let { errors.add(it) }
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "release wake lock failed", e)
            errors.add("释放运行唤醒锁失败：${e.message}")
        }
        verifier = null
        return errors.takeIf { it.isNotEmpty() }?.joinToString("；")
    }
    private fun finishWithFailure(stage: String, cause: Exception) {
        Log.e(TAG, "session=$sessionId $stage elapsed=${SystemClock.elapsedRealtimeNanos()}", cause)
        val error = release()
        finish(MockLocationStatus.Status(
            if (error == null) MockLocationStatus.Phase.FAILED else MockLocationStatus.Phase.CLEANUP_FAILED,
            "$stage：${cause.javaClass.simpleName}: ${cause.message ?: "无详细信息"}" +
                (error?.let { "；清理失败：$it" } ?: "；已停止注入，等待独立端验证正常定位")))
    }
    private fun finishStopped() {
        if (finishing) return
        MockLocationStatus.update(MockLocationStatus.Phase.STOPPING, "停止中：停止注入并解除测试位置")
        finish(stoppedResult(release()))
    }
    private fun finish(result: MockLocationStatus.Status) {
        terminal = result
        finishing = true
        MockLocationStatus.update(if (result.phase == MockLocationStatus.Phase.STOPPED_WAITING)
            MockLocationStatus.Phase.STOPPING else result.phase,
            if (result.phase == MockLocationStatus.Phase.STOPPED_WAITING) "测试位置已解除，正在结束服务" else result.message)
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
        val owner = this
        main.postDelayed({
            if (active === owner) {
                val previous = terminal
                MockLocationStatus.update(if (previous?.phase == MockLocationStatus.Phase.CLEANUP_FAILED)
                    MockLocationStatus.Phase.CLEANUP_FAILED else MockLocationStatus.Phase.FAILED,
                    "${previous?.message ?: "注入已停止"}；服务退出超时，请重试停止并检查系统定位")
            }
        }, 15000)
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        ++commandId
        startPending = false
        finishStopped()
        super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        val error = release()
        val result = if (error != null) MockLocationStatus.Status(MockLocationStatus.Phase.CLEANUP_FAILED,
            listOfNotNull(terminal?.message, "退出时清理失败：$error").joinToString("；")) else terminal
            ?: MockLocationStatus.Status(MockLocationStatus.Phase.FAILED,
                "服务意外退出，已请求解除测试位置；等待独立端验证正常定位")
        if (active === this) {
            active = null
            // A newer start may be queued after stop cancelled an undelivered Intent.
            if (!startPending) MockLocationStatus.update(result.phase, result.message)
        }
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    private fun notification(text: String): Notification = Notification.Builder(this, CHANNEL_ID)
        .setContentTitle("Mock Location Lab").setContentText(text)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true).build()
    private fun startAsForeground() {
        val n = notification("准备验证固定地点")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIF_ID, n)
    }
    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification(text))
    }
    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "定位模拟", NotificationManager.IMPORTANCE_LOW))
    }
    companion object {
        private const val TAG = "MockLocationService"
        private const val CHANNEL_ID = "mock_location_channel"
        private const val NOTIF_ID = 1001
        private const val PREFS = "mock_location_state"
        private const val AUTH_REQUIRED = "请进入开发者选项，选择 Mock Location Lab 作为模拟位置信息应用。"
        private const val CLEANUP_REQUIRED = "无法确认测试位置是否释放；请重新选择本应用为模拟位置应用后点击停止重试"
        private val PROVIDERS = listOf(MockLocationEngine.PROVIDER_NAME, MockLocationEngine.NETWORK_PROVIDER)
        private val main = Handler(Looper.getMainLooper())
        private var active: MockLocationService? = null
        private var commandId = 0L
        private var startPending = false
        private const val EXTRA_REQUEST = "extra_request"
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        private fun cleanupKey(provider: String): String = when (provider) {
            MockLocationEngine.PROVIDER_NAME -> "cleanup_pending"
            MockLocationEngine.NETWORK_PROVIDER -> "network_cleanup_pending"
            else -> error("未知 provider：$provider")
        }
        private fun isCleanupPending(ctx: Context, provider: String): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(cleanupKey(provider), false)
        fun hasPendingCleanup(ctx: Context): Boolean = PROVIDERS.any { isCleanupPending(ctx, it) }
        private fun setCleanupPending(ctx: Context, provider: String, value: Boolean): Boolean =
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(cleanupKey(provider), value).commit()
        private fun cleanupPendingProviders(ctx: Context, lm: LocationManager): String? {
            val errors = mutableListOf<String>()
            for (provider in PROVIDERS) {
                if (!isCleanupPending(ctx, provider)) continue
                try {
                    check(MockLocationEngine.isMockAppSelected(ctx)) { CLEANUP_REQUIRED }
                    MockLocationEngine.removeTestProvider(lm, provider)
                    check(MockLocationEngine.isMockAppSelected(ctx)) { CLEANUP_REQUIRED }
                    check(setCleanupPending(ctx, provider, false)) { "无法保存清理结果" }
                    Log.i(TAG, "remove returned provider=$provider elapsed=${SystemClock.elapsedRealtimeNanos()}; normal fix unverified")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove test provider $provider", e)
                    errors.add("$provider：${e.javaClass.simpleName}: ${e.message ?: "无详细信息"}")
                }
            }
            return errors.takeIf { it.isNotEmpty() }?.joinToString("；")
        }
        private fun stoppedResult(error: String?) = if (error == null)
            MockLocationStatus.Status(MockLocationStatus.Phase.STOPPED_WAITING,
                "模拟已解除，等待正常定位（尚未由独立读取端确认）")
        else MockLocationStatus.Status(MockLocationStatus.Phase.CLEANUP_FAILED, "清理失败：$error")
        fun reportPendingCleanup(ctx: Context) {
            if (active == null && !startPending && hasPendingCleanup(ctx))
                MockLocationStatus.update(MockLocationStatus.Phase.CLEANUP_FAILED,
                    "存在待清理记录，运行状态未知；请点击停止重试清理，或重新开始")
        }
        fun start(ctx: Context, lat: Double, lng: Double) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (startPending || active != null) { Log.w(TAG, "Duplicate start ignored"); return }
            val id = ++commandId
            startPending = true
            MockLocationStatus.update(MockLocationStatus.Phase.PREPARING, "准备启动")
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, MockLocationService::class.java)
                    .putExtra(EXTRA_REQUEST, id).putExtra(EXTRA_LAT, lat).putExtra(EXTRA_LNG, lng))
                main.postDelayed({
                    if (commandId == id && startPending) {
                        startPending = false
                        ++commandId
                        active?.finishWithFailure("启动超时", IllegalStateException("未收到服务启动结果"))
                            ?: run {
                                ctx.applicationContext.stopService(Intent(ctx, MockLocationService::class.java))
                                MockLocationStatus.update(MockLocationStatus.Phase.FAILED, "启动超时：未收到服务启动结果")
                            }
                    }
                }, 15000)
            } catch (e: Exception) {
                startPending = false
                Log.e(TAG, "Cannot start foreground service request=$id", e)
                MockLocationStatus.update(MockLocationStatus.Phase.FAILED, "启动前台服务失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
        fun stop(ctx: Context) {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (active?.finishing == true) {
                if (MockLocationStatus.current.phase in setOf(MockLocationStatus.Phase.FAILED,
                        MockLocationStatus.Phase.CLEANUP_FAILED)) {
                    active?.let { it.finish(stoppedResult(it.release())) }
                }
                return
            }
            ++commandId
            startPending = false
            MockLocationStatus.update(MockLocationStatus.Phase.STOPPING, "停止中：停止注入并解除测试位置")
            active?.let { it.finishStopped(); return }
            try {
                ctx.stopService(Intent(ctx, MockLocationService::class.java))
                val error = cleanupPendingProviders(ctx, ctx.getSystemService(LocationManager::class.java))
                val result = stoppedResult(error)
                MockLocationStatus.update(result.phase, result.message)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot stop service", e)
                MockLocationStatus.update(MockLocationStatus.Phase.CLEANUP_FAILED,
                    "停止失败：${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }
}
