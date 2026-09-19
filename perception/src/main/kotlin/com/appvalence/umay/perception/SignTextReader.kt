package com.appvalence.umay.perception

import android.graphics.Bitmap

/**
 * Reads printed text (a room number, a sign, a poster) from a single camera
 * frame — an on-demand "what does this say" action, not a continuous
 * stream. Unlike [ObstacleDetector], this never binds its own camera: it
 * reads whatever frame it's given, so it reuses [CameraXObstacleDetector]'s
 * already-bound session instead of fighting it for the camera (CameraX
 * allows only one active binding per lifecycle at a time).
 */
interface SignTextReader {
    /** The text blocks found in [bitmap], in on-screen order. Empty if none — never throws. */
    suspend fun read(bitmap: Bitmap): List<String>
}
