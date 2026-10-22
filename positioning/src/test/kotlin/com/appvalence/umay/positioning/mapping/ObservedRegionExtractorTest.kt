package com.appvalence.umay.positioning.mapping

import com.appvalence.umay.coremodel.GeometryEvidence
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.OccupancyRaster
import com.appvalence.umay.coremodel.WallSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObservedRegionExtractorTest {
    @Test fun `unknown boundary remains explicitly incomplete`() {
        val raster = OccupancyRaster.of(0.5, 0.0, 0.0, 4, 4, ByteArray(16) { OccupancyRaster.FREE })
        val region = ObservedRegionExtractor.extract(raster, emptyList()).regions.single()
        assertEquals(4.0, region.areaSquareMeters, 1e-9)
        assertFalse(region.boundaryComplete)
        assertEquals(4, region.outline.size)
    }

    @Test fun `occupied boundary with supported wall segments can close observed region`() {
        val n = 6
        val cells = ByteArray(n * n) { i ->
            val x = i % n; val y = i / n
            if (x in 1..4 && y in 1..4) OccupancyRaster.FREE else OccupancyRaster.OCCUPIED
        }
        val wall = { a: MetricPoint, b: MetricPoint -> WallSegment(a, b, 0.2, 2.2, 30, 3, 0.02, GeometryEvidence.MULTI_FRAME) }
        val walls = listOf(
            wall(MetricPoint(0.5,0.5),MetricPoint(2.5,0.5)), wall(MetricPoint(2.5,0.5),MetricPoint(2.5,2.5)),
            wall(MetricPoint(2.5,2.5),MetricPoint(0.5,2.5)), wall(MetricPoint(0.5,2.5),MetricPoint(0.5,0.5)),
        )
        val region = ObservedRegionExtractor.extract(OccupancyRaster.of(0.5,0.0,0.0,n,n,cells), walls).regions.single()
        assertTrue(region.boundaryComplete)
        assertEquals(4.0, region.areaSquareMeters, 1e-9)
    }

    @Test fun `disconnected observed floor stays as separate polygons`() {
        val cells = byteArrayOf(
            1,1,0,1,1,
            1,1,0,1,1,
        )
        val result = ObservedRegionExtractor.extract(OccupancyRaster.of(1.0,0.0,0.0,5,2,cells), emptyList())
        assertEquals(2, result.regions.size)
        assertTrue(result.regions.all { !it.boundaryComplete })
    }

    @Test fun `unknown interior stays a hole and signed polygon area matches observed cells`() {
        val width = 6
        val cells = ByteArray(width * width) { i ->
            val x = i % width; val y = i / width
            if (x in 2..3 && y in 2..3) OccupancyRaster.UNKNOWN else OccupancyRaster.FREE
        }
        val result = ObservedRegionExtractor.extract(OccupancyRaster.of(0.25, 100.0, -200.0, width, width, cells), emptyList())
        val region = result.regions.single()
        assertFalse(result.truncated)
        assertEquals(1, region.holes.size)
        assertEquals(2.25, area(region.outline), 1e-9)
        assertEquals(-0.25, area(region.holes.single()), 1e-9)
        assertEquals(2.0, region.areaSquareMeters, 1e-9)
        assertEquals(region.areaSquareMeters, area(region.outline) + region.holes.sumOf(::area), 1e-9)
        assertFalse(region.boundaryComplete)
    }

    @Test fun `concave unknown notch is preserved in the outer boundary`() {
        val width = 5
        val cells = ByteArray(width * width) { i ->
            if (i % width >= 2 && i / width >= 2) OccupancyRaster.UNKNOWN else OccupancyRaster.FREE
        }
        val region = ObservedRegionExtractor.extract(OccupancyRaster.of(1.0, 0.0, 0.0, width, width, cells), emptyList()).regions.single()
        assertTrue(region.holes.isEmpty())
        assertEquals(6, region.outline.size)
        assertEquals(16.0, area(region.outline), 1e-9)
        assertEquals(16.0, region.areaSquareMeters, 1e-9)
    }

    @Test fun `diagonally touching unknown holes reject the pinched component`() {
        val width = 5
        val cells = ByteArray(width * width) { OccupancyRaster.FREE }
        cells[width + 1] = OccupancyRaster.UNKNOWN
        cells[2 * width + 2] = OccupancyRaster.UNKNOWN
        val result = ObservedRegionExtractor.extract(OccupancyRaster.of(1.0, 0.0, 0.0, width, width, cells), emptyList())
        // The two hole loops touch at one vertex. Dropping either loop would fill unknown space.
        assertTrue(result.regions.isEmpty())
    }

    @Test fun `more holes than the export budget rejects the entire component`() {
        val width = 19
        val cells = ByteArray(width * width) { OccupancyRaster.FREE }
        var holes = 0
        for (y in 1 until width - 1 step 2) for (x in 1 until width - 1 step 2) {
            if (holes < 65) { cells[y * width + x] = OccupancyRaster.UNKNOWN; holes++ }
        }
        val result = ObservedRegionExtractor.extract(OccupancyRaster.of(0.1, 0.0, 0.0, width, width, cells), emptyList())
        assertTrue(result.truncated)
        assertTrue(result.regions.isEmpty())
    }

    @Test fun `oversized jagged hole cannot disappear from an otherwise valid outer ring`() {
        val width = 1200
        val height = 6
        val cells = ByteArray(width * height) { i ->
            val x = i % width; val y = i / width
            if (x in 1 until width - 1 && (y == 2 || (y == 3 && x % 2 == 1))) OccupancyRaster.UNKNOWN else OccupancyRaster.FREE
        }
        val result = ObservedRegionExtractor.extract(OccupancyRaster.of(0.1, 0.0, 0.0, width, height, cells), emptyList())
        assertTrue(result.truncated)
        assertTrue(result.regions.isEmpty())
    }

    @Test fun `input budget is checked before decoding a large raster`() {
        val raster = OccupancyRaster(0.1, 0.0, 0.0, 1000, 1000, "")
        val result = ObservedRegionExtractor.extract(raster, emptyList())
        assertTrue(result.truncated)
        assertTrue(result.regions.isEmpty())
    }

    @Test fun `boundary budget omits the complete component instead of publishing a partial ring`() {
        val width = 20_000
        val cells = ByteArray(width * 4) { OccupancyRaster.FREE }
        val result = ObservedRegionExtractor.extract(OccupancyRaster.of(0.1, 0.0, 0.0, width, 4, cells), emptyList())
        assertTrue(result.truncated)
        assertTrue(result.regions.isEmpty())
    }

    private fun area(points: List<MetricPoint>): Double {
        val origin = points.first()
        return points.indices.sumOf { i ->
            val a = points[i]; val b = points[(i + 1) % points.size]
            (a.x - origin.x) * (b.y - origin.y) - (a.y - origin.y) * (b.x - origin.x)
        } / 2
    }
}
