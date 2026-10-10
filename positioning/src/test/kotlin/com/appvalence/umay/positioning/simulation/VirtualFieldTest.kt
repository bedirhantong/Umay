package com.appvalence.umay.positioning.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Positioning in a simulated building (see [VirtualBuilding], [simulateWalk]):
 * scenarios a real walk will meet, each run with [SEEDS] different random
 * draws, with error limits a change to the filter must not break. Not a
 * substitute for field logs (FieldLogReplayTest) — the simulated walker keeps
 * to the corridor centre and the radio follows a textbook model — but it
 * catches a change that makes things worse before anyone walks a building.
 *
 * Run with UMAY_SIM_REPORT=1 to print every scenario's numbers.
 */
class VirtualFieldTest {
    private val building = VirtualBuilding.institutionFloor()
    private val loop = listOf("M0", "M10", "N10", "N20", "N30", "M30", "M40")
    private val twiceRound = listOf("M0", "M10", "N10", "N20", "N30", "M30", "M20", "M10", "N10", "N20", "N30", "M30", "M40")

    private fun runs(name: String, walk: (seed: Int) -> WalkResult): List<WalkResult> =
        simulateWalks(SEEDS, walk).also { results ->
            if (System.getenv("UMAY_SIM_REPORT") != null) {
                println(
                    "%-28s mean=%5.2f p90=%5.2f max=%5.2f final=%5.2f | worst: mean=%5.2f max=%5.2f final=%5.2f".format(
                        name, results.map { it.mean }.average(), results.map { it.p90 }.average(), results.map { it.max }.average(),
                        results.map { it.final }.average(), results.maxOf { it.mean }, results.maxOf { it.max }, results.maxOf { it.final },
                    ),
                )
            }
        }

    private fun assertWithin(results: List<WalkResult>, meanMeters: Double, maxMeters: Double) {
        val mean = results.map { it.mean }.average()
        assertTrue(mean < meanMeters, "mean error %.2f m, limit %.1f m".format(mean, meanMeters))
        val worst = results.maxOf { it.max }
        assertTrue(worst < maxMeters, "worst error %.2f m, limit %.1f m".format(worst, maxMeters))
    }

    @Test
    fun `steps alone follow a walk with turns`() {
        assertWithin(runs("steps") { simulateWalk(building, loop, it) }, meanMeters = 0.6, maxMeters = 2.5)
    }

    @Test
    fun `steps alone hold up over a long walk with gyro drift`() {
        val walker = WalkerModel(gyroDriftPerStepDeg = 0.3)
        assertWithin(runs("steps, drift, long") { simulateWalk(building, twiceRound, it, walker = walker) }, meanMeters = 0.6, maxMeters = 2.5)
    }

    @Test
    fun `a stride much shorter than calibrated stays on track`() {
        val walker = WalkerModel(trueStrideMeters = 0.62)
        assertWithin(runs("short stride, steps") { simulateWalk(building, loop, it, walker = walker) }, meanMeters = 0.8, maxMeters = 3.0)
        assertWithin(runs("short stride, beacons") { simulateWalk(building, loop, it, walker = walker, aids = Aids(beacons = true)) }, meanMeters = 0.8, maxMeters = 3.0)
    }

    @Test
    fun `beacons don't make a good walk worse`() {
        assertWithin(runs("beacons") { simulateWalk(building, loop, it, aids = Aids(beacons = true)) }, meanMeters = 0.6, maxMeters = 2.5)
        assertWithin(runs("beacons, long") { simulateWalk(building, twiceRound, it, aids = Aids(beacons = true)) }, meanMeters = 0.6, maxMeters = 3.0)
    }

    @Test
    fun `noisy beacons that often go unheard don't throw the walker off`() {
        val badRadio = VirtualBuilding.institutionFloor(RadioModel(fadingSigmaDb = 7.0, dropoutProbability = 0.3))
        assertWithin(runs("bad radio") { simulateWalk(badRadio, loop, it, aids = Aids(beacons = true)) }, meanMeters = 0.7, maxMeters = 3.0)
    }

    @Test
    fun `beacons heard only now and then mostly do no harm`() {
        // Four advertisements in five lost — a phone deep in a pocket. A wrong place match
        // still gets through now and then, so only the typical error is held here.
        val sparse = VirtualBuilding.institutionFloor(RadioModel(dropoutProbability = 0.8))
        val results = runs("sparse") { simulateWalk(sparse, loop, it, aids = Aids(beacons = true)) }
        val mean = results.map { it.mean }.average()
        assertTrue(mean < 1.5, "mean error %.2f m".format(mean))
        val p90 = results.map { it.p90 }.average()
        assertTrue(p90 < 3.0, "90th percentile %.2f m".format(p90))
    }

    @Test
    fun `beacons rescue a walk started from the wrong place`() {
        val results = runs("wrong start, beacons") { simulateWalk(building, loop, it, aids = Aids(beacons = true), filterStartNodeId = "M40") }
        val recovered = results.count { it.final < 2.0 }
        assertTrue(recovered >= SEEDS - 2, "recovered in $recovered of $SEEDS walks")
        val final = results.map { it.final }.average()
        assertTrue(final < 1.5, "mean final error %.2f m".format(final))
    }

    @Test
    fun `a place marker rescues a walk started from the wrong place, heading included`() {
        val results = runs("wrong start, marker") { simulateWalk(building, loop, it, aids = Aids(markerNodeIds = setOf("M10")), filterStartNodeId = "M40") }
        val worstFinal = results.maxOf { it.final }
        assertTrue(worstFinal < 1.5, "worst final error %.2f m".format(worstFinal))
    }

    private companion object {
        const val SEEDS = 10
    }
}
