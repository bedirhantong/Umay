package com.appvalence.umay.files

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import com.appvalence.umay.R
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.PlaceMarkers
import com.appvalence.umay.coremodel.isPlace
import com.appvalence.umay.perception.PlaceMarkerCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A printable sheet of place markers for a building: one A4 page per named
 * place, its QR code exactly [PlaceMarkers.PRINTED_SIDE_METERS] wide (distance
 * is judged from that size), its name large enough to find the right wall.
 * Written to the shareable exports folder.
 */
object MarkerSheets {
    // PDF units are points: 1/72 inch.
    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
    private const val POINTS_PER_METER = 72 / 0.0254

    suspend fun export(context: Context, map: BuildingMap): File? = withContext(Dispatchers.IO) {
        val places = map.floors.sortedBy { it.floorIndex }.flatMap { floor -> floor.nodes.filter { it.isPlace }.sortedBy { it.name }.map { floor.floorIndex to it } }
        if (places.isEmpty()) return@withContext null
        runCatching {
            val document = PdfDocument()
            val side = (PlaceMarkers.PRINTED_SIDE_METERS * POINTS_PER_METER).toFloat()
            val left = (PAGE_WIDTH - side) / 2
            val top = 110f
            val ink = Paint().apply { color = android.graphics.Color.BLACK; style = Paint.Style.FILL }
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 18f; textAlign = Paint.Align.CENTER }
            val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f; textAlign = Paint.Align.CENTER }
            places.forEachIndexed { index, (floorIndex, place) ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
                val canvas = page.canvas
                val matrix = PlaceMarkerCodes.matrix(place.id)
                val module = side / matrix.width
                for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
                    if (matrix[x, y]) canvas.drawRect(left + x * module, top + y * module, left + (x + 1) * module, top + (y + 1) * module, ink)
                }
                val textTop = top + side + 60
                canvas.drawText(place.name, PAGE_WIDTH / 2f, textTop, title)
                canvas.drawText(context.getString(R.string.markers_sheet_floor, map.id, context.getString(R.string.common_floor, floorIndex)), PAGE_WIDTH / 2f, textTop + 32, subtitle)
                canvas.drawText(context.getString(R.string.markers_sheet_print_note), PAGE_WIDTH / 2f, PAGE_HEIGHT - 70f, note)
                canvas.drawText(context.getString(R.string.markers_sheet_place_note), PAGE_WIDTH / 2f, PAGE_HEIGHT - 52f, note)
                document.finishPage(page)
            }
            val directory = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(directory, "${map.id.replace(Regex("[^\\p{L}\\p{N}_-]+"), "_")}-isaretler.pdf")
            file.outputStream().use(document::writeTo)
            document.close()
            file
        }.getOrNull()
    }
}
