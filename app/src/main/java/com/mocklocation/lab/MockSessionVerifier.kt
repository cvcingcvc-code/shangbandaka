package com.mocklocation.lab

import kotlin.math.abs

/**
 * Verifies one fixed-target session using elapsed-realtime timestamps only.
 * The caller supplies the clock so cached samples, clock boundaries and gaps are testable.
 * This checks only the named Android providers, not any other application's display.
 */
class MockSessionVerifier(
    providers: Set<String>,
    private val startedElapsedNanos: Long,
    private val targetLat: Double,
    private val targetLng: Double,
    private val timeoutNanos: Long = 10_000_000_000L
) {
    sealed class Health {
        object Waiting : Health()
        object Healthy : Health()
        data class Failure(val reason: String) : Health()
    }

    private val requiredProviders = providers.toSet()
    private val lastSampleNanos = mutableMapOf<String, Long>()
    private var failure: String? = null
    private var wasHealthy = false

    init {
        require(requiredProviders.isNotEmpty()) { "至少需要一个验证入口" }
        require(startedElapsedNanos >= 0L && timeoutNanos > 0L) { "验证时钟参数无效" }
        require(targetLat.isFinite() && targetLat in -90.0..90.0 &&
            targetLng.isFinite() && targetLng in -180.0..180.0) { "目标坐标无效" }
    }

    /**
     * Returns an error for a fresh invalid result. Null also covers ignored old/replayed data;
     * callers must use [health], rather than treating null as a successful verification.
     */
    fun accept(
        provider: String,
        lat: Double,
        lng: Double,
        isMock: Boolean,
        locationElapsedNanos: Long,
        nowNanos: Long
    ): String? {
        failure?.let { return it }
        if (provider !in requiredProviders) return null
        if (nowNanos < startedElapsedNanos) return fail("验证时钟早于本次启动")
        // A late callback must not revive an expired session before the next scheduled tick.
        val beforeSample = health(nowNanos)
        if (beforeSample is Health.Failure) return beforeSample.reason
        // A callback from the previous session must not verify the current session.
        if (locationElapsedNanos <= startedElapsedNanos) return null
        if (locationElapsedNanos > nowNanos) return fail("$provider 返回了未来时间戳")
        if (nowNanos - locationElapsedNanos >= timeoutNanos) return null
        // Repeated delivery does not extend the health window.
        if (locationElapsedNanos <= (lastSampleNanos[provider] ?: Long.MIN_VALUE)) return null
        if (!isMock) return fail("$provider 返回了新的非模拟位置")
        if (!lat.isFinite() || !lng.isFinite() ||
            abs(lat - targetLat) >= COORDINATE_TOLERANCE ||
            abs(lng - targetLng) >= COORDINATE_TOLERANCE) {
            return fail("$provider 返回的位置与所选地点不一致")
        }
        lastSampleNanos[provider] = locationElapsedNanos
        return null
    }

    /** Initial verification and continued health both expire at a ten-second gap by default. */
    fun health(nowNanos: Long): Health {
        failure?.let { return Health.Failure(it) }
        if (nowNanos < startedElapsedNanos) return Health.Failure(fail("验证时钟早于本次启动"))
        val missing = requiredProviders.filter { it !in lastSampleNanos }
        if (missing.isNotEmpty()) {
            if (nowNanos - startedElapsedNanos >= timeoutNanos) {
                return Health.Failure(fail("未在限时内验证新模拟位置：${missing.joinToString()}"))
            }
            return Health.Waiting
        }
        val stale = requiredProviders.filter { nowNanos - lastSampleNanos.getValue(it) >= timeoutNanos }
        if (stale.isNotEmpty()) {
            val stage = if (wasHealthy) "运行中定位验证已超时" else "定位验证数据已过期"
            return Health.Failure(fail("$stage：${stale.joinToString()}"))
        }
        wasHealthy = true
        return Health.Healthy
    }

    private fun fail(reason: String): String {
        failure = reason
        return reason
    }

    private companion object {
        const val COORDINATE_TOLERANCE = 0.000001
    }
}
