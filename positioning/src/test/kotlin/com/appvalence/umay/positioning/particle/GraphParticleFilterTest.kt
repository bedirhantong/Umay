package com.appvalence.umay.positioning.particle

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.FloorTransition
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.positioning.pdr.PdrIntegrator
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GraphParticleFilterTest {

    private val a = node("A", 0.0, 0.0)
    private val b = node("B", 0.0, 10.0)
    private val c = node("C", 10.0, 10.0)

    private fun reading(beaconId: String, rssi: Int, t: Long = 0L) = BleBeaconReading(
        beaconId = beaconId, macAddress = "", deviceName = null, uuid = null, major = null, minor = null,
        rssi = rssi, txPowerAtOneMeter = null, estimatedDistanceMeters = 0.0, timestampMs = t,
    )

    @Test
    fun `finds the walker's heading on its own, whichever way they faced at the start`() {
        // The old pipeline assumed the walker faced exactly where the survey began; here
        // they faced 73° away from it, which sends plain dead reckoning off the map.
        val steps = stepsAlong(listOf(a, b, c), gyroOffsetDeg = 73.0)
        val filter = GraphParticleFilter(corridorMap(a, b, c), "A", random = Random(42))
        val pdr = PdrIntegrator(startX = a.x, startY = a.y)

        val particleEnd = steps.map(filter::onStep).last()
        val pdrEnd = steps.map(pdr::integrate).last()

        assertTrue(hypot(particleEnd.x - c.x, particleEnd.y - c.y) < 1.5, "particle filter ended at $particleEnd")
        assertTrue(hypot(pdrEnd.x - c.x, pdrEnd.y - c.y) > 5.0, "plain PDR unexpectedly ended near C: $pdrEnd")
        assertEquals(PositionSource.MAP_MATCHED, particleEnd.source)
    }

    @Test
    fun `reports the heading in the map frame once it has learned it`() {
        val steps = stepsAlong(listOf(a, b), gyroOffsetDeg = 120.0)
        val filter = GraphParticleFilter(corridorMap(a, b), "A", random = Random(7))

        val end = steps.map(filter::onStep).last()

        // Walking A→B is due north in the map, whatever the gyro said.
        val error = ((end.headingDeg + 540.0) % 360.0) - 180.0
        assertTrue(kotlin.math.abs(error) < 15.0, "heading was ${end.headingDeg}")
    }

    @Test
    fun `stays on a long corridor while the gyro drifts`() {
        // 0.4° of gyro bias per step adds up to 23° by the end of 40 m.
        val far = node("Z", 0.0, 40.0)
        val steps = stepsAlong(listOf(a, far), driftPerStepDeg = 0.4)
        val filter = GraphParticleFilter(corridorMap(a, far), "A", random = Random(3))
        val pdr = PdrIntegrator(startX = a.x, startY = a.y)

        val end = steps.map(filter::onStep).last()
        val pdrEnd = steps.map(pdr::integrate).last()

        assertTrue(pdrEnd.x > 5.0, "plain PDR unexpectedly stayed in the corridor: $pdrEnd")
        assertTrue(kotlin.math.abs(end.x) < 0.5, "drifted sideways to $end")
        // Without a fingerprint, how far along is only as good as the stride.
        assertTrue(hypot(end.x - far.x, end.y - far.y) < 3.0, "ended at $end")
    }

    @Test
    fun `waits at a corner until the walker actually turns`() {
        // Strides here are 10% longer than the calibrated 0.7 m, so particles reach the
        // corner early; they must not turn the corner on their own.
        val steps = stepsAlong(listOf(a, b), stride = 0.77).map { it.copy(stepLengthMeters = 0.7) }
        val filter = GraphParticleFilter(corridorMap(a, b, c), "A", random = Random(11))

        val end = steps.map(filter::onStep).last()

        assertTrue(hypot(end.x - b.x, end.y - b.y) < 1.5, "ended at $end")
    }

    @Test
    fun `a confident fingerprint match pulls a lost cloud to the matched place`() {
        val fingerprint = listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -70.0))
        val tagged = node("T", 0.0, 20.0, fingerprint)
        val filter = GraphParticleFilter(corridorMap(a, tagged), "A", random = Random(5))

        val correction = filter.onBeaconReadings(listOf(reading("b1", -60), reading("b2", -70)), timestampMs = 5_000L)

        assertNotNull(correction)
        assertEquals("T", correction.node.id)
        assertTrue(hypot(correction.estimate.x - tagged.x, correction.estimate.y - tagged.y) < 3.0, "estimate ${correction.estimate}")
    }

    @Test
    fun `ignores repeat matches within a second so one place isn't counted many times`() {
        val fingerprint = listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -70.0))
        val tagged = node("T", 0.0, 5.0, fingerprint)
        val filter = GraphParticleFilter(corridorMap(a, tagged), "A", random = Random(5))
        val readings = listOf(reading("b1", -60), reading("b2", -70))

        assertNotNull(filter.onBeaconReadings(readings, timestampMs = 5_000L))
        assertNull(filter.onBeaconReadings(readings, timestampMs = 5_400L))
        assertNotNull(filter.onBeaconReadings(readings, timestampMs = 6_100L))
    }

    @Test
    fun `only matches places on the current floor`() {
        val fingerprint = listOf(RadioFingerprint("b1", -60.0), RadioFingerprint("b2", -70.0))
        val upstairs = node("U", 0.0, 5.0, fingerprint)
        val map = BuildingMap(id = "test", floors = listOf(floor(0, a, b), floor(1, upstairs, node("U2", 0.0, 15.0))))
        val filter = GraphParticleFilter(map, "A", random = Random(5))

        assertNull(filter.onBeaconReadings(listOf(reading("b1", -60), reading("b2", -70)), timestampMs = 5_000L))
    }

    @Test
    fun `a confirmed floor change restarts at the arrival node and keeps the learned heading`() {
        val stairsTop = node("S1", 50.0, 50.0)
        val landing = node("L", 50.0, 60.0)
        val map = BuildingMap(
            id = "test",
            floors = listOf(floor(0, a, b), floor(1, stairsTop, landing)),
            floorTransitions = listOf(FloorTransition("B", 0, "S1", 1, costMeters = 5.0)),
        )
        val filter = GraphParticleFilter(map, "A", random = Random(9))
        stepsAlong(listOf(a, b), gyroOffsetDeg = 40.0).forEach(filter::onStep)

        val arrived = filter.changeFloor("S1", timestampMs = 99_000L)
        assertEquals(1, arrived.floor)
        assertTrue(hypot(arrived.x - stairsTop.x, arrived.y - stairsTop.y) < 0.01, "arrived at $arrived")

        // Still north in the map, in the same rotated gyro frame as downstairs.
        val upstairsSteps = stepsAlong(listOf(stairsTop, landing), gyroOffsetDeg = 40.0, startMs = 100_000L)
            .mapIndexed { i, step -> if (i == 0) step.copy(headingDeltaDeg = 0.0) else step }
        val end = upstairsSteps.map(filter::onStep).last()
        assertTrue(hypot(end.x - landing.x, end.y - landing.y) < 1.5, "ended at $end")
    }

    @Test
    fun `an isolated start node falls back to dead reckoning in the survey's frame`() {
        val lonely = node("A", 0.0, 0.0)
        val filter = GraphParticleFilter(corridorMap(lonely), "A", random = Random(1))

        val end = stepsAlong(listOf(lonely, node("N", 0.0, 7.0))).map(filter::onStep).last()

        assertTrue(hypot(end.x - 0.0, end.y - 7.0) < 1.5, "ended at $end")
    }

    @Test
    fun `every scan pulls a mis-scaled walk back toward where the beacons say it is`() {
        val start = node("A", 0.0, 0.0)
        val end = node("Z", 0.0, 30.0)
        // The walker's true stride is 0.7 m, but the phone thinks it's 0.6 m: 14% short.
        val trueSteps = stepsAlong(listOf(start, end), stride = 0.7).take(36)
        val trueY = trueSteps.indices.map { (it + 1) * 0.7 }
        val scans = trueY.map { y ->
            listOf("b1", "b2").map { id ->
                BleBeaconReading(id, "", null, "u", 1, 1, com.appvalence.umay.positioning.fingerprint.corridorRssi(id, y).toInt(), -59, 1.0, 0L)
            }
        }
        fun walk(withRadioMap: Boolean): Double {
            val base = corridorMap(start, end)
            val map = if (!withRadioMap) base else base.copy(floors = base.floors.map { it.copy(radioMap = com.appvalence.umay.positioning.fingerprint.corridorRadioMap()) })
            val filter = GraphParticleFilter(map, "A", random = Random(21))
            var estimate = 0.0
            trueSteps.forEachIndexed { i, step ->
                estimate = filter.onStep(step.copy(stepLengthMeters = 0.6)).y
                filter.onBeaconReadings(scans[i].map { it.copy(timestampMs = step.timestampMs) }, step.timestampMs + 1_100L * i)
                estimate = filter.estimate().y
            }
            return kotlin.math.abs(estimate - trueY.last())
        }

        val withoutRadio = walk(withRadioMap = false)
        val withRadio = walk(withRadioMap = true)

        assertTrue(withoutRadio > 2.5, "dead reckoning alone was expected to fall behind, was off by $withoutRadio")
        assertTrue(withRadio < 1.5, "the radio map should have kept it within 1.5 m, was off by $withRadio")
    }
}
