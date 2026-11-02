package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable

/** Positive evidence: a measured camera ray passed through this location at this height. */
@Serializable
data class OpeningClearanceWitness(val point: MetricPoint, val height: Double, val frames: Int, val lastFrame: Long)

/** Geometrically supported opening, pending semantic/operator confirmation as a door. */
@Serializable
data class OpeningCandidate(
    val start: MetricPoint,
    val end: MetricPoint,
    val lintelHeightMeters: Double,
    val observedClearanceHeightMeters: Double,
    val independentFrames: Int,
    val evidence: GeometryEvidence = GeometryEvidence.MULTI_FRAME,
)

/** Boundary of actually observed floor cells. Unknown boundaries remain explicitly incomplete. */
@Serializable
data class ObservedRegion(
    val outline: List<MetricPoint>,
    val holes: List<List<MetricPoint>> = emptyList(),
    val areaSquareMeters: Double,
    val boundaryComplete: Boolean,
)
