package com.appvalence.umay.common

/** Wall-clock time, injectable so anything time-dependent can be tested without waiting. */
fun interface Clock {
    fun nowMs(): Long
}
