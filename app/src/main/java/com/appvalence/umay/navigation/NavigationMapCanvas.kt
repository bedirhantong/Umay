package com.appvalence.umay.navigation

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Accessible
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Elevator
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Stairs
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import com.appvalence.umay.R
import com.appvalence.umay.common.WallMapImage
import com.appvalence.umay.common.drawWallLayer
import com.appvalence.umay.common.rememberWallMapImage
import com.appvalence.umay.common.FloorPlanViewport
import com.appvalence.umay.common.drawSpatialMap
import com.appvalence.umay.common.drawMetricScale
import com.appvalence.umay.common.floorPlanBounds
import com.appvalence.umay.common.rememberSpatialMapDrawing
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.MetricPoint
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.isPlace
import com.appvalence.umay.coremodel.isUnnamed
import kotlin.math.cos
import kotlin.math.sin

/**
 * The floor the user is on, drawn the way people already read indoor maps
 * (Google/Apple Maps indoor views, airport wayfinding apps) rather than as
 * a raw node-edge graph:
 *
 * - Legacy walked maps keep their corridor styling; reconstructed floors show measured geometry.
 * - The route is a thick line with a casing; the part already walked fades out.
 * - The user is the familiar blue dot, with a heading cone ("which way am I
 *   facing") and an accuracy halo that honestly grows as dead reckoning drifts.
 * - Doors, stairs, elevators and ramps get recognizable icons; the
 *   destination gets a pin; place names sit on readable pills, dropped
 *   rather than overlapped when space runs out.
 * - A scale bar says how far things actually are.
 *
 * Two camera modes: **follow** (default) keeps the user centered with their
 * heading pointing up, like a car's turn-by-turn view — the natural frame
 * for "go left" to match the screen; **overview** fits the whole floor, with
 * a uniform scale in both axes so corridor proportions stay true.
 *
 * Coordinates remain in the floor's local metric frame. Overview keeps its orientation;
 * follow mode rotates the displayed frame to the user's heading.
 *
 * A blind user never needs this (everything here is also spoken), so the
 * whole canvas is one TalkBack stop announcing [description], not a maze of
 * unlabeled shapes.
 */
@Composable
fun NavigationMapCanvas(
    floorMap: FloorMap,
    floorLabel: String,
    route: List<MapNode>,
    nextRouteIndex: Int,
    nodeFloor: Map<String, Int>,
    position: Position?,
    description: String,
    modifier: Modifier = Modifier,
) {
    val frameId = floorMap.reconstruction?.coordinateFrameId
    var followUser by remember(floorMap.floorIndex, frameId) { mutableStateOf(true) }
    var manualViewport by remember(floorMap.floorIndex, frameId) { mutableStateOf<FloorPlanViewport?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val livePosition = position?.takeIf { it.floor == floorMap.floorIndex && it.x.isFinite() && it.y.isFinite() }
    val following = followUser && livePosition != null

    val headingUnwrapper = remember(floorMap.floorIndex, frameId) { HeadingUnwrapper() }
    val animatedHeading by animateFloatAsState(
        headingUnwrapper.unwrap(livePosition?.headingDeg?.takeIf { it.isFinite() }?.toFloat() ?: 0f),
        tween(MAP_ANIMATION_MS),
        label = "heading",
    )
    val animatedX by animateFloatAsState(livePosition?.x?.toFloat() ?: 0f, tween(MAP_ANIMATION_MS), label = "x")
    val animatedY by animateFloatAsState(livePosition?.y?.toFloat() ?: 0f, tween(MAP_ANIMATION_MS), label = "y")

    val colors = MapColors(
        background = MaterialTheme.colorScheme.surfaceVariant,
        corridor = MaterialTheme.colorScheme.surface,
        corridorEdge = MaterialTheme.colorScheme.outlineVariant,
        route = MaterialTheme.colorScheme.primary,
        routeCasing = MaterialTheme.colorScheme.onPrimaryContainer,
        walked = MaterialTheme.colorScheme.outline,
        user = MaterialTheme.colorScheme.primary,
        onUser = MaterialTheme.colorScheme.onPrimary,
        destination = MaterialTheme.colorScheme.error,
        onDestination = MaterialTheme.colorScheme.onError,
        hazard = MaterialTheme.colorScheme.tertiary,
        onHazard = MaterialTheme.colorScheme.onTertiary,
        place = MaterialTheme.colorScheme.secondary,
        label = MaterialTheme.colorScheme.onSurface,
        labelBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
    )
    val icons = MapIcons(
        door = rememberVectorPainter(Icons.Filled.DoorFront),
        stairs = rememberVectorPainter(Icons.Filled.Stairs),
        elevator = rememberVectorPainter(Icons.Filled.Elevator),
        ramp = rememberVectorPainter(Icons.AutoMirrored.Filled.Accessible),
        destination = rememberVectorPainter(Icons.Filled.Flag),
    )
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }
    // Camera-surveyed floors carry a wall map; drawn under the route, like a robot vacuum's.
    val wallMap by rememberWallMapImage(
        raster = floorMap.occupancy,
        seenFloorColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
        wallColor = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val drawing = rememberSpatialMapDrawing(floorMap.reconstruction)
    val bounds = remember(floorMap) { floorPlanBounds(floorMap) }
    val visibleBounds = livePosition?.let { bounds?.including(MetricPoint(it.x, it.y))
        ?: com.appvalence.umay.common.FloorPlanBounds.of(listOf(MetricPoint(it.x, it.y))) } ?: bounds
    val viewport = if (following) {
        FloorPlanViewport(MetricPoint(animatedX.toDouble(), animatedY.toDouble()), canvasSize.width.toDouble(), canvasSize.height.toDouble(),
            (minOf(canvasSize.width, canvasSize.height) / FOLLOW_VIEW_METERS).toDouble().coerceAtLeast(0.1),
            panY = canvasSize.height * (FOLLOW_ANCHOR_FRACTION - 0.5), headingDeg = animatedHeading.toDouble())
    } else manualViewport?.copy(widthPixels = canvasSize.width.toDouble(), heightPixels = canvasSize.height.toDouble())
        ?: visibleBounds?.let { FloorPlanViewport.fit(it, canvasSize.width.toDouble(), canvasSize.height.toDouble(), padding = 36.0) }
    val currentViewport by rememberUpdatedState(viewport)
    val geometryLegend = if (floorMap.reconstruction != null) stringResource(R.string.map_geometry_legend) else ""

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.background),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().onSizeChanged { canvasSize = it }
            .semantics { contentDescription = listOf(description, geometryLegend).filter { it.isNotBlank() }.joinToString(". ") }
            .pointerInput(floorMap.floorIndex, frameId) {
                detectTransformGestures { centroid, delta, factor, _ ->
                    manualViewport = (manualViewport ?: currentViewport)?.transformed(
                        centroid.x.toDouble(), centroid.y.toDouble(), factor.toDouble(), delta.x.toDouble(), delta.y.toDouble())
                    followUser = false
                }
            }
        ) {
            val camera = Camera(viewport ?: return@Canvas)
            drawFloor(
                camera = camera,
                floorMap = floorMap,
                route = route,
                nextRouteIndex = nextRouteIndex,
                nodeFloor = nodeFloor,
                position = livePosition?.let {
                    if (following) it.copy(x = animatedX.toDouble(), y = animatedY.toDouble(), headingDeg = animatedHeading.toDouble()) else it
                },
                colors = colors,
                icons = icons,
                labelPaint = labelPaint,
                wallMap = wallMap,
                spatialDrawing = drawing,
            )
        }

        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
        ) {
            Text(
                text = floorLabel,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
            FilledTonalIconButton(
                onClick = { followUser = false; manualViewport = null },
            ) {
                Icon(Icons.Filled.ZoomOutMap, contentDescription = stringResource(R.string.map_fit))
            }
            if (livePosition != null) FilledTonalIconButton(
                onClick = { followUser = !following; manualViewport = null },
            ) {
                Icon(
                    imageVector = Icons.Filled.MyLocation,
                    contentDescription = stringResource(if (following) R.string.nav_map_show_all else R.string.nav_map_follow_me),
                )
            }
        }
    }
}

/**
 * Keeps the heading continuous (…, 350, 370, …) instead of wrapping at 360, so
 * animating from 350° to 10° turns the map 20° the short way instead of spinning
 * it 340° back around. Plain state on purpose: it's derived per composition from
 * the incoming heading, not something that should itself trigger recomposition.
 */
private class HeadingUnwrapper {
    private var unwrapped: Float? = null

    fun unwrap(headingDeg: Float): Float {
        val previous = unwrapped ?: return headingDeg.also { unwrapped = it }
        var delta = (headingDeg - previous) % 360f
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return (previous + delta).also { unwrapped = it }
    }
}

private data class MapColors(
    val background: Color,
    val corridor: Color,
    val corridorEdge: Color,
    val route: Color,
    val routeCasing: Color,
    val walked: Color,
    val user: Color,
    val onUser: Color,
    val destination: Color,
    val onDestination: Color,
    val hazard: Color,
    val onHazard: Color,
    val place: Color,
    val label: Color,
    val labelBackground: Color,
)

private data class MapIcons(
    val door: VectorPainter,
    val stairs: VectorPainter,
    val elevator: VectorPainter,
    val ramp: VectorPainter,
    val destination: VectorPainter,
)

/**
 * World (meters, y = "forward" of the survey origin) to screen pixels: a
 * uniform [pixelsPerMeter] in both axes (so a square room stays square), a
 * rotation by [headingDeg] so that heading points up, and [anchor] as the
 * on-screen spot where world point ([centerX], [centerY]) lands.
 */
private class Camera(val viewport: FloorPlanViewport) {
    val centerX get() = viewport.center.x
    val centerY get() = viewport.center.y
    val headingDeg get() = viewport.headingDeg
    val pixelsPerMeter get() = viewport.pixelsPerMeter.toFloat()
    val anchor get() = Offset(viewport.anchorX.toFloat(), viewport.anchorY.toFloat())

    fun toScreen(x: Double, y: Double): Offset {
        val (sx, sy) = viewport.toScreen(MetricPoint(x, y))
        return Offset(sx.toFloat(), sy.toFloat())
    }

    /** A world heading as seen on screen, in degrees clockwise from up. */
    fun screenHeading(worldHeadingDeg: Double): Double = worldHeadingDeg - headingDeg

}

private fun DrawScope.drawFloor(
    camera: Camera,
    floorMap: FloorMap,
    route: List<MapNode>,
    nextRouteIndex: Int,
    nodeFloor: Map<String, Int>,
    position: Position?,
    colors: MapColors,
    icons: MapIcons,
    labelPaint: Paint,
    wallMap: WallMapImage?,
    spatialDrawing: com.appvalence.umay.common.SpatialMapDrawing?,
) {
    val nodesById = floorMap.nodes.associateBy { it.id }
    wallMap?.let { drawWallMapLayer(camera, it, it.seenFloor) }
    val floorIndex = floorMap.floorIndex
    fun onThisFloor(node: MapNode) = nodeFloor[node.id] == floorIndex

    // Corridors: a slightly darker edge under a lighter fill reads as a hallway with walls.
    val corridorWidth = if (floorMap.reconstruction != null) 2.dp.toPx() else (CORRIDOR_WIDTH_METERS * camera.pixelsPerMeter).coerceIn(10.dp.toPx(), 36.dp.toPx())
    floorMap.edges.forEach { edge ->
        val from = nodesById[edge.fromId] ?: return@forEach
        val to = nodesById[edge.toId] ?: return@forEach
        drawLine(colors.corridorEdge, camera.toScreen(from.x, from.y), camera.toScreen(to.x, to.y), corridorWidth + 3.dp.toPx(), StrokeCap.Round)
    }
    floorMap.edges.forEach { edge ->
        val from = nodesById[edge.fromId] ?: return@forEach
        val to = nodesById[edge.toId] ?: return@forEach
        drawLine(colors.corridor, camera.toScreen(from.x, from.y), camera.toScreen(to.x, to.y), corridorWidth, StrokeCap.Round)
    }
    // Walls on top of the corridor fill: where they disagree, the measured wall is the truth.
    wallMap?.let { drawWallMapLayer(camera, it, it.walls) }

    drawSpatialMap(spatialDrawing, camera.viewport)

    // Doorways: a bar across the gap in the wall, so a door reads as a door, not a hole.
    floorMap.doors.forEach { door ->
        val node = nodesById[door.nodeId] ?: return@forEach
        val bearing = Math.toRadians(door.passageBearingDeg)
        val half = door.widthMeters / 2
        // The wall runs across the passage: (cos, -sin) of its bearing.
        val a = camera.toScreen(node.x - half * cos(bearing), node.y + half * sin(bearing))
        val b = camera.toScreen(node.x + half * cos(bearing), node.y - half * sin(bearing))
        drawLine(colors.hazard, a, b, 4.dp.toPx(), StrokeCap.Butt)
    }

    // Route, split at where the user is: walked part faded and thin, the rest bold.
    val walked = mutableListOf<Offset>()
    val ahead = mutableListOf<Offset>()
    val clampedNext = nextRouteIndex.coerceIn(0, route.size)
    route.take(clampedNext).filter(::onThisFloor).forEach { walked += camera.toScreen(it.x, it.y) }
    position?.let {
        val here = camera.toScreen(it.x, it.y)
        if (walked.isNotEmpty()) walked += here
        ahead += here
    }
    route.drop(clampedNext).takeWhile(::onThisFloor).forEach { ahead += camera.toScreen(it.x, it.y) }
    if (position == null) ahead.addAll(0, walked).also { walked.clear() }

    val routeWidth = 6.dp.toPx()
    polyline(walked)?.let { drawPath(it, colors.walked.copy(alpha = 0.6f), style = roundStroke(routeWidth * 0.6f)) }
    polyline(ahead)?.let {
        drawPath(it, colors.routeCasing, style = roundStroke(routeWidth + 3.dp.toPx()))
        drawPath(it, colors.route, style = roundStroke(routeWidth))
    }

    // Places: hazard icons and plain place dots first, labels last so they sit on top.
    val destination = route.lastOrNull()
    val nextNode = route.getOrNull(nextRouteIndex)
    val routeIds = route.map { it.id }.toSet()
    floorMap.nodes.forEach { node ->
        val center = camera.toScreen(node.x, node.y)
        when {
            node.id == destination?.id -> Unit // drawn as a pin below, on top of everything else
            iconFor(node.type, icons) != null -> drawBadge(center, colors.hazard, colors.onHazard, iconFor(node.type, icons)!!, 11.dp.toPx())
            !node.isPlace -> Unit // a beacon or a corridor bend: not a place anyone walks to
            else -> {
                drawCircle(colors.corridor, radius = 5.dp.toPx(), center = center)
                drawCircle(if (node.id in routeIds) colors.route else colors.place, radius = 3.5.dp.toPx(), center = center)
            }
        }
    }

    position?.let { drawUser(camera, it, colors) }

    if (destination != null && onThisFloor(destination)) {
        drawPin(camera.toScreen(destination.x, destination.y), colors.destination, colors.onDestination, icons.destination)
    }

    // Labels by priority — the destination and the next turn matter far more than a
    // side room — and never on top of one another: a label that would overlap one
    // already placed is dropped instead of making both unreadable.
    val placed = mutableListOf<Rect>()
    val labelled = floorMap.nodes
        .filter { it.isPlace && !it.isUnnamed }
        .sortedBy { node ->
            when (node.id) {
                destination?.id -> 0
                nextNode?.id -> 1
                in routeIds -> if (node.type == NodeType.WAYPOINT) 3 else 2
                else -> 4
            }
        }
    labelled.forEach { node ->
        val anchor = camera.toScreen(node.x, node.y)
        val lift = if (node.id == destination?.id) 40.dp.toPx() else 16.dp.toPx()
        drawLabel(node.name.ellipsize(MAX_LABEL_CHARS), Offset(anchor.x, anchor.y - lift), colors, labelPaint, placed, emphasized = node.id == destination?.id || node.id == nextNode?.id)
    }

    drawMetricScale(camera.viewport, labelPaint, colors.label)
}

private fun iconFor(type: NodeType, icons: MapIcons): VectorPainter? = when (type) {
    NodeType.DOOR -> icons.door
    NodeType.STAIRS -> icons.stairs
    NodeType.ELEVATOR -> icons.elevator
    NodeType.RAMP -> icons.ramp
    else -> null
}

private fun polyline(points: List<Offset>): Path? {
    if (points.size < 2) return null
    return Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
}

private fun roundStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** [layer] of [map], placed and rotated exactly like [Camera.toScreen] places world points. */
private fun DrawScope.drawWallMapLayer(camera: Camera, map: WallMapImage, layer: ImageBitmap) {
    val pixelsPerMeter = camera.pixelsPerMeter
    // Where the map's top-left corner (lowest x, highest y) lands before rotating to heading-up.
    val topLeft = Offset(
        camera.anchor.x + ((map.originX - camera.centerX) * pixelsPerMeter).toFloat(),
        camera.anchor.y - ((map.originY + map.heightMeters - camera.centerY) * pixelsPerMeter).toFloat(),
    )
    rotate(degrees = (-camera.headingDeg).toFloat(), pivot = camera.anchor) {
        drawWallLayer(layer, map, topLeft, pixelsPerMeter)
    }
}

private fun DrawScope.drawIcon(painter: VectorPainter, center: Offset, sizePx: Float, tint: Color) {
    translate(center.x - sizePx / 2, center.y - sizePx / 2) {
        with(painter) { draw(Size(sizePx, sizePx), colorFilter = ColorFilter.tint(tint)) }
    }
}

private fun DrawScope.drawBadge(center: Offset, background: Color, foreground: Color, icon: VectorPainter, radius: Float) {
    drawCircle(Color.White, radius = radius + 1.5.dp.toPx(), center = center)
    drawCircle(background, radius = radius, center = center)
    drawIcon(icon, center, radius * 1.3f, foreground)
}

private fun DrawScope.drawPin(tip: Offset, color: Color, onColor: Color, icon: VectorPainter) {
    val radius = 13.dp.toPx()
    val head = Offset(tip.x, tip.y - radius * 2.2f)
    val pin = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(head.x - radius * 0.75f, head.y + radius * 0.6f)
        lineTo(head.x + radius * 0.75f, head.y + radius * 0.6f)
        close()
    }
    drawCircle(Color.Black.copy(alpha = 0.18f), radius = 4.dp.toPx(), center = tip)
    drawPath(pin, color)
    drawCircle(Color.White, radius = radius + 2.dp.toPx(), center = head)
    drawCircle(color, radius = radius, center = head)
    drawIcon(icon, head, radius * 1.2f, onColor)
}

private fun DrawScope.drawUser(camera: Camera, position: Position, colors: MapColors) {
    val center = camera.toScreen(position.x, position.y)

    // Accuracy halo: how sure we are, drawn to scale — it visibly grows as dead reckoning
    // drifts and snaps back small on a fingerprint correction.
    val haloRadius = (position.accuracyMeters * camera.pixelsPerMeter).toFloat().coerceIn(14.dp.toPx(), size.minDimension)
    drawCircle(colors.user.copy(alpha = 0.12f), radius = haloRadius, center = center)
    drawCircle(colors.user.copy(alpha = 0.35f), radius = haloRadius, center = center, style = Stroke(1.dp.toPx()))

    // Heading cone: a soft wedge pointing the way the phone faces.
    val coneLength = 44.dp.toPx()
    val facing = Math.toRadians(camera.screenHeading(position.headingDeg))
    val halfAngle = Math.toRadians(CONE_HALF_ANGLE_DEG)
    fun conePoint(angle: Double) = Offset(center.x + (coneLength * sin(angle)).toFloat(), center.y - (coneLength * cos(angle)).toFloat())
    val cone = Path().apply {
        moveTo(center.x, center.y)
        lineTo(conePoint(facing - halfAngle).x, conePoint(facing - halfAngle).y)
        lineTo(conePoint(facing + halfAngle).x, conePoint(facing + halfAngle).y)
        close()
    }
    drawPath(cone, Brush.radialGradient(listOf(colors.user.copy(alpha = 0.55f), colors.user.copy(alpha = 0f)), center = center, radius = coneLength))

    drawCircle(Color.Black.copy(alpha = 0.15f), radius = 12.dp.toPx(), center = center + Offset(0f, 1.dp.toPx()))
    drawCircle(Color.White, radius = 11.dp.toPx(), center = center)
    drawCircle(colors.user, radius = 8.dp.toPx(), center = center)
}

private fun DrawScope.drawLabel(
    text: String,
    bottomCenter: Offset,
    colors: MapColors,
    paint: Paint,
    placed: MutableList<Rect>,
    emphasized: Boolean,
) {
    paint.textSize = (if (emphasized) 13 else 11).dp.toPx()
    paint.color = colors.label.toArgb()
    val textWidth = paint.measureText(text)
    val horizontalPadding = 8.dp.toPx()
    val height = paint.textSize + 8.dp.toPx()
    val rect = Rect(
        left = bottomCenter.x - textWidth / 2 - horizontalPadding,
        top = bottomCenter.y - height,
        right = bottomCenter.x + textWidth / 2 + horizontalPadding,
        bottom = bottomCenter.y,
    )
    if (rect.right < 0 || rect.left > size.width || rect.bottom < 0 || rect.top > size.height) return
    if (placed.any { it.overlaps(rect) }) return
    placed += rect

    drawRoundRect(colors.labelBackground, topLeft = rect.topLeft, size = rect.size, cornerRadius = CornerRadius(height / 2))
    drawContext.canvas.nativeCanvas.drawText(text, rect.left + horizontalPadding, rect.bottom - (height - paint.textSize) / 2 - paint.descent() / 2, paint)
}

private fun String.ellipsize(maxChars: Int) = if (length <= maxChars) this else take(maxChars - 1) + "…"

private const val MAP_HEIGHT_DP = 340
private const val MAP_ANIMATION_MS = 350
private const val FOLLOW_VIEW_METERS = 18f
private const val FOLLOW_ANCHOR_FRACTION = 0.62f
private const val CORRIDOR_WIDTH_METERS = 1.8f
private const val CONE_HALF_ANGLE_DEG = 32.0
private const val MAX_LABEL_CHARS = 20
