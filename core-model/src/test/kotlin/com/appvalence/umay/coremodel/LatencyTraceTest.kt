package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LatencyTraceTest {

    @Test
    fun `totalMs is null until feedback is emitted`() {
        val trace = LatencyTrace(sensingStartMs = 1_000L)
        assertEquals(null, trace.totalMs)
    }

    @Test
    fun `totalMs is the delta between sensing start and feedback emission`() {
        val trace = LatencyTrace(sensingStartMs = 1_000L, feedbackEmittedMs = 1_250L)
        assertEquals(250L, trace.totalMs)
    }

    @Test
    fun `exceedsBudget is false when within the 320ms default budget`() {
        val trace = LatencyTrace(sensingStartMs = 0L, feedbackEmittedMs = 320L)
        assertFalse(trace.exceedsBudget())
    }

    @Test
    fun `exceedsBudget is true when over the default budget`() {
        val trace = LatencyTrace(sensingStartMs = 0L, feedbackEmittedMs = 321L)
        assertTrue(trace.exceedsBudget())
    }

    @Test
    fun `exceedsBudget honors a custom budget`() {
        val trace = LatencyTrace(sensingStartMs = 0L, feedbackEmittedMs = 150L)
        assertTrue(trace.exceedsBudget(budgetMs = 100L))
    }
}
