package com.mocklocation.lab

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.baidu.mapapi.CoordType
import com.baidu.mapapi.SDKInitializer

/** Delayed SDK initialization and real SDK authorization results for the map screens. */
internal object BaiduSdkAccess {
    enum class State { WAITING, AUTHORIZED, FAILED }
    data class Status(val state: State, val message: String)

    private const val PREFS = "baidu_map_consent"
    private const val AGREED = "sdk_privacy_agreed"
    private val listeners = mutableSetOf<(Status) -> Unit>()
    private var initialized = false
    private var receiverRegistered = false
    private var status = Status(State.WAITING, "正在验证百度地图授权")

    fun hasKey(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        return !info.metaData?.getString("com.baidu.lbsapi.API_KEY").isNullOrBlank()
    }

    fun hasConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(AGREED, false)

    fun recordConsent(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(AGREED, true).commit()

    fun addListener(listener: (Status) -> Unit) {
        listeners.add(listener)
        listener(status)
    }

    fun removeListener(listener: (Status) -> Unit) {
        listeners.remove(listener)
    }

    fun initialize(context: Context) {
        val app = context.applicationContext
        check(hasKey(app)) { "此安装包未配置百度地图密钥" }
        check(hasConsent(app)) { "请先阅读并同意百度地图服务说明" }
        if (initialized) return
        if (!receiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_OK)
                addAction(SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_ERROR)
                addAction(SDKInitializer.SDK_BROADCAST_ACTION_STRING_NETWORK_ERROR)
            }
            ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
        }
        SDKInitializer.setAgreePrivacy(app, true)
        SDKInitializer.initialize(app)
        SDKInitializer.setCoordType(CoordType.GCJ02)
        initialized = true
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            status = when (intent.action) {
                SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_OK ->
                    Status(State.AUTHORIZED, "百度地图授权验证通过")
                SDKInitializer.SDK_BROADTCAST_ACTION_STRING_PERMISSION_CHECK_ERROR -> {
                    val code = intent.getIntExtra(SDKInitializer.SDK_BROADTCAST_INTENT_EXTRA_INFO_KEY_ERROR_CODE, -1)
                    Status(State.FAILED, "百度地图授权失败（错误码 ${code}），请检查应用密钥与安装包签名")
                }
                SDKInitializer.SDK_BROADCAST_ACTION_STRING_NETWORK_ERROR ->
                    Status(State.FAILED, "百度地图网络连接失败；恢复网络后请完全关闭本应用再打开，以重新验证地图服务")
                else -> return
            }
            listeners.toList().forEach { it(status) }
        }
    }
}
