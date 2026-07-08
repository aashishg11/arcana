package com.aashishgodambe.arcana.core.ai.pricing

import com.aashishgodambe.arcana.testFunko
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MockPriceModelTest {

    @Test
    fun `series spans the full window and is stable across calls`() {
        val item = testFunko(1, valueCents = 50_000)
        val a = MockPriceModel.weeklySeriesCents(item)
        val b = MockPriceModel.weeklySeriesCents(item)
        assertEquals(MockPriceModel.WEEKS, a.size)
        assertEquals("deterministic — same item yields the same series", a, b)
        assertEquals("current value is the newest point", a.last(), MockPriceModel.currentValueCents(item))
    }

    @Test
    fun `values drift week-to-week rather than sitting flat`() {
        val series = MockPriceModel.weeklySeriesCents(testFunko(2, valueCents = 50_000))
        assertTrue("a flat series has nothing for the summary to describe", series.toSet().size > 1)
    }

    @Test
    fun `current value differs from the import baseline so deltas exist`() {
        val baseline = 50_000
        val current = MockPriceModel.currentValueCents(testFunko(3, valueCents = baseline))
        assertTrue("mock must diverge from baseline", current != baseline)
    }

    @Test
    fun `distinct items get distinct trajectories`() {
        val one = MockPriceModel.weeklySeriesCents(testFunko(10, valueCents = 50_000))
        val two = MockPriceModel.weeklySeriesCents(testFunko(11, valueCents = 50_000))
        assertTrue("different localIds should not produce identical curves", one != two)
    }
}
