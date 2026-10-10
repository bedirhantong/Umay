package com.appvalence.umay.perception

import com.appvalence.umay.coremodel.ObstacleKind
import com.appvalence.umay.coremodel.ObstacleType

/**
 * The COCO classes EfficientDet-Lite0 knows that a walker can bump into, by
 * the model's 0-based class index (its labelmap.txt; checked against the
 * bundled model in ObstacleClassesTest), with a typical height used to judge
 * distance from how tall the object looks.
 *
 * Walls and closed doors aren't among them: the route keeps to the surveyed
 * corridors, and doors are tagged on the map.
 */
object ObstacleClasses {
    data class ObstacleClass(val kind: ObstacleKind, val label: String, val typicalHeightMeters: Double) {
        val type: ObstacleType get() = if (kind == ObstacleKind.PERSON) ObstacleType.PERSON else ObstacleType.STATIC_OBJECT
    }

    val byIndex: Map<Int, ObstacleClass> = mapOf(
        0 to ObstacleClass(ObstacleKind.PERSON, "person", 1.7),
        1 to ObstacleClass(ObstacleKind.BICYCLE, "bicycle", 1.0),
        3 to ObstacleClass(ObstacleKind.MOTORCYCLE, "motorcycle", 1.1),
        10 to ObstacleClass(ObstacleKind.FIRE_HYDRANT, "fire hydrant", 0.7),
        14 to ObstacleClass(ObstacleKind.BENCH, "bench", 0.8),
        17 to ObstacleClass(ObstacleKind.DOG, "dog", 0.5),
        32 to ObstacleClass(ObstacleKind.SUITCASE, "suitcase", 0.6),
        61 to ObstacleClass(ObstacleKind.CHAIR, "chair", 0.9),
        62 to ObstacleClass(ObstacleKind.COUCH, "couch", 0.85),
        63 to ObstacleClass(ObstacleKind.POTTED_PLANT, "potted plant", 0.8),
        64 to ObstacleClass(ObstacleKind.BED, "bed", 0.6),
        66 to ObstacleClass(ObstacleKind.TABLE, "dining table", 0.75),
        69 to ObstacleClass(ObstacleKind.TOILET, "toilet", 0.75),
        81 to ObstacleClass(ObstacleKind.REFRIGERATOR, "refrigerator", 1.8),
    )

    /** An object whose box reaches the bottom of the frame is cut off: it's closer than its height says. */
    const val CUT_OFF_DISTANCE_METERS = 1.2
    private const val BOTTOM_EDGE_FRACTION = 0.97

    /** Distance to an object of [obstacleClass] whose box spans [top]..[bottom] of a frame [imageHeight] high. */
    fun distanceMeters(obstacleClass: ObstacleClass, top: Float, bottom: Float, imageHeight: Int): Double {
        val estimate = ObstacleGeometry.estimateDistanceMeters(bottom - top, imageHeight, realHeightMeters = obstacleClass.typicalHeightMeters)
        return if (bottom >= imageHeight * BOTTOM_EDGE_FRACTION) minOf(estimate, CUT_OFF_DISTANCE_METERS) else estimate
    }
}
