package com.appvalence.umay.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Guidance on a map made with the camera ([VirtualCameraSurveyor]: ARCore's
 * drifting poses and depth, through the app's own aligner, wall grid and map
 * builder). Before the aligner took the gyro's word on a resumed frame's turn,
 * one relocalisation turned 9° left places up to 5 m out and walls scrambled;
 * and places named while tracking was lost landed where it stopped, 3 m off.
 */
class CameraSurveyTest {
    private val office = VirtualBuildings.openOffice()
    private val phrases = StringsXmlPhrases.fromAppResources()
    private val trips = listOf("giris" to "mutfak", "giris" to "tuvalet", "asansor" to "toplanti2", "yazici" to "toplanti1", "toplanti1" to "danisma", "mutfak" to "asansor")

    private fun guidedOn(survey: VirtualCameraSurveyor.Survey) =
        trips.flatMap { (a, b) -> (1..2).map { simulateGuidedWalk(office, a, b, it, phrases = phrases, aids = Aids(beacons = true), survey = survey.surveyed) } }

    @Test
    fun `tracking that comes back where it left off makes a true map, walls and all`() {
        for (seed in 1..3) {
            val survey = VirtualCameraSurveyor.survey(office, "giris", seed, tracking = ArTrackingModel(jumpOnResume = 0.0))
            val errors = survey.surveyed.placementErrors(office)
            assertTrue(errors.values.average() < 0.3, "seed $seed: places off by ${errors.values.average()} m on average")
            assertTrue(errors.values.max() < 0.8, "seed $seed: worst place off by ${errors.values.max()} m")
            assertTrue(survey.wallPrecision >= 0.8, "seed $seed: ${survey.wallPrecision} of the wall cells are real walls")
            assertTrue(survey.wallRecall >= 0.65, "seed $seed: ${survey.wallRecall} of the walls in sight were found")
            val results = guidedOn(survey)
            assertTrue(results.count { it.reached } >= results.size - 1, "seed $seed: reached ${results.count { it.reached }} of ${results.size}")
        }
    }

    @Test
    fun `tracking that sometimes comes back in a turned frame still puts places right`() {
        for (seed in 1..3) {
            val survey = VirtualCameraSurveyor.survey(office, "giris", seed)
            val errors = survey.surveyed.placementErrors(office)
            assertTrue(errors.values.average() < 0.5, "seed $seed: places off by ${errors.values.average()} m on average")
            assertTrue(errors.values.max() < 1.0, "seed $seed: worst place off by ${errors.values.max()} m")
            val results = guidedOn(survey)
            assertTrue(results.count { it.reached } >= results.size - 1, "seed $seed: reached ${results.count { it.reached }} of ${results.size}")
        }
    }

    @Test
    fun `poor tracking, lost often and often relocalised wrongly, still guides most walks`() {
        val poor = ArTrackingModel(yawDriftDegPerMeter = 0.5, scaleError = 0.03, lossPerStep = 0.03, jumpOnResume = 0.5)
        val results = (1..3).flatMap { seed ->
            val survey = VirtualCameraSurveyor.survey(office, "giris", seed, tracking = poor)
            val errors = survey.surveyed.placementErrors(office)
            assertTrue(survey.frameJumps > 0, "seed $seed: no wrong relocalisation to test")
            assertTrue(errors.values.average() < 1.0, "seed $seed: places off by ${errors.values.average()} m on average")
            assertTrue(errors.values.max() < 2.5, "seed $seed: worst place off by ${errors.values.max()} m")
            guidedOn(survey)
        }
        assertTrue(results.count { it.reached } >= results.size - 3, "reached ${results.count { it.reached }} of ${results.size}")
    }
}
