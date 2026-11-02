package com.appvalence.umay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.appvalence.umay.common.FloorPlanViewport
import com.appvalence.umay.common.drawSpatialMap
import com.appvalence.umay.common.floorPlanBounds
import com.appvalence.umay.common.rememberSpatialMapDrawing
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.GeometryEvidence
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.ObservedRegion
import com.appvalence.umay.coremodel.OpeningCandidate
import com.appvalence.umay.coremodel.SpatialMap
import com.appvalence.umay.coremodel.WallSegment
import com.appvalence.umay.navigation.NavigationMapCanvas
import com.appvalence.umay.survey.SurveyMapCanvas
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Test-only metric fixtures exercise rendering; no camera, BLE, or synthetic production observations. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 26)
class FloorPlanCanvasTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun geometryOnlyNavigationRendersWallsOpeningsAndObservedBoundaries() {
        val floor = FloorMap(0, nodes = emptyList(), edges = emptyList(), reconstruction = fixture())
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                NavigationMapCanvas(
                    floorMap = floor,
                    floorLabel = "Test floor",
                    route = emptyList(),
                    nextRouteIndex = 0,
                    nodeFloor = emptyMap(),
                    position = null,
                    description = "Geometry fixture",
                    modifier = Modifier.width(320.dp).testTag(MAP),
                )
            }
        }

        val image = compose.onNodeWithTag(MAP).captureToImage()
        val viewport = FloorPlanViewport.fit(floorPlanBounds(floor)!!, image.width.toDouble(), image.height.toDouble(), padding = 36.0)

        assertColorNear(image, viewport, MetricPoint(-1.4, 0.5), MULTI_FRAME_WALL)
        assertColorNear(image, viewport, MetricPoint(0.0, 0.5), OPENING)
        assertColorNear(image, viewport, MetricPoint(3.0, 0.0), COMPLETE_BOUNDARY)
    }

    @Test
    fun sharedGeometryLayerFollowsRotatedPannedAndZoomedViewport() {
        val actualViewport = AtomicReference<FloorPlanViewport>()
        compose.setContent {
            val drawing = rememberSpatialMapDrawing(fixture())
            Canvas(Modifier.size(320.dp).testTag(MAP)) {
                val initial = FloorPlanViewport(
                    center = MetricPoint(0.0, 0.0),
                    widthPixels = size.width.toDouble(),
                    heightPixels = size.height.toDouble(),
                    pixelsPerMeter = size.minDimension / 9.0,
                    panX = 19.0,
                    panY = -11.0,
                    headingDeg = 37.0,
                )
                val viewport = initial.transformed(
                    size.width * 0.4, size.height * 0.6, zoomFactor = 1.35, panDeltaX = 13.0, panDeltaY = -9.0,
                )
                actualViewport.set(viewport)
                drawRect(Color.White)
                drawSpatialMap(drawing, viewport)
            }
        }

        val image = compose.onNodeWithTag(MAP).captureToImage()
        val viewport = actualViewport.get()
        assertNotNull("Canvas must publish the viewport used for this frame", viewport)
        assertColorNear(image, viewport, MetricPoint(-1.4, 0.5), MULTI_FRAME_WALL)
        assertColorNear(image, viewport, MetricPoint(0.0, 0.5), OPENING)
    }

    @Test
    fun surveyRendersObservedRegionsWithoutTrajectoryRasterOrWalls() {
        val regionsOnly = fixture().copy(walls = emptyList(), openings = emptyList())
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SurveyMapCanvas(
                    trajectory = emptyList(), results = emptyList(), spatialMap = regionsOnly,
                    height = 180.dp, modifier = Modifier.width(320.dp).testTag(MAP),
                )
            }
        }

        assertContainsColor(compose.onNodeWithTag(MAP).captureToImage(), COMPLETE_BOUNDARY)
    }

    @Test
    fun surveyRendersOpeningsWithoutTrajectoryRasterOrWalls() {
        val openingsOnly = fixture().copy(walls = emptyList(), observedRegions = emptyList())
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme()) {
                SurveyMapCanvas(
                    trajectory = emptyList(), results = emptyList(), spatialMap = openingsOnly,
                    height = 180.dp, modifier = Modifier.width(320.dp).testTag(MAP),
                )
            }
        }

        assertContainsColor(compose.onNodeWithTag(MAP).captureToImage(), OPENING)
    }

    private fun assertColorNear(image: ImageBitmap, viewport: FloorPlanViewport, point: MetricPoint, expected: Color) {
        val (screenX, screenY) = viewport.toScreen(point)
        val x = screenX.roundToInt()
        val y = screenY.roundToInt()
        val pixels = image.toPixelMap()
        // Search across the short dash gaps and antialiased stroke edges, independent of device density.
        val radius = 24
        val found = (maxOf(0, y - radius)..minOf(image.height - 1, y + radius)).any { row ->
            (maxOf(0, x - radius)..minOf(image.width - 1, x + radius)).any { column ->
                pixels.matches(column, row, expected)
            }
        }
        assertTrue("Expected $expected near metric $point projected to ($screenX, $screenY)", found)
    }

    private fun assertContainsColor(image: ImageBitmap, expected: Color) {
        val pixels = image.toPixelMap()
        val count = (0 until image.height).sumOf { row ->
            (0 until image.width).count { column -> pixels.matches(column, row, expected) }
        }
        // A visible stroke must exist; text/empty-state wording and system locale do not affect this check.
        assertTrue("Expected a visible $expected geometry stroke, found $count pixels", count >= 8)
    }

    private fun PixelMap.matches(x: Int, y: Int, expected: Color): Boolean {
        val actual = this[x, y]
        return abs(actual.red - expected.red) <= 0.03f && abs(actual.green - expected.green) <= 0.03f &&
            abs(actual.blue - expected.blue) <= 0.03f && actual.alpha >= 0.95f
    }

    /** Explicitly synthetic geometry belongs only to this test source set. */
    private fun fixture() = SpatialMap(
        coordinateFrameId = "canvas-test-frame",
        walls = listOf(
            WallSegment(MetricPoint(-2.0, 0.5), MetricPoint(-0.6, 0.5), 0.3, 1.8, 20, 4, 0.01, GeometryEvidence.MULTI_FRAME),
            WallSegment(MetricPoint(0.6, 0.5), MetricPoint(2.0, 0.5), 0.3, 1.8, 20, 4, 0.01, GeometryEvidence.MULTI_FRAME),
        ),
        openings = listOf(OpeningCandidate(MetricPoint(-0.6, 0.5), MetricPoint(0.6, 0.5), 2.0, 1.6, 4)),
        observedRegions = listOf(ObservedRegion(
            outline = listOf(MetricPoint(-3.0, -3.0), MetricPoint(3.0, -3.0), MetricPoint(3.0, 3.0), MetricPoint(-3.0, 3.0)),
            areaSquareMeters = 36.0, boundaryComplete = true,
        )),
    )

    private companion object {
        const val MAP = "floor-plan-test-canvas"
        val MULTI_FRAME_WALL = Color(0xFFAD5700)
        val OPENING = Color(0xFF00838F)
        val COMPLETE_BOUNDARY = Color(0xFF1565C0)
    }
}
