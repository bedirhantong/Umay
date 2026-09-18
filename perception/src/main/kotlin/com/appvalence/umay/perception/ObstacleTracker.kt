package com.appvalence.umay.perception

import java.util.UUID
import kotlin.math.hypot

/**
 * Matches this frame's raw detections against the previous frame's tracked
 * obstacles by nearest bounding-box center, so a stable id and a relative
 * velocity (FR-7) can be attached to each one. Negative velocity means the
 * obstacle is approaching (distance shrinking over time).
 *
 * Not thread-safe; callers must serialize calls to [update] (e.g. run it on
 * a single analyzer executor, as CameraX's ImageAnalysis already does).
 */
class ObstacleTracker(
    private val matchRadiusPx: Float = DEFAULT_MATCH_RADIUS_PX,
) {
    data class RawDetection(
        val centerXPx: Float,
        val centerYPx: Float,
        val distanceMeters: Double,
        val bearingDeg: Double,
    )

    data class TrackedDetection(
        val id: String,
        val distanceMeters: Double,
        val bearingDeg: Double,
        val relativeVelocityMps: Double?,
    )

    private data class Track(
        val id: String,
        var centerXPx: Float,
        var centerYPx: Float,
        var distanceMeters: Double,
        var timestampMs: Long,
    )

    private val tracks = mutableListOf<Track>()

    fun update(detections: List<RawDetection>, timestampMs: Long): List<TrackedDetection> {
        val unmatchedTracks = tracks.toMutableList()
        val results = mutableListOf<TrackedDetection>()
        val newTracks = mutableListOf<Track>()

        for (detection in detections) {
            val match = unmatchedTracks.minByOrNull { track ->
                hypot((track.centerXPx - detection.centerXPx).toDouble(), (track.centerYPx - detection.centerYPx).toDouble())
            }?.takeIf { track ->
                hypot((track.centerXPx - detection.centerXPx).toDouble(), (track.centerYPx - detection.centerYPx).toDouble()) <= matchRadiusPx
            }

            val velocity = if (match != null) {
                val dtSeconds = (timestampMs - match.timestampMs) / 1000.0
                if (dtSeconds > 0) (detection.distanceMeters - match.distanceMeters) / dtSeconds else null
            } else {
                null
            }

            val id = match?.id ?: UUID.randomUUID().toString()
            if (match != null) unmatchedTracks.remove(match)

            results += TrackedDetection(
                id = id,
                distanceMeters = detection.distanceMeters,
                bearingDeg = detection.bearingDeg,
                relativeVelocityMps = velocity,
            )
            newTracks += Track(id, detection.centerXPx, detection.centerYPx, detection.distanceMeters, timestampMs)
        }

        tracks.clear()
        tracks += newTracks
        return results
    }

    companion object {
        const val DEFAULT_MATCH_RADIUS_PX = 150f
    }
}
