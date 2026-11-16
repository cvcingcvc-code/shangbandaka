package com.mocklocation.lab

import org.junit.Assert.*
import org.junit.Test

class MockSessionVerifierTest {
    private val second = 1_000_000_000L
    private val start = 100L * second
    private val lat = 31.2304
    private val lng = 121.4737

    private fun verifier() = MockSessionVerifier(setOf("gps", "network"), start, lat, lng)
    private fun sample(v: MockSessionVerifier, provider: String, at: Long, receivedAt: Long = at) =
        v.accept(provider, lat, lng, true, at, receivedAt)

    @Test fun oldSessionAndLateCachedDataCannotVerifyNewStart() {
        val v = verifier()
        assertNull(sample(v, "gps", start - second, start + second))
        assertNull(sample(v, "network", start, start + second))
        assertSame(MockSessionVerifier.Health.Waiting, v.health(start + 9 * second))
        assertTrue(v.health(start + 10 * second) is MockSessionVerifier.Health.Failure)

        val late = verifier()
        assertNotNull(sample(late, "gps", start + second, start + 11 * second))
        assertTrue(late.health(start + 11 * second) is MockSessionVerifier.Health.Failure)
    }

    @Test fun bothProvidersMustReturnFreshTargetBeforeRunning() {
        val v = verifier()
        assertNull(sample(v, "gps", start + second))
        assertSame(MockSessionVerifier.Health.Waiting, v.health(start + second))
        assertNull(sample(v, "network", start + 2 * second))
        assertSame(MockSessionVerifier.Health.Healthy, v.health(start + 2 * second))
    }

    @Test fun healthyWindowIsBasedOnEachSamplesTimeNotDeliveryTime() {
        val v = verifier()
        sample(v, "gps", start + second)
        sample(v, "network", start + second)
        assertSame(MockSessionVerifier.Health.Healthy, v.health(start + second))
        sample(v, "network", start + 10 * second)
        assertSame(MockSessionVerifier.Health.Healthy, v.health(start + 10 * second))
        val result = v.health(start + 11 * second) as MockSessionVerifier.Health.Failure
        assertTrue(result.reason.contains("gps"))
        assertTrue(result.reason.contains("运行中"))
    }

    @Test fun duplicateAndOutOfOrderCallbacksDoNotExtendHealth() {
        val v = verifier()
        sample(v, "gps", start + 2 * second)
        sample(v, "network", start + 2 * second)
        assertSame(MockSessionVerifier.Health.Healthy, v.health(start + 2 * second))
        sample(v, "gps", start + 2 * second, start + 9 * second)
        sample(v, "gps", start + second, start + 9 * second)
        sample(v, "network", start + 11 * second)
        assertTrue(v.health(start + 12 * second) is MockSessionVerifier.Health.Failure)
    }

    @Test fun freshNonMockImmediatelyFailsAndCannotBeRepairedByLaterCallbacks() {
        val v = verifier()
        val error = v.accept("gps", lat, lng, false, start + second, start + second)
        assertTrue(error!!.contains("非模拟"))
        assertEquals(error, sample(v, "gps", start + 2 * second))
        assertEquals(error, (v.health(start + 2 * second) as MockSessionVerifier.Health.Failure).reason)
    }

    @Test fun freshWrongTargetImmediatelyFails() {
        val v = verifier()
        val error = v.accept("network", lat + 0.01, lng, true, start + second, start + second)
        assertTrue(error!!.contains("地点不一致"))
        assertTrue(v.health(start + second) is MockSessionVerifier.Health.Failure)
    }

    @Test fun invalidOrFutureCoordinatesCannotVerify() {
        assertNotNull(verifier().accept("gps", Double.NaN, lng, true, start + second, start + second))
        assertNotNull(verifier().accept("gps", lat, lng, true, start + 2 * second, start + second))
    }

    @Test fun unrelatedProviderDoesNotVerifyOrPoisonRequiredProviders() {
        val v = verifier()
        assertNull(v.accept("fused", 0.0, 0.0, false, start + second, start + second))
        assertSame(MockSessionVerifier.Health.Waiting, v.health(start + second))
        sample(v, "gps", start + 2 * second)
        sample(v, "network", start + 2 * second)
        assertSame(MockSessionVerifier.Health.Healthy, v.health(start + 2 * second))
    }

    @Test fun staleNonMockFromBeforeStartIsIgnored() {
        val v = verifier()
        assertNull(v.accept("gps", 0.0, 0.0, false, start - second, start + second))
        sample(v, "gps", start + 2 * second)
        sample(v, "network", start + 2 * second)
        assertSame(MockSessionVerifier.Health.Healthy, v.health(start + 2 * second))
    }

    @Test fun repeatedFreshUpdatesKeepSameTargetHealthyForLongRun() {
        val v = verifier()
        for (i in 1..60) {
            val now = start + i * second
            sample(v, "gps", now)
            sample(v, "network", now)
            assertSame(MockSessionVerifier.Health.Healthy, v.health(now))
        }
    }

    @Test fun newCallbacksAfterDeadlineCannotRescueSessionBeforeNextHealthTick() {
        val initial = verifier()
        assertNotNull(sample(initial, "gps", start + 11 * second))
        assertTrue(initial.health(start + 11 * second) is MockSessionVerifier.Health.Failure)

        val running = verifier()
        sample(running, "gps", start + second)
        sample(running, "network", start + second)
        assertSame(MockSessionVerifier.Health.Healthy, running.health(start + second))
        assertNotNull(sample(running, "gps", start + 12 * second))
        assertNotNull(sample(running, "network", start + 12 * second))
        assertTrue(running.health(start + 12 * second) is MockSessionVerifier.Health.Failure)
    }
}
