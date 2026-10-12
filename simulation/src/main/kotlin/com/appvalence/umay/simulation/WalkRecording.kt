package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.BuildingMap

/**
 * One walk as the viewer plays it back ([WalkViewerPage]): the map it happened
 * on and, step by step, where the walker really was (when known), where the
 * app thought they were, the particle cloud behind that guess, and what the
 * app said. Made by a simulation ([simulateWalk], [simulateGuidedWalk]) or
 * rebuilt from a field log ([FieldLogRecording]).
 */
data class WalkRecording(
    val title: String,
    val subtitle: String = "",
    val map: BuildingMap,
    val frames: List<WalkFrame>,
    /** Headline numbers shown above the map. */
    val summary: List<SummaryItem> = emptyList(),
    /** Where the beacons are, when known (a simulation knows; a survey doesn't). */
    val beacons: List<BeaconMark> = emptyList(),
    /** Places with a printed marker on the wall. */
    val markerNodeIds: Set<String> = emptySet(),
    /** Labels for [WalkFrame.estimate] and [WalkFrame.secondEstimate] in the legend. */
    val estimateLabel: String = "Tahmin",
    val secondEstimateLabel: String = "",
)

/** One headline number ("Varış", "Evet"); [bad] shows it as a problem. */
data class SummaryItem(val label: String, val value: String, val bad: Boolean = false)

data class BeaconMark(val id: String, val floor: Int, val x: Double, val y: Double)

data class Point(val x: Double, val y: Double)

data class WalkFrame(
    val tMs: Long,
    val floor: Int,
    /** Where the walker really was; null when unknown (a field log knows only at "Buradayım" marks). */
    val truth: Point? = null,
    val estimate: Point? = null,
    val accuracyMeters: Double? = null,
    val headingDeg: Double? = null,
    /** Another estimate to compare against, such as what the app showed live during a field test. */
    val secondEstimate: Point? = null,
    val particles: List<Pair<Double, Double>> = emptyList(),
    /** Said at this step, in order. */
    val speech: List<String> = emptyList(),
    /** Anything else worth a line: a fingerprint fix, a marker seen, what the walker decided. */
    val notes: List<String> = emptyList(),
    /** The route the app guides along, when it changed at this step (null: unchanged). */
    val route: List<String>? = null,
    /** The place the app is guiding toward at this step. */
    val targetNodeId: String? = null,
)
