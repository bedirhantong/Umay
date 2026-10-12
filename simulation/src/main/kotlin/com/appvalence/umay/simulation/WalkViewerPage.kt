package com.appvalence.umay.simulation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import kotlin.math.roundToLong

/**
 * The walk viewer (docs/YOL_HARITASI.md 0.11): one self-contained HTML file
 * that plays [WalkRecording]s on their floor plan — where the walker really
 * was, the app's estimate and particle cloud, the route, what was said, and
 * the position error over time. It makes no network requests at all (no
 * fonts, no libraries), so a real building's map never leaves the computer
 * it's opened on.
 */
object WalkViewerPage {
    private const val TEMPLATE = "walk-viewer.html"
    private const val DATA_PLACEHOLDER = "__UMAY_WALK_DATA__"
    private const val BODY_MARKER = "<!--BODY-->"

    /**
     * The page for [walks] (a picker switches between them). [standalone] makes a
     * whole document to open directly; without it, just the page's content, for a
     * host that wraps pages in its own document.
     */
    fun render(walks: List<WalkRecording>, standalone: Boolean = true): String {
        require(walks.isNotEmpty()) { "nothing to show" }
        val template = WalkViewerPage::class.java.getResource(TEMPLATE)?.readText()
            ?: error("$TEMPLATE is missing from the simulation module's resources")
        // A "</script>" inside a place name must not end the data block early.
        val data = toJson(walks).toString().replace("</", "<\\/")
        val (head, body) = template.replace(DATA_PLACEHOLDER, data).split(BODY_MARKER, limit = 2)
        if (!standalone) return head + body
        return buildString {
            appendLine("<!doctype html>")
            appendLine("<html lang=\"tr\">")
            appendLine("<head>")
            appendLine("<meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, viewport-fit=cover\">")
            append(head)
            appendLine("</head>")
            appendLine("<body>")
            append(body)
            appendLine("</body>")
            appendLine("</html>")
        }
    }

    /** Writes [render]'s page to [file], creating its folder. */
    fun write(file: File, walks: List<WalkRecording>, standalone: Boolean = true): File {
        file.parentFile?.mkdirs()
        file.writeText(render(walks, standalone))
        return file
    }

    private fun toJson(walks: List<WalkRecording>): JsonObject = buildJsonObject {
        putJsonArray("walks") { walks.forEach { add(walkJson(it)) } }
    }

    private fun walkJson(walk: WalkRecording): JsonObject = buildJsonObject {
        put("title", walk.title)
        put("subtitle", walk.subtitle)
        put("estimateLabel", walk.estimateLabel)
        put("secondLabel", walk.secondEstimateLabel)
        putJsonArray("summary") {
            walk.summary.forEach { item ->
                addJsonArray {
                    add(item.label)
                    add(item.value)
                    if (item.bad) add("bad")
                }
            }
        }
        putJsonArray("floors") {
            walk.map.floors.sortedBy { it.floorIndex }.forEach { floor ->
                addJsonObject {
                    put("index", floor.floorIndex)
                    putJsonArray("nodes") {
                        floor.nodes.forEach { node ->
                            addJsonObject {
                                put("id", node.id)
                                put("name", node.name)
                                put("x", round(node.x))
                                put("y", round(node.y))
                                put("type", node.type.name)
                            }
                        }
                    }
                    putJsonArray("edges") {
                        floor.edges.forEach { edge ->
                            addJsonArray {
                                add(edge.fromId)
                                add(edge.toId)
                            }
                        }
                    }
                    floor.occupancy?.let { raster ->
                        putJsonObject("walls") {
                            put("cell", raster.cellSizeMeters)
                            put("ox", round(raster.originX))
                            put("oy", round(raster.originY))
                            put("w", raster.width)
                            put("h", raster.height)
                            put("rle", raster.cells)
                        }
                    }
                }
            }
        }
        putJsonArray("beacons") {
            walk.beacons.forEach { beacon ->
                addJsonObject {
                    put("id", beacon.id)
                    put("floor", beacon.floor)
                    put("x", round(beacon.x))
                    put("y", round(beacon.y))
                }
            }
        }
        putJsonArray("markers") { walk.markerNodeIds.sorted().forEach { add(it) } }
        putJsonArray("frames") {
            walk.frames.forEach { frame ->
                addJsonObject {
                    put("t", frame.tMs)
                    put("f", frame.floor)
                    frame.truth?.let { point(this, "tr", it) }
                    frame.estimate?.let { point(this, "e", it) }
                    frame.secondEstimate?.let { point(this, "e2", it) }
                    frame.accuracyMeters?.let { put("a", round(it)) }
                    frame.headingDeg?.let { put("h", round(it, 10.0)) }
                    if (frame.particles.isNotEmpty()) {
                        putJsonArray("p") {
                            frame.particles.forEach { (x, y) ->
                                add(round(x, 10.0))
                                add(round(y, 10.0))
                            }
                        }
                    }
                    if (frame.speech.isNotEmpty()) putJsonArray("s") { frame.speech.forEach { add(it) } }
                    if (frame.notes.isNotEmpty()) putJsonArray("n") { frame.notes.forEach { add(it) } }
                    frame.route?.let { route -> putJsonArray("r") { route.forEach { add(it) } } }
                    frame.targetNodeId?.let { put("g", it) }
                }
            }
        }
    }

    private fun point(builder: kotlinx.serialization.json.JsonObjectBuilder, key: String, point: Point) {
        builder.putJsonArray(key) {
            add(round(point.x))
            add(round(point.y))
        }
    }

    // Centimetres are plenty for a floor plan and keep a long walk's page small.
    private fun round(value: Double, factor: Double = 100.0): Double = (value * factor).roundToLong() / factor
}
