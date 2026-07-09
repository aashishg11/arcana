package com.aashishgodambe.arcana.core.ai.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PercentilesTest {

    @Test
    fun `empty input is null`() {
        assertNull(percentile(emptyList(), 0.5))
    }

    @Test
    fun `single value returns itself for any fraction`() {
        assertEquals(42L, percentile(listOf(42L), 0.5))
        assertEquals(42L, percentile(listOf(42L), 0.95))
        assertEquals(42L, percentile(listOf(42L), 0.0))
    }

    @Test
    fun `p50 is the conventional median`() {
        // odd N → middle element
        assertEquals(20L, percentile(listOf(30L, 10L, 20L), 0.50))
        // even N → mean of the two middle values (type-7 interpolation)
        assertEquals(25L, percentile(listOf(40L, 10L, 20L, 30L), 0.50))
    }

    @Test
    fun `p0 and p100 are the min and max`() {
        val values = listOf(5L, 1L, 9L, 3L)
        assertEquals(1L, percentile(values, 0.0))
        assertEquals(9L, percentile(values, 1.0))
    }

    @Test
    fun `p95 interpolates between the top ranks and rounds to whole ms`() {
        // sorted [10,20,30,40]; rank = 0.95*3 = 2.85 → 30 + (40-30)*0.85 = 38.5 → 39
        assertEquals(39L, percentile(listOf(10L, 20L, 30L, 40L), 0.95))
    }

    @Test
    fun `input order does not matter`() {
        val ascending = listOf(1L, 2L, 3L, 4L, 5L)
        assertEquals(percentile(ascending, 0.5), percentile(ascending.reversed(), 0.5))
    }

    @Test
    fun `fraction outside 0-1 throws`() {
        assertThrows(IllegalArgumentException::class.java) { percentile(listOf(1L), 1.5) }
        assertThrows(IllegalArgumentException::class.java) { percentile(listOf(1L), -0.1) }
    }
}
