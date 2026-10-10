package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.OccupancyRaster

/**
 * Keeps a building's wall maps small enough to travel: a map file is shared
 * by message and through the shared pool, whose documents have a hard size
 * limit. While the encoded maps together exceed [maxEncodedChars], the
 * largest one is halved in resolution (walls kept, see
 * [OccupancyRaster.downsampled]). Past [MAX_CELL_SIZE_METERS] a floor is
 * dropped rather than shared as a blur — the route graph doesn't need it.
 */
object WallMapBudget {
    const val DEFAULT_MAX_ENCODED_CHARS = 350_000
    const val MAX_CELL_SIZE_METERS = 0.4

    fun fit(rasters: Map<Int, OccupancyRaster>, maxEncodedChars: Int = DEFAULT_MAX_ENCODED_CHARS): Map<Int, OccupancyRaster> {
        val fitted = rasters.toMutableMap()
        while (fitted.values.sumOf { it.cells.length } > maxEncodedChars) {
            val (floor, largest) = fitted.maxByOrNull { it.value.cells.length } ?: break
            val coarser = largest.downsampled()
            if (coarser == null || coarser.cellSizeMeters > MAX_CELL_SIZE_METERS + 1e-9) {
                fitted.remove(floor)
            } else {
                fitted[floor] = coarser
            }
        }
        return fitted
    }
}
