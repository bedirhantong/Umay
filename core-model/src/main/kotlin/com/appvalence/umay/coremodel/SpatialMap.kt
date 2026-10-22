package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable

/** Metric local frame: x = AR x, y = -AR z, height = AR y; no geographic projection. */
@Serializable
data class MetricPoint(val x: Double, val y: Double)

@Serializable
enum class GeometryEvidence { OBSERVED_CANDIDATE, MULTI_FRAME, OPERATOR_CONFIRMED }

/** A vertical surface candidate, not a semantic guarantee that this is an architectural wall. */
@Serializable
data class WallSegment(
    val start: MetricPoint,
    val end: MetricPoint,
    val minHeight: Double,
    val maxHeight: Double,
    val supportingVoxels: Int,
    val independentFrames: Int,
    val residualMeters: Double,
    val evidence: GeometryEvidence = GeometryEvidence.OBSERVED_CANDIDATE,
)

/** Bounded intermediate evidence permits re-fitting without retaining camera imagery. */
@Serializable
data class SurfaceVoxel(
    val x: Double, val y: Double, val height: Double,
    val frames: Int, val lastFrame: Long,
)

@Serializable
data class FloorSurface(val worldHeight: Double, val independentFrames: Int, val residualMeters: Double)

@Serializable
enum class ReconstructionIssue { FLOOR_UNRESOLVED, FRAME_ALIGNMENT_REQUIRED, EVIDENCE_LIMIT_REACHED, PARTIAL_COVERAGE, CONFLICTING_SURFACES }

@Serializable
data class MetricFrameTransform(val clockwiseYawDeg: Double, val translationX: Double, val translationY: Double)

@Serializable
data class SpatialMap(
    val schemaVersion: Int = 1,
    val coordinateFrameId: String,
    val worldToFloor: MetricFrameTransform? = null,
    val floor: FloorSurface? = null,
    val walls: List<WallSegment> = emptyList(),
    val corners: List<MetricPoint> = emptyList(),
    val openings: List<OpeningCandidate> = emptyList(),
    val openingWitnesses: List<OpeningClearanceWitness> = emptyList(),
    val observedRegions: List<ObservedRegion> = emptyList(),
    val rejectedWalls: List<WallSegment> = emptyList(),
    val confirmedWalls: List<WallSegment> = emptyList(),
    val voxels: List<SurfaceVoxel> = emptyList(),
    val integratedFrames: Long = 0,
    val issues: Set<ReconstructionIssue> = setOf(ReconstructionIssue.PARTIAL_COVERAGE),
) {
    /** A partial measured floor boundary is useful mapping progress even without waypoints or walls. */
    val hasGeometry: Boolean get() = walls.isNotEmpty() || openings.isNotEmpty() || observedRegions.isNotEmpty()
}
