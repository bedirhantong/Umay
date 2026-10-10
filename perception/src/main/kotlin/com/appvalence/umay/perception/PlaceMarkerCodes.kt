package com.appvalence.umay.perception

import com.appvalence.umay.coremodel.PlaceMarkers
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The QR code printed for a place: one module per matrix cell, no quiet zone (the sheet leaves white around it). */
object PlaceMarkerCodes {
    fun matrix(nodeId: String): BitMatrix = QRCodeWriter().encode(
        PlaceMarkers.contentFor(nodeId),
        BarcodeFormat.QR_CODE,
        0,
        0,
        // L keeps an id-sized marker at version 3 (see QrMarkerReader.MARKER_MODULES): the
        // fewer the modules, the farther away a 15 cm code can still be read.
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L, EncodeHintType.MARGIN to 0),
    )
}
