package com.mocklocation.lab

import org.junit.Assert.*
import org.junit.Test

class PlaceSearchQueryGateTest {
    @Test fun changingTextRejectsAResponseEvenBeforeItsCancellationIsObserved() {
        val gate = PlaceSearchQueryGate()
        val old = gate.capture("上海现代化工")
        assertFalse(gate.accepts(old, "上海人民广场"))
    }

    @Test fun editingBackToTheSameTextDoesNotReviveAnOldRequestOrListItem() {
        val gate = PlaceSearchQueryGate()
        val old = gate.capture("上海现代化工")
        gate.invalidate()
        gate.invalidate()
        assertFalse(gate.accepts(old, "上海现代化工"))
    }

    @Test fun aNewSearchForTheSameTextRejectsThePreviousCompletion() {
        val gate = PlaceSearchQueryGate()
        val first = gate.capture("上海人民广场")
        gate.invalidate()
        val second = gate.capture("上海人民广场")
        assertFalse(gate.accepts(first, "上海人民广场"))
        assertTrue(gate.accepts(second, "上海人民广场"))
    }

    @Test fun returningFromMapCanRebindTheCompletedListWithoutRevivingItsResponse() {
        val gate = PlaceSearchQueryGate()
        val response = gate.capture("上海人民广场")
        gate.invalidate()
        val reboundItem = gate.capture("上海人民广场")
        assertFalse(gate.accepts(response, "上海人民广场"))
        assertTrue(gate.accepts(reboundItem, "上海人民广场"))
        assertFalse(gate.accepts(reboundItem, "上海现代化工"))
    }
}
