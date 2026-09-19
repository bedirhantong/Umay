package com.appvalence.umay.perception

/**
 * Lets at most one frame through per [minIntervalMs]. Not thread-safe: call
 * from one thread (the camera analyzer's).
 */
class FrameThrottle(private val minIntervalMs: Long) {
    private var lastAcceptedAtMs: Long? = null

    /** True if a frame arriving at [nowMs] should be processed; the time is then recorded. */
    fun tryAcquire(nowMs: Long): Boolean {
        val last = lastAcceptedAtMs
        if (last != null && nowMs - last < minIntervalMs) return false
        lastAcceptedAtMs = nowMs
        return true
    }

    fun reset() {
        lastAcceptedAtMs = null
    }
}
