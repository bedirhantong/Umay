package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.FieldLogEvent
import com.appvalence.umay.coremodel.FieldLogJson
import com.appvalence.umay.coremodel.GroundTruthEvent
import com.appvalence.umay.coremodel.GuidanceEvent
import com.appvalence.umay.coremodel.PositionEvent
import com.appvalence.umay.coremodel.PositionStream
import com.appvalence.umay.coremodel.SessionStartEvent
import java.io.File
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FieldLogRecordingTest {
    private val office = VirtualBuildings.openOffice()
    private val map = office.surveyedMap(Random(1), withRadio = false)

    /** Entrance → lobby → west aisle, logged the way NavigationSession logs it, with a "Buradayım" at the end. */
    private fun loggedWalk(): List<FieldLogEvent> {
        val events = mutableListOf<FieldLogEvent>(
            SessionStartEvent(
                t = 0L, appVersion = "test", device = "test", androidSdk = 36, buildingId = map.id,
                startNodeId = "giris", destinationNodeId = "a1", constants = mapOf("stepLengthMetersInUse" to 0.7), map = map,
            ),
            GuidanceEvent(t = 1L, alertId = "route-guide-1", priority = "INFO", message = "Lobi yönünde yaklaşık 4 metre ilerle, sonra sola dön."),
        )
        var t = 0L
        val waypoints = listOf("giris", "lobi", "a1").map(office.nodesById::getValue)
        for ((from, to) in waypoints.zipWithNext()) {
            // Headings in the gyro's own frame: here, rotated 30° from the map's.
            val heading = Math.toDegrees(atan2(to.x - from.x, to.y - from.y)) - 30.0
            repeat(ceil(hypot(to.x - from.x, to.y - from.y) / 0.7).toInt()) {
                t += 550
                events += PositionEvent(t, PositionStream.PDR, 0.0, 0.0, 0, heading, 1.0, "PEDESTRIAN_DEAD_RECKONING")
                events += PositionEvent(t, PositionStream.FUSED, from.x, from.y, 0, heading, 1.0, "MAP_MATCHED")
            }
        }
        val end = office.nodesById.getValue("a1")
        events += GroundTruthEvent(t + 1, "a1", "a1", 0, end.x, end.y, end.x + 0.5, end.y, 0, 0.5)
        return events
    }

    @Test
    fun `replays a field log into a walk the viewer can play`() {
        val walk = FieldLogRecording.from(loggedWalk(), title = "Deneme kaydı")

        assertTrue(walk.frames.size > 20, "${walk.frames.size} frames")
        val mark = walk.frames.single { it.truth != null }
        assertEquals(Point(6.5, 5.0), mark.truth)
        val estimate = assertNotNull(mark.estimate)
        assertTrue(hypot(estimate.x - 6.5, estimate.y - 5.0) < 2.0, "replayed estimate $estimate")
        assertTrue(walk.frames.any { "Lobi yönünde" in it.speech.joinToString() })
        assertTrue(walk.frames.any { it.secondEstimate != null })
        assertTrue("Deneme kaydı" in WalkViewerPage.render(listOf(walk)))
    }

    @Test
    fun `reads a folder of field logs`() {
        val folder = File("build/tmp/field-log-test").apply { deleteRecursively(); mkdirs() }
        File(folder, "kayit-1.jsonl").writeText(loggedWalk().joinToString("\n") { FieldLogJson.encode(it) })
        File(folder, "notlar.txt").writeText("not a log")

        val walks = FieldLogRecording.fromFolder(folder)

        assertEquals(listOf("kayit-1"), walks.map { it.title })
    }

    /**
     * Not a check: writes the viewer for real field logs. Point UMAY_FIELD_LOGS at a
     * folder of exported logs (.jsonl) and run this test on its own; the page lands in
     * simulation/build/reports/walks/saha-kayitlari.html.
     */
    @Test
    fun `viewer for real field logs`() {
        val folder = System.getenv("UMAY_FIELD_LOGS")?.let(::File) ?: return
        val walks = FieldLogRecording.fromFolder(folder)
        if (walks.isEmpty()) return
        val page = WalkViewerPage.write(File("build/reports/walks/saha-kayitlari.html"), walks)
        println("Field log viewer: ${page.absolutePath}")
    }
}
