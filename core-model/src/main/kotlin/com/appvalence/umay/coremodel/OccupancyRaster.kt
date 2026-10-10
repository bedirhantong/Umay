@file:OptIn(ExperimentalEncodingApi::class)

package com.appvalence.umay.coremodel

import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * A floor's top-down wall map, measured with the camera during the survey:
 * each square cell is [UNKNOWN] (never seen), [FREE] (floor was seen there)
 * or [OCCUPIED] (something stood there between knee and head height — a
 * wall, a pillar, a counter). The robot-vacuum kind of map.
 *
 * Cell (column, row) covers x in [originX + column·cellSize, +cellSize) and
 * y in [originY + row·cellSize, +cellSize), in the floor's own coordinates
 * (the same ones as its [MapNode]s). Row 0 is the lowest y.
 *
 * [cells] is run-length encoded (value byte, then the run length as an
 * unsigned LEB128 varint) and Base64'd: a floor is mostly long runs of the
 * same value, and the map has to fit a shared-pool document.
 */
@Serializable
data class OccupancyRaster(
    val cellSizeMeters: Double,
    val originX: Double,
    val originY: Double,
    val width: Int,
    val height: Int,
    val cells: String,
) {
    /** One byte per cell, row by row; null when [cells] doesn't decode to exactly [width]·[height] known values. */
    fun decodeCells(): ByteArray? {
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_CELLS) return null
        val encoded = runCatching { base64.decode(cells) }.getOrNull() ?: return null
        val out = ByteArray(width * height)
        var written = 0
        var i = 0
        while (i < encoded.size) {
            val value = encoded[i++]
            if (value != UNKNOWN && value != FREE && value != OCCUPIED) return null
            var run = 0L
            var shift = 0
            while (true) {
                if (i >= encoded.size || shift > 28) return null
                val b = encoded[i++].toInt() and 0xFF
                run = run or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
            }
            if (run <= 0 || written + run > out.size) return null
            out.fill(value, written, written + run.toInt())
            written += run.toInt()
        }
        return if (written == out.size) out else null
    }

    /**
     * The same area at twice the cell size: a coarse cell is [OCCUPIED] if any of its
     * four was (a thin wall must not vanish), else [FREE] if any was, else [UNKNOWN].
     */
    fun downsampled(): OccupancyRaster? {
        val source = decodeCells() ?: return null
        val newWidth = (width + 1) / 2
        val newHeight = (height + 1) / 2
        val out = ByteArray(newWidth * newHeight)
        for (row in 0 until height) {
            for (column in 0 until width) {
                val value = source[row * width + column]
                val target = (row / 2) * newWidth + column / 2
                if (value > out[target]) out[target] = value // OCCUPIED > FREE > UNKNOWN
            }
        }
        return of(cellSizeMeters * 2, originX, originY, newWidth, newHeight, out)
    }

    companion object {
        const val UNKNOWN: Byte = 0
        const val FREE: Byte = 1
        const val OCCUPIED: Byte = 2

        /** Guards decoding of hostile files: 4 000 × 4 000 cells is 400 m square at 10 cm. */
        const val MAX_CELLS = 16_000_000L

        private val base64 = Base64.Default

        fun of(cellSizeMeters: Double, originX: Double, originY: Double, width: Int, height: Int, cells: ByteArray): OccupancyRaster {
            require(cells.size == width * height) { "expected ${width * height} cells, got ${cells.size}" }
            val out = java.io.ByteArrayOutputStream(cells.size / 8 + 16)
            var i = 0
            while (i < cells.size) {
                val value = cells[i]
                var run = 1
                while (i + run < cells.size && cells[i + run] == value) run++
                out.write(value.toInt())
                var remaining = run
                while (true) {
                    val low = remaining and 0x7F
                    remaining = remaining ushr 7
                    if (remaining == 0) {
                        out.write(low)
                        break
                    }
                    out.write(low or 0x80)
                }
                i += run
            }
            val encoded = base64.encode(out.toByteArray())
            return OccupancyRaster(cellSizeMeters, originX, originY, width, height, encoded)
        }
    }
}
