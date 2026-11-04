package com.mocklocation.lab

/** Resolves the final cleanup attempt without claiming that a normal fix has been observed. */
internal object CleanupStatusResolver {
    fun resolve(
        previous: MockLocationStatus.Status?,
        error: String?,
        userStop: Boolean
    ): MockLocationStatus.Status = when {
        error != null -> MockLocationStatus.Status(MockLocationStatus.Phase.CLEANUP_FAILED,
            listOfNotNull(previous?.message, "退出时清理失败：$error").joinToString("；"))
        previous?.phase == MockLocationStatus.Phase.CLEANUP_FAILED -> MockLocationStatus.Status(
            if (userStop) MockLocationStatus.Phase.STOPPED_WAITING else MockLocationStatus.Phase.FAILED,
            "此前错误：${previous.message}；清理重试已返回成功，等待独立端确认正常定位")
        previous != null -> previous
        else -> MockLocationStatus.Status(MockLocationStatus.Phase.FAILED,
            "服务意外退出，已请求解除测试位置；等待独立端验证正常定位")
    }
}
