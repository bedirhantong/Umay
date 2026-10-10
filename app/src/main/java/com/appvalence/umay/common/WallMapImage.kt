package com.appvalence.umay.common

import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.appvalence.umay.coremodel.OccupancyRaster
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * A floor's wall map ready to draw: seen floor and walls as two separate layers,
 * so a map can put its own corridors between them. One pixel per cell; the top
 * row of each bitmap is the highest y, as on screen.
 */
class WallMapImage(
    val seenFloor: ImageBitmap,
    val walls: ImageBitmap,
    val originX: Double,
    val originY: Double,
    val widthMeters: Double,
    val heightMeters: Double,
)

fun OccupancyRaster.toWallMapImage(seenFloorColor: Color, wallColor: Color): WallMapImage? {
    val cells = decodeCells() ?: return null
    val floorArgb = seenFloorColor.toArgb()
    val wallArgb = wallColor.toArgb()
    val floorPixels = IntArray(width * height)
    val wallPixels = IntArray(width * height)
    for (row in 0 until height) {
        val screenRow = height - 1 - row
        for (column in 0 until width) {
            when (cells[row * width + column]) {
                OccupancyRaster.FREE -> floorPixels[screenRow * width + column] = floorArgb
                OccupancyRaster.OCCUPIED -> wallPixels[screenRow * width + column] = wallArgb
            }
        }
    }
    return WallMapImage(
        seenFloor = Bitmap.createBitmap(floorPixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap(),
        walls = Bitmap.createBitmap(wallPixels, width, height, Bitmap.Config.ARGB_8888).asImageBitmap(),
        originX = originX,
        originY = originY,
        widthMeters = width * cellSizeMeters,
        heightMeters = height * cellSizeMeters,
    )
}

/** Decoded off the main thread: a large floor is hundreds of thousands of cells. */
@Composable
fun rememberWallMapImage(raster: OccupancyRaster?, seenFloorColor: Color, wallColor: Color): State<WallMapImage?> =
    produceState<WallMapImage?>(initialValue = null, raster, seenFloorColor, wallColor) {
        value = raster?.let { withContext(Dispatchers.Default) { it.toWallMapImage(seenFloorColor, wallColor) } }
    }

/**
 * Draws one layer of [map] with its top-left corner at [topLeft] on screen and
 * [pixelsPerMeter] scale, unrotated — rotate the canvas around it if needed.
 * Nearest-neighbour, so cells stay crisp squares when zoomed in.
 */
fun DrawScope.drawWallLayer(layer: ImageBitmap, map: WallMapImage, topLeft: Offset, pixelsPerMeter: Float) {
    drawImage(
        image = layer,
        srcOffset = IntOffset.Zero,
        srcSize = IntSize(layer.width, layer.height),
        dstOffset = IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()),
        dstSize = IntSize(
            (map.widthMeters * pixelsPerMeter).roundToInt().coerceAtLeast(1),
            (map.heightMeters * pixelsPerMeter).roundToInt().coerceAtLeast(1),
        ),
        filterQuality = FilterQuality.None,
    )
}
