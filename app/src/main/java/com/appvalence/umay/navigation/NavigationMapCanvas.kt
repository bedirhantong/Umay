package com.appvalence.umay.navigation

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessible
import androidx.compose.material.icons.filled.DoorFront
import androidx.compose.material.icons.filled.Elevator
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PriorityHigh
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.appvalence.umay.coremodel.DetectedObstacle
import com.appvalence.umay.coremodel.FloorMap
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * The floor the user is on, drawn the way people already read indoor maps
 * (Google/Apple Maps indoor views, airport wayfinding apps) rather than as
 * a raw node-edge graph:
 *
 * - Walked connections become soft, wide corridors, not hairlines between dots.
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
 * There's no true north here: every floor's coordinates come from the
 * survey walk's own dead-reckoning origin, so heading-up is also the only
 * orientation that means anything to someone standing in the building.
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
    obstacles: List<DetectedObstacle>,
    description: String,
    modifier: Modifier = Modifier,
) {
    var followUser by remember { mutableStateOf(true) }
    val following = followUser && position != null && position.floor == floorMap.floorIndex

    val headingUnwrapper = remember { HeadingUnwrapper() }
    val animatedHeading by animateFloatAsState(
        headingUnwrapper.unwrap(position?.headingDeg?.toFloat() ?: 0f),
        tween(MAP_ANIMATION_MS),
        label = "heading",
    )
    val animatedX by animateFloatAsState(position?.x?.toFloat() ?: 0f, tween(MAP_ANIMATION_MS), label = "x")
    val animatedY by animateFloatAsState(position?.y?.toFloat() ?: 0f, tween(MAP_ANIMATION_MS), label = "y")

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
        obstacle = Color(0xFFD32F2F),
    )
    val icons = MapIcons(
        door = rememberVectorPainter(Icons.Filled.DoorFront),
        stairs = rememberVectorPainter(Icons.Filled.Stairs),
        elevator = rememberVectorPainter(Icons.Filled.Elevator),
        ramp = rememberVectorPainter(Icons.Filled.Accessible),
        destination = rememberVectorPainter(Icons.Filled.Flag),
        obstacle = rememberVectorPainter(Icons.Filled.PriorityHigh),
    )
    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD } }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(MAP_HEIGHT_DP.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.background),
    ) {
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = description }) {
            if (floorMap.nodes.isEmpty()) return@Canvas
            val livePosition = position?.takeIf { it.floor == floorMap.floorIndex }
            val camera = if (following && livePosition != null) {
                Camera.follow(this, animatedX.toDouble(), animatedY.toDouble(), animatedHeading.toDouble())
            } else {
                Camera.overview(this, floorMap.nodes, livePosition)
            }
            drawFloor(
                camera = camera,
                floorMap = floorMap,
                route = route,
                nextRouteIndex = nextRouteIndex,
                nodeFloor = nodeFloor,
                position = livePosition?.let {
                    if (following) it.copy(x = animatedX.toDouble(), y = animatedY.toDouble(), headingDeg = animatedHeading.toDouble()) else it
                },
                obstacles = obstacles,
                colors = colors,
                icons = icons,
                labelPaint = labelPaint,
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

        if (position != null) {
            FilledTonalIconButton(
                onClick = { followUser = !followUser },
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Icon(
                    imageVector = if (following) Icons.Filled.ZoomOutMap else Icons.Filled.MyLocation,
                    contentDescription = if (following) "Tüm katı göster" else "Konumuma odaklan",
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
    val obstacle: Color,
)

private data class MapIcons(
    val door: VectorPainter,
    val stairs: VectorPainter,
    val elevator: VectorPainter,
    val ramp: VectorPainter,
    val destination: VectorPainter,
    val obstacle: VectorPainter,
)

/**
 * World (meters, y = "forward" of the survey origin) to screen pixels: a
 * uniform [pixelsPerMeter] in both axes (so a square room stays square), a
 * rotation by [headingDeg] so that heading points up, and [anchor] as the
 * on-screen spot where world point ([centerX], [centerY]) lands.
 */
private class Camera(
    val centerX: Double,
    val centerY: Double,
    val headingDeg: Double,
    val pixelsPerMeter: Float,
    val anchor: Offset,
) {
    private val cosA = cos(Math.toRadians(headingDeg))
    private val sinA = sin(Math.toRadians(headingDeg))

    fun toScreen(x: Double, y: Double): Offset {
        val dx = x - centerX
        val dy = y - centerY
        // Rotating by +heading counter-clockwise maps the heading vector (sin h, cos h) onto (0, 1): straight up.
        val rx = dx * cosA - dy * sinA
        val ry = dx * sinA + dy * cosA
        return Offset(anchor.x + (rx * pixelsPerMeter).toFloat(), anchor.y - (ry * pixelsPerMeter).toFloat())
    }

    /** A world heading as seen on screen, in degrees clockwise from up. */
    fun screenHeading(worldHeadingDeg: Double): Double = worldHeadingDeg - headingDeg

    companion object {
        fun follow(scope: DrawScope, x: Double, y: Double, headingDeg: Double) = Camera(
            centerX = x,
            centerY = y,
            headingDeg = headingDeg,
            pixelsPerMeter = scope.size.minDimension / FOLLOW_VIEW_METERS,
            // Below center: what's ahead matters more than what's behind.
            anchor = Offset(scope.size.width / 2f, scope.size.height * FOLLOW_ANCHOR_FRACTION),
        )

        fun overview(scope: DrawScope, nodes: List<MapNode>, position: Position?): Camera {
            val xs = nodes.map { it.x } + listOfNotNull(position?.x)
            val ys = nodes.map { it.y } + listOfNotNull(position?.y)
            val spanX = (xs.max() - xs.min()).coerceAtLeast(MIN_SPAN_METERS)
            val spanY = (ys.max() - ys.min()).coerceAtLeast(MIN_SPAN_METERS)
            val padding = with(scope) { OVERVIEW_PADDING_DP.dp.toPx() }
            val scale = min((scope.size.width - 2 * padding) / spanX, (scope.size.height - 2 * padding) / spanY).toFloat()
            return Camera(
                centerX = (xs.max() + xs.min()) / 2,
                centerY = (ys.max() + ys.min()) / 2,
                headingDeg = 0.0,
                pixelsPerMeter = scale,
                anchor = scope.center,
            )
        }
    }
}

private fun DrawScope.drawFloor(
    camera: Camera,
    floorMap: FloorMap,
    route: List<MapNode>,
    nextRouteIndex: Int,
    nodeFloor: Map<String, Int>,
    position: Position?,
    obstacles: List<DetectedObstacle>,
    colors: MapColors,
    icons: MapIcons,
    labelPaint: Paint,
) {
    val nodesById = floorMap.nodes.associateBy { it.id }
    val floorIndex = floorMap.floorIndex
    fun onThisFloor(node: MapNode) = nodeFloor[node.id] == floorIndex

    // Corridors: a slightly darker edge under a lighter fill reads as a hallway with walls.
    val corridorWidth = (CORRIDOR_WIDTH_METERS * camera.pixelsPerMeter).coerceIn(10.dp.toPx(), 36.dp.toPx())
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
            node.type == NodeType.BLE_ANCHOR -> Unit // infrastructure, not a place anyone walks to
            else -> {
                drawCircle(colors.corridor, radius = 5.dp.toPx(), center = center)
                drawCircle(if (node.id in routeIds) colors.route else colors.place, radius = 3.5.dp.toPx(), center = center)
            }
        }
    }

    obstacles.forEach { obstacle ->
        val here = position ?: return@forEach
        val bearing = Math.toRadians(here.headingDeg + obstacle.bearingDeg)
        val center = camera.toScreen(here.x + obstacle.distanceMeters * sin(bearing), here.y + obstacle.distanceMeters * cos(bearing))
        drawCircle(colors.obstacle.copy(alpha = 0.25f), radius = 18.dp.toPx(), center = center)
        drawBadge(center, colors.obstacle, Color.White, icons.obstacle, 10.dp.toPx())
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
        .filter { it.type != NodeType.BLE_ANCHOR && it.name != it.id }
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

    drawScaleBar(camera.pixelsPerMeter, colors, labelPaint)
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

/** Bottom-left "|——| 5 m": the one thing that turns a picture into distances. */
private fun DrawScope.drawScaleBar(pixelsPerMeter: Float, colors: MapColors, paint: Paint) {
    val targetPx = 80.dp.toPx()
    val meters = SCALE_BAR_STEPS_METERS.lastOrNull { it * pixelsPerMeter <= targetPx } ?: SCALE_BAR_STEPS_METERS.first()
    val lengthPx = meters * pixelsPerMeter
    val margin = 14.dp.toPx()
    val y = size.height - margin
    val start = Offset(margin, y)
    val end = Offset(margin + lengthPx, y)
    val tick = 5.dp.toPx()
    val stroke = 2.dp.toPx()
    listOf(start to end, start to Offset(start.x, y - tick), end to Offset(end.x, y - tick)).forEach { (a, b) ->
        drawLine(colors.label, a, b, stroke, StrokeCap.Round)
    }
    paint.textSize = 11.dp.toPx()
    paint.color = colors.label.toArgb()
    val text = "${if (meters % 1f == 0f) meters.toInt().toString() else meters.toString()} m"
    drawContext.canvas.nativeCanvas.drawText(text, end.x + 6.dp.toPx(), y, paint)
}

private fun String.ellipsize(maxChars: Int) = if (length <= maxChars) this else take(maxChars - 1) + "…"

private const val MAP_HEIGHT_DP = 340
private const val MAP_ANIMATION_MS = 350
private const val FOLLOW_VIEW_METERS = 18f
private const val FOLLOW_ANCHOR_FRACTION = 0.62f
private const val OVERVIEW_PADDING_DP = 36
private const val MIN_SPAN_METERS = 4.0
private const val CORRIDOR_WIDTH_METERS = 1.8f
private const val CONE_HALF_ANGLE_DEG = 32.0
private const val MAX_LABEL_CHARS = 20
private val SCALE_BAR_STEPS_METERS = listOf(1f, 2f, 5f, 10f, 20f, 50f, 100f)
