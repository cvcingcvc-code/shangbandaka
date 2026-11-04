package com.mocklocation.lab

import org.junit.Assert.*
import org.junit.Test

class CleanupStatusResolverTest {
    @Test fun userStopWithSuccessfulCleanupRetryWaitsForNormalFix() {
        val firstAttempt = MockLocationStatus.Status(MockLocationStatus.Phase.CLEANUP_FAILED,
            "清理失败：gps 移除暂时失败")

        val result = CleanupStatusResolver.resolve(firstAttempt, null, userStop = true)

        assertEquals(MockLocationStatus.Phase.STOPPED_WAITING, result.phase)
        assertTrue(result.message.contains(firstAttempt.message))
        assertTrue(result.message.contains("清理重试已返回成功"))
        assertTrue(result.message.contains("等待独立端"))
    }

    @Test fun runtimeFailureIsNotConvertedToSuccessfulStopByCleanupRetry() {
        val firstAttempt = MockLocationStatus.Status(MockLocationStatus.Phase.CLEANUP_FAILED,
            "network 注入失败；清理失败：gps 移除暂时失败")

        val result = CleanupStatusResolver.resolve(firstAttempt, null, userStop = false)

        assertEquals(MockLocationStatus.Phase.FAILED, result.phase)
        assertTrue(result.message.contains("network 注入失败"))
        assertTrue(result.message.contains("清理重试已返回成功"))
    }

    @Test fun repeatedCleanupFailurePreservesOriginalFailureAndCurrentError() {
        val firstAttempt = MockLocationStatus.Status(MockLocationStatus.Phase.FAILED,
            "启动失败：network 注册失败")

        val result = CleanupStatusResolver.resolve(firstAttempt, "gps 移除被拒绝", userStop = false)

        assertEquals(MockLocationStatus.Phase.CLEANUP_FAILED, result.phase)
        assertTrue(result.message.contains(firstAttempt.message))
        assertTrue(result.message.contains("gps 移除被拒绝"))
    }

    @Test fun unexpectedExitWithoutTerminalStateRemainsFailure() {
        val result = CleanupStatusResolver.resolve(null, null, userStop = false)

        assertEquals(MockLocationStatus.Phase.FAILED, result.phase)
        assertTrue(result.message.contains("服务意外退出"))
        assertTrue(result.message.contains("等待独立端"))
    }

    @Test fun successfulCleanupDoesNotEraseAnExistingRuntimeFailure() {
        val failure = MockLocationStatus.Status(MockLocationStatus.Phase.FAILED,
            "验证超时，已停止注入")

        val result = CleanupStatusResolver.resolve(failure, null, userStop = false)

        assertEquals(MockLocationStatus.Phase.FAILED, result.phase)
        assertEquals(failure.message, result.message)
    }
}
