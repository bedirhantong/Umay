package com.appvalence.umay.positioning.replay

import com.appvalence.umay.coremodel.FieldLogEvent
import com.appvalence.umay.coremodel.FieldLogJson
import com.appvalence.umay.coremodel.GroundTruthEvent
import com.appvalence.umay.coremodel.PositionEvent
import com.appvalence.umay.coremodel.PositionStream
import com.appvalence.umay.coremodel.SessionStartEvent
import com.appvalence.umay.positioning.particle.corridorMap
import com.appvalence.umay.positioning.particle.node
import com.appvalence.umay.positioning.particle.stepsAlong
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldLogReplayTest {

    private val a = node("A", 0.0, 0.0)
    private val b = node("B", 0.0, 10.0)
    private val c = node("C", 10.0, 10.0)
    private val map = corridorMap(a, b, c)

    private fun sessionStart(stride: Double) = SessionStartEvent(
        t = 0L,
        appVersion = "test",
        device = "test",
        androidSdk = 36,
        buildingId = map.id,
        startNodeId = "A",
        destinationNodeId = "C",
        constants = mapOf("stepLengthMetersInUse" to stride),
        map = map,
    )

    /** A walk A→B→C logged the way NavigationSession logs it, with a "Buradayım" at C. */
    private fun loggedWalk(gyroOffsetDeg: Double): List<FieldLogEvent> {
        val steps = stepsAlong(listOf(a, b, c), gyroOffsetDeg = gyroOffsetDeg)
        var heading = 0.0
        val pdr = steps.map { step ->
            heading = ((heading + step.headingDeltaDeg) % 360.0 + 360.0) % 360.0
            PositionEvent(step.timestampMs, PositionStream.PDR, x = 0.0, y = 0.0, floor = 0, headingDeg = heading, accuracyMeters = 1.0, source = "PEDESTRIAN_DEAD_RECKONING")
        }
        val arrival = GroundTruthEvent.of(t = steps.last().timestampMs + 1, node = c, nodeFloor = 0, estimate = null)
        return listOf(sessionStart(0.7)) + pdr + arrival
    }

    @Test
    fun `scores both pipelines at every ground-truth mark`() {
        val result = FieldLogReplay.replay(loggedWalk(gyroOffsetDeg = 0.0))

        assertEquals(30, result.steps)
        assertEquals(1, result.comparisons.size)
        assertEquals("C", result.comparisons.single().nodeName)
        // Facing where the survey began, both pipelines walk the route correctly.
        assertTrue(result.comparisons.single().legacyErrorMeters!! < 1.0)
        assertTrue(result.comparisons.single().particleErrorMeters!! < 1.5)
    }

    @Test
    fun `an unknown starting heading sinks the old pipeline but not the particle filter`() {
        val result = FieldLogReplay.replay(loggedWalk(gyroOffsetDeg = 90.0))

        val comparison = result.comparisons.single()
        assertTrue(comparison.legacyErrorMeters!! > 5.0, "legacy error ${comparison.legacyErrorMeters}")
        assertTrue(comparison.particleErrorMeters!! < 1.5, "particle error ${comparison.particleErrorMeters}")
    }

    /**
     * Not a check: prints how both pipelines do on real walks. Point UMAY_FIELD_LOGS at a
     * folder of exported field logs (.jsonl) and run this test on its own.
     */
    @Test
    fun `report on real field logs`() {
        val dir = System.getenv("UMAY_FIELD_LOGS")?.let(::File) ?: return
        val logs = dir.listFiles { file -> file.extension == "jsonl" }.orEmpty().sortedBy { it.name }
        for (log in logs) {
            val events = log.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { FieldLogJson.decode(it) }.getOrNull() }
            if (events.none { it is SessionStartEvent }) continue
            val result = FieldLogReplay.replay(events)
            println("== ${log.name}: ${result.steps} steps, corrections legacy=${result.legacyCorrections} particle=${result.particleCorrections}")
            for (row in result.comparisons) {
                println("   %-24s live=%6s legacy=%6s particle=%6s".format(row.nodeName, row.liveErrorMeters.fmt(), row.legacyErrorMeters.fmt(), row.particleErrorMeters.fmt()))
            }
            println("   mean error: legacy=${result.legacyMeanError.fmt()} particle=${result.particleMeanError.fmt()}")
        }
        assertNotNull(dir)
    }

    private fun Double?.fmt() = this?.let { "%.2f".format(it) } ?: "-"
}
