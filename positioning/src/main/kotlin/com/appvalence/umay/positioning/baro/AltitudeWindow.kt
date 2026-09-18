package com.appvalence.umay.positioning.baro

/**
 * The last [windowMs] of barometric altitude readings, smoothed: the mean
 * altitude over the window, and a vertical-speed estimate from the older
 * half vs. the newer half (robust to one noisy reading, unlike comparing the
 * two most recent samples).
 */
internal class AltitudeWindow(private val windowMs: Long) {
    private val samples = ArrayDeque<Pair<Long, Double>>() // (timestampMs, altitude meters)

    fun add(hPa: Double, timestampMs: Long) {
        samples.addLast(timestampMs to PressureAltitude.metersFromHpa(hPa))
        while (samples.isNotEmpty() && timestampMs - samples.first().first > windowMs) samples.removeFirst()
    }

    fun smoothedMeters(): Double? = samples.takeIf { it.isNotEmpty() }?.map { it.second }?.average()

    fun verticalSpeedMps(): Double? {
        if (samples.size < 4) return null
        val midpoint = (samples.first().first + samples.last().first) / 2
        val older = samples.filter { it.first <= midpoint }
        val newer = samples.filter { it.first > midpoint }
        if (older.isEmpty() || newer.isEmpty()) return null
        val dtSeconds = (newer.map { it.first }.average() - older.map { it.first }.average()) / 1000.0
        if (dtSeconds <= 0) return null
        return (newer.map { it.second }.average() - older.map { it.second }.average()) / dtSeconds
    }
}
