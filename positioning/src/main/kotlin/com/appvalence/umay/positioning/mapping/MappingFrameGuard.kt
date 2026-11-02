package com.appvalence.umay.positioning.mapping

import kotlin.math.hypot

/** A rigid session frame is fixed once. An unverifiable restart/jump stops integration until a new survey. */
class MappingFrameGuard {
    var alignmentRequired = false
        private set
    private var sessionId: String? = null
    private var last: ArPose? = null
    private var transform: ArToSurvey? = null

    fun accept(id: String, pose: ArPose): ArToSurvey? {
        if (alignmentRequired || listOf(pose.x, pose.y, pose.z, pose.headingDeg).any { !it.isFinite() }) return null
        val previous = last
        if (sessionId != null && sessionId != id) { alignmentRequired = true; return null }
        if (previous != null) {
            // One clock for both poses: nanoseconds only when both have them.
            val bothNanos = pose.timestampNs != null && previous.timestampNs != null
            val time = if (bothNanos) pose.timestampNs!! / 1_000_000 else pose.timestampMs
            val before = if (bothNanos) previous.timestampNs!! / 1_000_000 else previous.timestampMs
            if (time <= before) return null
            // Poses are not delivered while tracking is lost, so a walker can be well away when the first one
            // comes back in the same session: allow for as long as ARCore keeps relocalising (not just 2 s).
            val seconds = ((time-before)/1000.0).coerceAtMost(MAX_GAP_SECONDS)
            val displacement = hypot(hypot(pose.x-previous.x, pose.z-previous.z), pose.y-previous.y)
            if (displacement > 1.0 + 2.5*seconds) { alignmentRequired = true; return null }
        }
        sessionId = id
        last = pose
        return (transform ?: ArToSurvey(0.0, -pose.x, pose.z).also { transform = it })
    }

    private companion object {
        const val MAX_GAP_SECONDS = 30.0
    }
}
