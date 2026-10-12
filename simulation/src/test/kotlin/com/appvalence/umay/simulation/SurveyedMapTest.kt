package com.appvalence.umay.simulation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guidance on a map surveyed the way a real one is ([VirtualSurveyor]: by
 * footsteps, drifting, built by the app's own BeaconMapBuilder), not on the
 * building's true plan. Before loop closure, an uncalibrated surveyor with a
 * drifting gyro left 4–6 junctions of the office unjoined, and only 6–19 of 24
 * guided walks arrived.
 */
class SurveyedMapTest {
    private val office = VirtualBuildings.openOffice()
    private val phrases = StringsXmlPhrases.fromAppResources()
    private val trips = listOf("giris" to "mutfak", "giris" to "tuvalet", "asansor" to "toplanti2", "yazici" to "toplanti1", "toplanti1" to "danisma", "mutfak" to "asansor")

    @Test
    fun `a careful surveyor's map puts every place within a metre or so`() {
        val survey = VirtualSurveyor.survey(office, "giris", seed = 1)
        assertEquals(0, survey.unjoinedPlaces)
        val errors = survey.surveyed.placementErrors(office)
        assertTrue(errors.values.average() < 0.6, "places off by ${errors.values.average()} m on average")
        assertTrue(errors.values.max() < 1.5, "worst place off by ${errors.values.max()} m")
    }

    @Test
    fun `a sloppy survey still joins its junctions and guides people there`() {
        // Stride 11% longer than calibrated, the gyro drifting 0.15° a step.
        val sloppy = WalkerModel(trueStrideMeters = 0.78, calibratedStrideMeters = 0.70, gyroDriftPerStepDeg = 0.15)
        val results = (1..2).flatMap { surveySeed ->
            val survey = VirtualSurveyor.survey(office, "giris", surveySeed, walker = sloppy)
            assertEquals(0, survey.unjoinedPlaces, "junctions named again stayed apart")
            trips.flatMap { (a, b) -> (1..2).map { simulateGuidedWalk(office, a, b, it, phrases = phrases, aids = Aids(beacons = true), survey = survey.surveyed) } }
        }
        assertTrue(results.count { it.reached } >= results.size - 2, "reached ${results.count { it.reached }} of ${results.size}")
    }
}
