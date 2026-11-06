package com.mocklocation.lab

import org.junit.Assert.*
import org.junit.Test

class PlaceSearchCompletionGateTest {
    @Test fun onTimeResultIsDeliveredOnlyOnce() {
        val gate = PlaceSearchCompletionGate(100, 9000)
        assertEquals(PlaceSearchCompletionGate.Completion.ACCEPT, gate.complete(9099))
        assertEquals(PlaceSearchCompletionGate.Completion.IGNORE, gate.complete(9100))
    }
    @Test fun lateResultCannotWinWhenUiTimeoutRunnableHasNotRun() {
        val gate = PlaceSearchCompletionGate(100, 9000)
        assertEquals(PlaceSearchCompletionGate.Completion.TIMED_OUT, gate.complete(9100))
        assertEquals(PlaceSearchCompletionGate.Completion.IGNORE, gate.complete(9101))
    }
    @Test fun cancelBeforeCompletionDiscardsBackendResult() {
        val gate = PlaceSearchCompletionGate(100, 9000)
        gate.cancel()
        assertFalse(gate.isActive())
        assertEquals(PlaceSearchCompletionGate.Completion.IGNORE, gate.complete(200))
    }
    @Test fun cancelAfterBackendCompletionAlsoInvalidatesQueuedUiDelivery() {
        val gate = PlaceSearchCompletionGate(100, 9000)
        assertEquals(PlaceSearchCompletionGate.Completion.ACCEPT, gate.complete(200))
        gate.cancel()
        assertTrue(gate.isCancelled())
    }
}
