package com.appvalence.umay.perception

import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.MarkerSighting
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * Produces a continuous stream of obstacle detections from the camera feed
 * (Faz 1: CameraX ImageAnalysis + TFLite). Must satisfy the NFR-1 320ms
 * end-to-end latency budget together with the orchestrator and feedback layers.
 */
interface ObstacleDetector {
    fun obstacleUpdates(): Flow<List<DetectedObstacle>>

    /** Place markers the same camera happens to see (see [QrMarkerReader]); none by default. */
    fun markerSightings(): Flow<MarkerSighting> = emptyFlow()
}
