package com.appvalence.umay.common

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.transform

/**
 * At most one value per [periodMs]: the first straight away, then the latest one
 * after each pause — never a stale one, and never the last one dropped. For
 * streams that change far faster than a screen needs to redraw (BLE sightings
 * arrive dozens of times a second).
 */
fun <T> Flow<T>.throttleLatest(periodMs: Long): Flow<T> = conflate().transform {
    emit(it)
    delay(periodMs)
}
