package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.FieldLogEvent
import com.appvalence.umay.coremodel.FieldLogJson
import com.appvalence.umay.coremodel.FloorChangeEvent
import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.coremodel.GroundTruthEvent
import com.appvalence.umay.coremodel.GuidanceEvent
import com.appvalence.umay.coremodel.PositionEvent
import com.appvalence.umay.coremodel.PositionStream
import com.appvalence.umay.coremodel.RssiEvent
import com.appvalence.umay.coremodel.SessionEndEvent
import com.appvalence.umay.coremodel.SessionStartEvent
import com.appvalence.umay.coremodel.StepEvent
import com.appvalence.umay.positioning.ble.BleBeaconScanner
import com.appvalence.umay.positioning.particle.GraphParticleFilter
import com.appvalence.umay.positioning.pdr.StepDetector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.hypot
import kotlin.random.Random

/**
 * A field log (a real walk, see [FieldLogEvent]) as the viewer plays it: the
 * walk is replayed through today's positioning filter, the same way
 * FieldLogReplay does, so its particle cloud can be drawn; what the app
 * estimated live during the walk is shown beside it, the tester's
 * "Buradayım" marks are the ground truth, and the spoken guidance is what the
 * tester heard.
 */
object FieldLogRecording {

    fun from(
        events: List<FieldLogEvent>,
        title: String,
        config: GraphParticleFilter.Config = GraphParticleFilter.Config(),
        seed: Int = 1,
    ): WalkRecording {
        val start = events.filterIsInstance<SessionStartEvent>().firstOrNull()
            ?: error("A field log always starts with session_start; this one has none")
        val ordered = events.sortedBy { it.t }
        val stepLength = start.constants["stepLengthMetersInUse"] ?: StepDetector.AVERAGE_STEP_LENGTH_METERS
        val filter = GraphParticleFilter(start.map, start.startNodeId, config, Random(seed))
        val nodesById = start.map.floors.flatMap { it.nodes }.associateBy { it.id }

        val frames = mutableListOf<WalkFrame>()
        val speech = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val sightings = mutableMapOf<String, RssiEvent>()
        var previousPdrHeading = 0.0
        var live: Point? = null
        var steps = 0
        val replayErrors = mutableListOf<Double>()
        val liveErrors = mutableListOf<Double>()

        fun frame(t: Long, truth: Point? = null) {
            val estimate = filter.estimate(t)
            frames += WalkFrame(
                tMs = t,
                floor = estimate.floor,
                truth = truth,
                estimate = Point(estimate.x, estimate.y),
                accuracyMeters = estimate.accuracyMeters,
                headingDeg = estimate.headingDeg,
                secondEstimate = live,
                particles = filter.particlePositions(PARTICLES_PER_FRAME),
                speech = speech.toList(),
                notes = notes.toList(),
            )
            speech.clear()
            notes.clear()
        }

        frame(start.t)
        for (event in ordered) {
            when (event) {
                is PositionEvent -> when (event.stream) {
                    PositionStream.PDR -> {
                        val delta = ((event.headingDeg - previousPdrHeading + 540.0) % 360.0) - 180.0
                        previousPdrHeading = event.headingDeg
                        filter.onStep(StepEvent(stepLength, delta, event.t))
                        steps++
                        frame(event.t)
                    }
                    PositionStream.FUSED -> live = Point(event.x, event.y)
                    PositionStream.FINGERPRINT -> Unit
                }

                is RssiEvent -> {
                    sightings[event.beaconId] = event
                    sightings.values.removeAll { event.t - it.t > BleBeaconScanner.STALE_THRESHOLD_MS }
                    filter.onBeaconReadings(sightings.values.map(::toReading), event.t)
                        ?.let { notes += "Parmak izi eşleşmesi: ${it.node.name}" }
                }

                is GuidanceEvent -> speech += event.message

                is FloorChangeEvent -> if (event.phase != FloorChangePhase.PROMPTED) {
                    filter.changeFloor(event.targetNodeId, event.t)
                    notes += "Kat değişti: ${nodesById[event.targetNodeId]?.name ?: event.targetNodeId}, kat ${event.targetFloor}"
                }

                is GroundTruthEvent -> {
                    val estimate = filter.estimate(event.t)
                    if (estimate.floor == event.floor) replayErrors += hypot(estimate.x - event.nodeX, estimate.y - event.nodeY)
                    event.errorMeters?.let(liveErrors::add)
                    notes += "Buradayım: ${event.nodeName}" + (event.errorMeters?.let { ", yürüyüşte hata %.1f m".format(it) } ?: "")
                    frame(event.t, truth = Point(event.nodeX, event.nodeY))
                }

                is SessionEndEvent -> notes += "Kayıt bitti (${event.reason})."

                else -> Unit
            }
        }
        if (speech.isNotEmpty() || notes.isNotEmpty()) frame(ordered.last().t)

        val startPlace = nodesById[start.startNodeId]?.name ?: start.startNodeId
        val destination = nodesById[start.destinationNodeId]?.name ?: start.destinationNodeId
        return WalkRecording(
            title = title,
            subtitle = "$startPlace → $destination · ${start.device}, Umay ${start.appVersion} · ${DATE.format(Date(start.t))}",
            map = start.map,
            frames = frames,
            summary = listOfNotNull(
                SummaryItem("Adım", steps.toString()),
                SummaryItem("Buradayım işareti", replayErrors.size.toString(), bad = replayErrors.isEmpty()),
                liveErrors.takeIf { it.isNotEmpty() }?.let { SummaryItem("Yürüyüşteki hata", "%.2f m".format(it.average())) },
                replayErrors.takeIf { it.isNotEmpty() }?.let { SummaryItem("Bugünkü kodla hata", "%.2f m".format(it.average())) },
            ),
            estimateLabel = "Bugünkü kodla tahmin",
            secondEstimateLabel = "Yürüyüş sırasındaki tahmin",
        )
    }

    /** Every field log (.jsonl) in [folder], one walk each, oldest name first; unreadable files are skipped. */
    fun fromFolder(folder: File): List<WalkRecording> =
        folder.listFiles { file -> file.extension == "jsonl" }.orEmpty().sortedBy { it.name }.mapNotNull { file ->
            val events = file.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { FieldLogJson.decode(it) }.getOrNull() }
            if (events.none { it is SessionStartEvent }) null else from(events, title = file.nameWithoutExtension)
        }

    private const val PARTICLES_PER_FRAME = 60
    private val DATE = SimpleDateFormat("d MMMM yyyy HH:mm", Locale.forLanguageTag("tr"))

    private fun toReading(event: RssiEvent) = BleBeaconReading(
        beaconId = event.beaconId,
        macAddress = "",
        deviceName = null,
        uuid = null,
        major = null,
        minor = null,
        rssi = event.rssi,
        txPowerAtOneMeter = null,
        estimatedDistanceMeters = 0.0,
        timestampMs = event.t,
    )
}
