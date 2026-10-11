package com.appvalence.umay.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Validation, kept apart from tuning (roadmap 0.15): every constant of the app
 * was chosen on the office and the institution floor, never on this clinic
 * ([VirtualBuildings.clinicFloor]), and none ever may be. A change that helps
 * there and fails here fits those two buildings, not buildings. The walker is
 * the realistic one ([WalkerModel], speech taking its time).
 *
 * The limits are where the app stood when the clinic was added, not where it
 * should be: there the office's 36 of 36 were 32 with markers, and on beacons
 * alone 21 (30 with the compass hint). Long stretches let the estimate fall
 * behind, so arrival at the far end comes late; starting from a door, the
 * heading isn't learned before the first corner. Raise them as that improves.
 */
class ValidationWalkTest {
    private val clinic = VirtualBuildings.clinicFloor()
    private val phrases = StringsXmlPhrases.fromAppResources()
    private val trips = listOf(
        "giris" to "rontgen",
        "giris" to "tuvalet",
        "eczane" to "kafeterya",
        "poliklinik2" to "laboratuvar",
        "vezne" to "giris",
        "kafeterya" to "poliklinik1",
    )

    private fun walks(aids: Aids) = trips.flatMap { (a, b) -> (1..SEEDS).map { simulateGuidedWalk(clinic, a, b, it, phrases = phrases, aids = aids) } }

    private fun assertReached(results: List<GuidedWalkResult>, atLeast: Int) {
        val reached = results.count { it.reached }
        if (System.getenv("UMAY_SIM_REPORT") != null) {
            println("klinik: reached $reached/${results.size}, wrong turns ${results.sumOf { it.wrongTurns }}, error %.2f m".format(results.map { it.meanError }.average()))
        }
        assertTrue(reached >= atLeast, "reached $reached of ${results.size}, expected at least $atLeast")
    }

    @Test
    fun `beacons, markers and the compass get a newcomer around a building nobody tuned for`() {
        assertReached(walks(Aids(beacons = true, markerNodeIds = clinic.markerNodeIds, compass = true)), atLeast = 33)
    }

    @Test
    fun `beacons alone, with and without the compass`() {
        assertReached(walks(Aids(beacons = true, compass = true)), atLeast = 28)
        assertReached(walks(Aids(beacons = true)), atLeast = 19)
    }

    private companion object {
        const val SEEDS = 6
    }
}
