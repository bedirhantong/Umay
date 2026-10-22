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
            val time = pose.timestampNs?.div(1_000_000) ?: pose.timestampMs
            val before = previous.timestampNs?.div(1_000_000) ?: previous.timestampMs
            if (time <= before) return null
            val seconds = ((time-before)/1000.0).coerceAtMost(2.0)
            val displacement = hypot(hypot(pose.x-previous.x, pose.z-previous.z), pose.y-previous.y)
            if (displacement > 1.0 + 2.5*seconds) { alignmentRequired = true; return null }
        }
        sessionId = id
        last = pose
        return (transform ?: ArToSurvey(0.0, -pose.x, pose.z).also { transform = it })
    }
}
