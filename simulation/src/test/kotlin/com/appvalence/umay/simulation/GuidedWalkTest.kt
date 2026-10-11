package com.appvalence.umay.simulation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The app as a blind newcomer meets it, in the virtual open office: a walker
 * who knows only what the app says ([simulateGuidedWalk]) on six everyday
 * trips, each with several random draws. The limits are what a change to
 * positioning or guidance must not make worse.
 *
 * Run with UMAY_SIM_REPORT=1 to print every scenario's numbers.
 */
class GuidedWalkTest {
    private val office = VirtualBuildings.openOffice()
    private val phrases = StringsXmlPhrases.fromAppResources()
    private val everything = Aids(beacons = true, markerNodeIds = office.markerNodeIds)

    private fun trips(
        name: String,
        building: VirtualBuilding = office,
        walk: (start: String, destination: String, seed: Int) -> GuidedWalkResult,
    ): List<GuidedWalkResult> {
        val results = TRIPS.flatMap { (start, destination) -> (1..SEEDS).map { walk(start, destination, it) } }
        if (System.getenv("UMAY_SIM_REPORT") != null) {
            println(
                "%-26s %s: reached %d/%d, wrong turns %.2f, wrong way %.2f, off route %.2f, detour %.2f, error %.2f m".format(
                    name, building.name, results.count { it.reached }, results.size, results.map { it.wrongTurns }.average(),
                    results.map { it.wrongWayAlerts }.average(), results.map { it.offRouteAlerts }.average(),
                    results.map { it.detour }.average(), results.map { it.meanError }.average(),
                ),
            )
        }
        return results
    }

    private fun assertReached(results: List<GuidedWalkResult>, atLeast: Int) {
        val reached = results.count { it.reached }
        assertTrue(reached >= atLeast, "reached the destination in $reached of ${results.size} walks, expected at least $atLeast")
    }

    @Test
    fun `with beacons and markers a newcomer gets everywhere without a wrong turn`() {
        val results = trips("beacons + markers") { a, b, seed -> simulateGuidedWalk(office, a, b, seed, phrases = phrases, aids = everything) }
        assertReached(results, atLeast = results.size - 1)
        // The few there are come from setting off through a meeting-room door: a stretch
        // too short to learn which way the walker faces before the first junction
        // (roadmap 3.5, a compass hint, is for that).
        val wrongTurns = results.sumOf { it.wrongTurns }
        assertTrue(wrongTurns <= 4, "$wrongTurns wrong turns")
        val detour = results.map { it.detour }.average()
        assertTrue(detour < 1.15, "walked %.2f× the shortest way".format(detour))
    }

    @Test
    fun `clock-face directions guide as well as left and right`() {
        val clock = StringsXmlPhrases.fromAppResources(clockDirections = true)
        val results = trips("clock directions") { a, b, seed -> simulateGuidedWalk(office, a, b, seed, phrases = clock, aids = everything) }
        assertReached(results, atLeast = results.size - 1)
    }

    @Test
    fun `beacons alone still get a newcomer there`() {
        val results = trips("beacons only") { a, b, seed -> simulateGuidedWalk(office, a, b, seed, phrases = phrases, aids = Aids(beacons = true)) }
        assertReached(results, atLeast = results.size - 4)
    }

    @Test
    fun `a stride far from the calibrated one, or a drifting gyro, is absorbed`() {
        val short = trips("short stride") { a, b, seed ->
            simulateGuidedWalk(office, a, b, seed, phrases = phrases, aids = everything, walker = WalkerModel(trueStrideMeters = 0.6))
        }
        assertReached(short, atLeast = short.size - 2)
        val drift = trips("gyro drift") { a, b, seed ->
            simulateGuidedWalk(office, a, b, seed, phrases = phrases, aids = everything, walker = WalkerModel(gyroDriftPerStepDeg = 0.3))
        }
        assertReached(drift, atLeast = drift.size - 2)
    }

    @Test
    fun `steps alone mostly get there, and never claim arrival far away`() {
        val results = trips("steps only") { a, b, seed -> simulateGuidedWalk(office, a, b, seed, phrases = phrases, aids = Aids()) }
        assertReached(results, atLeast = results.size * 3 / 4)
        val falseArrivals = results.count { it.arrived && !it.reached }
        assertTrue(falseArrivals <= 1, "said 'arrived' $falseArrivals times with the walker more than ${GuidedWalkResult.REACHED_METERS} m away")
    }

    @Test
    fun `a wrong starting place is mostly recovered from`() {
        // The walker says they're in the lobby (or at the entrance, when they really are in the lobby).
        val results = trips("wrong start") { a, b, seed ->
            simulateGuidedWalk(office, a, b, seed, phrases = phrases, aids = everything, appStartNodeId = if (a == "lobi") "giris" else "lobi")
        }
        assertReached(results, atLeast = results.size / 2)
    }

    @Test
    fun `printed markers, read by the camera, rescue a walker the app has misplaced`() {
        // Markers hung on the nearest wall and read from rendered frames by the app's own
        // reader: walking normally they're hardly ever close and square enough to read, but
        // a walker the app has put in the wrong place wanders past one sooner or later.
        val wrongStart = { a: String -> if (a == "lobi") "giris" else "lobi" }
        val trips = TRIPS.take(4)
        val withMarkers = trips.flatMap { (a, b) ->
            (1..3).map { simulateGuidedWalk(office, a, b, it, phrases = phrases, aids = Aids(markerNodeIds = office.markerNodeIds, camera = true), appStartNodeId = wrongStart(a)) }
        }
        val without = trips.flatMap { (a, b) -> (1..3).map { simulateGuidedWalk(office, a, b, it, phrases = phrases, aids = Aids(), appStartNodeId = wrongStart(a)) } }
        assertTrue(withMarkers.sumOf { it.markersRead } > 0, "no marker was ever read")
        val rescued = withMarkers.count { it.reached } - without.count { it.reached }
        assertTrue(rescued >= 3, "markers rescued $rescued more walks of ${withMarkers.size}")
    }

    private companion object {
        const val SEEDS = 6
        val TRIPS = listOf(
            "giris" to "mutfak",
            "giris" to "tuvalet",
            "asansor" to "toplanti2",
            "yazici" to "toplanti1",
            "toplanti1" to "danisma",
            "mutfak" to "asansor",
        )
    }
}
