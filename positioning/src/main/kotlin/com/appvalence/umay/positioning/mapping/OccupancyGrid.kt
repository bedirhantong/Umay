package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.OccupancyRaster
import kotlin.math.abs
import kotlin.math.floor

/**
 * A growable top-down occupancy grid built from range measurements — the map
 * a robot vacuum keeps. Each cell holds the log-odds that something solid is
 * there: every ray that ends in a cell votes "occupied" for it and "free" for
 * every cell it crossed on the way. Votes accumulate, so a person walking past
 * once is outvoted by the many rays that later see the floor behind them,
 * while a wall seen from many angles only gets more certain.
 *
 * Storage is chunked, so the grid can grow in any direction without knowing
 * the building's size up front, and costs memory only where something was
 * seen. Not thread-safe.
 */
class OccupancyGrid(val cellSizeMeters: Double = DEFAULT_CELL_SIZE_METERS) {

    private val chunks = HashMap<Long, FloatArray>()

    /** True once any measurement has been added. */
    val isEmpty: Boolean get() = chunks.isEmpty()

    /**
     * A measurement from ([fromX], [fromY]) to ([toX], [toY]): every cell in between was
     * seen through, so is free; the end cell is occupied when [hit], free otherwise
     * (the ray ended on open floor).
     */
    fun addRay(fromX: Double, fromY: Double, toX: Double, toY: Double, hit: Boolean) {
        if (!fromX.isFinite() || !fromY.isFinite() || !toX.isFinite() || !toY.isFinite()) return
        var column = cellOf(fromX)
        var row = cellOf(fromY)
        val endColumn = cellOf(toX)
        val endRow = cellOf(toY)

        // Bresenham over the cells, stopping one short of the end cell.
        val columnSpan = abs(endColumn.toLong() - column)
        val rowSpan = abs(endRow.toLong() - row)
        if (columnSpan > MAX_RAY_CELLS || rowSpan > MAX_RAY_CELLS) return
        val dc = columnSpan.toInt()
        val dr = -rowSpan.toInt()
        val sc = if (column < endColumn) 1 else -1
        val sr = if (row < endRow) 1 else -1
        var error = dc + dr
        while (column != endColumn || row != endRow) {
            vote(column, row, FREE_VOTE)
            val doubled = 2 * error
            if (doubled >= dr) {
                error += dr
                column += sc
            }
            if (doubled <= dc) {
                error += dc
                row += sr
            }
        }
        vote(endColumn, endRow, if (hit) OCCUPIED_VOTE else FREE_VOTE)
    }

    fun logOdds(x: Double, y: Double): Float {
        val column = cellOf(x)
        val row = cellOf(y)
        return chunks[chunkKey(column shr CHUNK_SHIFT, row shr CHUNK_SHIFT)]?.get(indexInChunk(column, row)) ?: 0f
    }

    /** Everything seen so far, cropped to the cells that were; null if nothing was. */
    fun snapshot(): OccupancyRaster? {
        if (chunks.isEmpty()) return null
        var minColumn = Int.MAX_VALUE
        var minRow = Int.MAX_VALUE
        var maxColumn = Int.MIN_VALUE
        var maxRow = Int.MIN_VALUE
        forEachKnownCell { column, row, _ ->
            if (column < minColumn) minColumn = column
            if (column > maxColumn) maxColumn = column
            if (row < minRow) minRow = row
            if (row > maxRow) maxRow = row
        }
        if (minColumn > maxColumn) return null

        val width = maxColumn - minColumn + 1
        val height = maxRow - minRow + 1
        val cells = ByteArray(width * height)
        forEachKnownCell { column, row, value -> cells[(row - minRow) * width + (column - minColumn)] = value }
        return OccupancyRaster.of(
            cellSizeMeters = cellSizeMeters,
            originX = minColumn * cellSizeMeters,
            originY = minRow * cellSizeMeters,
            width = width,
            height = height,
            cells = cells,
        )
    }

    private inline fun forEachKnownCell(action: (column: Int, row: Int, value: Byte) -> Unit) {
        for ((key, chunk) in chunks) {
            val chunkColumn = (key shr 32).toInt()
            val chunkRow = key.toInt()
            for (i in chunk.indices) {
                val value = classify(chunk[i])
                if (value == OccupancyRaster.UNKNOWN) continue
                action((chunkColumn shl CHUNK_SHIFT) + (i and CHUNK_MASK), (chunkRow shl CHUNK_SHIFT) + (i shr CHUNK_SHIFT), value)
            }
        }
    }

    private fun vote(column: Int, row: Int, vote: Float) {
        val chunk = chunks.getOrPut(chunkKey(column shr CHUNK_SHIFT, row shr CHUNK_SHIFT)) { FloatArray(CHUNK_SIZE * CHUNK_SIZE) }
        val i = indexInChunk(column, row)
        chunk[i] = (chunk[i] + vote).coerceIn(-LOG_ODDS_LIMIT, LOG_ODDS_LIMIT)
    }

    private fun cellOf(meters: Double): Int = floor(meters / cellSizeMeters).toInt()

    private fun indexInChunk(column: Int, row: Int) = ((row and CHUNK_MASK) shl CHUNK_SHIFT) or (column and CHUNK_MASK)

    private fun chunkKey(chunkColumn: Int, chunkRow: Int): Long = (chunkColumn.toLong() shl 32) or (chunkRow.toLong() and 0xFFFFFFFFL)

    companion object {
        const val DEFAULT_CELL_SIZE_METERS = 0.1

        // A free vote is weaker than an occupied one: rays graze past thin walls and
        // door frames far more often than they hit them, and a wall must survive that.
        const val OCCUPIED_VOTE = 0.85f
        const val FREE_VOTE = -0.4f
        const val LOG_ODDS_LIMIT = 4f

        /** Needs about two hits more than misses to call a cell a wall (and the converse). */
        const val CLASSIFY_THRESHOLD = 1.2f

        private const val CHUNK_SHIFT = 6
        private const val CHUNK_SIZE = 1 shl CHUNK_SHIFT
        private const val CHUNK_MASK = CHUNK_SIZE - 1

        // A real ray is never longer than the depth range; anything longer is a corrupt
        // measurement (a pose kilometres away) and is dropped whole.
        private const val MAX_RAY_CELLS = 2_000

        fun classify(logOdds: Float): Byte = when {
            logOdds >= CLASSIFY_THRESHOLD -> OccupancyRaster.OCCUPIED
            logOdds <= -CLASSIFY_THRESHOLD -> OccupancyRaster.FREE
            else -> OccupancyRaster.UNKNOWN
        }
    }
}
