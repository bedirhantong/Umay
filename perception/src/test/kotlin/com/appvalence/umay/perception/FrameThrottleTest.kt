package com.appvalence.umay.perception

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameThrottleTest {
    @Test
    fun `the first frame always goes through`() {
        assertTrue(FrameThrottle(minIntervalMs = 125).tryAcquire(nowMs = 0))
    }

    @Test
    fun `frames closer together than the interval are dropped`() {
        val throttle = FrameThrottle(minIntervalMs = 125)
        assertTrue(throttle.tryAcquire(1_000))
        assertFalse(throttle.tryAcquire(1_050))
        assertFalse(throttle.tryAcquire(1_124))
    }

    @Test
    fun `a frame exactly one interval later goes through, and the next interval counts from it`() {
        val throttle = FrameThrottle(minIntervalMs = 125)
        assertTrue(throttle.tryAcquire(1_000))
        assertTrue(throttle.tryAcquire(1_125))
        assertFalse(throttle.tryAcquire(1_200))
        assertTrue(throttle.tryAcquire(1_250))
    }

    @Test
    fun `dropped frames don't push the next accepted one further out`() {
        val throttle = FrameThrottle(minIntervalMs = 125)
        assertTrue(throttle.tryAcquire(0))
        repeat(10) { assertFalse(throttle.tryAcquire(10L * it + 10)) }
        assertTrue(throttle.tryAcquire(125))
    }

    @Test
    fun `after a reset the next frame goes through immediately`() {
        val throttle = FrameThrottle(minIntervalMs = 125)
        assertTrue(throttle.tryAcquire(1_000))
        throttle.reset()
        assertTrue(throttle.tryAcquire(1_001))
    }
}
