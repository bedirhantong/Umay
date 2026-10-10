package com.appvalence.umay.perception

import com.appvalence.umay.coremodel.MarkerSighting
import com.appvalence.umay.coremodel.PlaceMarkers
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import com.google.zxing.qrcode.QRCodeReader
import kotlin.math.hypot
import kotlin.math.tan

/**
 * Finds Umay's place markers ([PlaceMarkers]) in a grayscale camera frame and
 * tells how far each one is, from how big it looks: the printed size is known,
 * so it's the same pinhole estimate as for people, using the distance between
 * the QR code's finder patterns. Other QR codes are ignored.
 *
 * Pure JVM (ZXing), so it's tested with generated images. Not thread-safe.
 */
class QrMarkerReader(private val horizontalFovDegrees: Double = ASSUMED_HORIZONTAL_FOV_DEGREES) {
    private val reader = GenericMultipleBarcodeReader(QRCodeReader())
    private val hints = mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true)

    /** [luminance]: one byte per pixel, row by row, [width] × [height]. */
    fun read(luminance: ByteArray, width: Int, height: Int, timestampMs: Long): List<MarkerSighting> {
        val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
        val results = try {
            reader.decodeMultiple(BinaryBitmap(HybridBinarizer(source)), hints)
        } catch (e: NotFoundException) {
            return emptyList()
        } catch (e: ReaderException) {
            return emptyList()
        }
        val focalPx = width / (2.0 * tan(Math.toRadians(horizontalFovDegrees / 2)))
        return results.mapNotNull { result ->
            val nodeId = PlaceMarkers.nodeIdOf(result.text ?: return@mapNotNull null) ?: return@mapNotNull null
            // Finder patterns: bottom-left, top-left, top-right. Their centres sit 3.5 modules
            // in from the code's edges, so the code's side is that span × N / (N − 7).
            val points = result.resultPoints?.takeIf { it.size >= 3 } ?: return@mapNotNull null
            val span = (hypot((points[0].x - points[1].x).toDouble(), (points[0].y - points[1].y).toDouble()) +
                hypot((points[2].x - points[1].x).toDouble(), (points[2].y - points[1].y).toDouble())) / 2
            if (span <= 0) return@mapNotNull null
            val sidePx = span * MARKER_MODULES / (MARKER_MODULES - 7)
            val centerX = (points[0].x + points[2].x) / 2.0
            MarkerSighting(
                nodeId = nodeId,
                distanceMeters = PlaceMarkers.PRINTED_SIDE_METERS * focalPx / sidePx,
                bearingDeg = Math.toDegrees(kotlin.math.atan((centerX - width / 2.0) / focalPx)),
                timestampMs = timestampMs,
            )
        }
    }

    companion object {
        // Phones' main cameras see about this wide; the estimate is a starting point, like ObstacleGeometry's.
        const val ASSUMED_HORIZONTAL_FOV_DEGREES = 65.0

        /**
         * Modules across an Umay marker: "UMAY1:" plus a 36-character id is 42 bytes, which
         * fits QR version 3 (29 modules) at error correction L — what the sheet prints.
         */
        const val MARKER_MODULES = 29
    }
}
