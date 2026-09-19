package com.appvalence.umay.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class FlowsTest {
    @Test
    fun `a burst collapses to its first and last values`() = runTest {
        val emitted = flow {
            for (i in 1..50) {
                emit(i)
                delay(10)
            }
        }.throttleLatest(periodMs = 1_000).toList()

        assertEquals(listOf(1, 50), emitted)
    }

    @Test
    fun `values further apart than the period all get through`() = runTest {
        val emitted = flow {
            emit("a")
            delay(600)
            emit("b")
            delay(600)
            emit("c")
        }.throttleLatest(periodMs = 500).toList()

        assertEquals(listOf("a", "b", "c"), emitted)
    }

    @Test
    fun `the latest value within each period wins`() = runTest {
        val emitted = flow {
            emit(1)
            delay(100)
            emit(2)
            delay(100)
            emit(3)
            delay(1_000)
            emit(4)
        }.throttleLatest(periodMs = 500).toList()

        assertEquals(listOf(1, 3, 4), emitted)
    }
}
