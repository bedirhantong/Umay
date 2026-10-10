package com.appvalence.umay.perception

import com.appvalence.umay.coremodel.PlaceMarkers
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrMarkerReaderTest {

    private val nodeId = "7d0c7e0e-5f7b-4f0e-9c55-1b2a3c4d5e6f"
    private val width = 640
    private val height = 480

    /** A frame with a marker [sidePx] wide, its top-left at ([left], [top]), on a light grey wall. */
    private fun frame(content: String, sidePx: Int, left: Int, top: Int): ByteArray {
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0))
        assertEquals(QrMarkerReader.MARKER_MODULES, matrix.width, "the sheet's QR version")
        val pixels = ByteArray(width * height) { 200.toByte() }
        val module = sidePx.toDouble() / matrix.width
        // White quiet zone around it, as on the printed sheet.
        for (y in (top - 4 * module.toInt()) until (top + sidePx + 4 * module.toInt())) for (x in (left - 4 * module.toInt()) until (left + sidePx + 4 * module.toInt())) pixels[y * width + x] = 255.toByte()
        for (y in 0 until sidePx) for (x in 0 until sidePx) {
            if (matrix[(x / module).toInt(), (y / module).toInt()]) pixels[(top + y) * width + left + x] = 0
        }
        return pixels
    }

    @Test
    fun `a marker is read, and its distance judged from its size`() {
        val reader = QrMarkerReader(horizontalFovDegrees = 65.0)
        val sidePx = 116
        val sighting = reader.read(frame(PlaceMarkers.contentFor(nodeId), sidePx, left = 262, top = 182), width, height, timestampMs = 7).single()

        assertEquals(nodeId, sighting.nodeId)
        val focal = width / (2 * tan(Math.toRadians(32.5)))
        val expected = PlaceMarkers.PRINTED_SIDE_METERS * focal / sidePx
        assertTrue(kotlin.math.abs(sighting.distanceMeters - expected) < expected * 0.1, "${sighting.distanceMeters} vs $expected")
        assertTrue(kotlin.math.abs(sighting.bearingDeg) < 3.0, "centred: ${sighting.bearingDeg}")
    }

    @Test
    fun `a marker to the right is seen to the right`() {
        val sighting = QrMarkerReader().read(frame(PlaceMarkers.contentFor(nodeId), 116, left = 480, top = 182), width, height, 0).single()

        assertTrue(sighting.bearingDeg > 15.0, "${sighting.bearingDeg}")
    }

    @Test
    fun `other QR codes, and frames without one, give nothing`() {
        val reader = QrMarkerReader()

        assertTrue(reader.read(frame("https://example.com/some/other/code/123456789", 116, 262, 182), width, height, 0).isEmpty())
        assertTrue(reader.read(ByteArray(width * height) { 128.toByte() }, width, height, 0).isEmpty())
    }

    @Test
    fun `only Umay marker contents point to a place`() {
        assertEquals(nodeId, PlaceMarkers.nodeIdOf(PlaceMarkers.contentFor(nodeId)))
        assertEquals(null, PlaceMarkers.nodeIdOf("UMAY1:"))
        assertEquals(null, PlaceMarkers.nodeIdOf("hello"))
    }
}
