package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.ObstacleKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Obstacles in the virtual office, seen by a stand-in for the camera's
 * detector ([VirtualObstacleSensor]), in walks guided by the app: the walker
 * must be warned before reaching one, a person must not send them the long
 * way round, and a corridor that really is closed must still be got round —
 * told to turn back, then which way to go.
 */
class ObstacleWalkTest {
    private val office = VirtualBuildings.openOffice()
    private val phrases = StringsXmlPhrases.fromAppResources()

    private fun walks(start: String, destination: String, obstacle: VirtualObstacle) =
        (1..SEEDS).map { simulateGuidedWalk(office, start, destination, it, phrases = phrases, aids = Aids(beacons = true), obstacles = listOf(obstacle)) }

    @Test
    fun `a person in the way is warned about, and the route stays`() {
        val results = walks("giris", "mutfak", VirtualObstacle("kisi", ObstacleKind.PERSON, 16.0, 12.5, 1.75))
        assertEquals(SEEDS, results.count { it.reached })
        assertEquals(0, results.sumOf { it.unwarnedContacts })
        assertEquals(0, results.sumOf { it.reroutes })
        assertTrue(results.all { (it.firstWarningMeters["kisi"] ?: 0.0) >= 2.0 }, "warned too late: ${results.map { it.firstWarningMeters }}")
    }

    @Test
    fun `a chair in a wide aisle is warned about well before and walked round, the route stays`() {
        val results = walks("giris", "mutfak", VirtualObstacle("sandalye", ObstacleKind.CHAIR, 16.0, 7.5, 0.9))
        assertEquals(SEEDS, results.count { it.reached })
        assertEquals(0, results.sumOf { it.unwarnedContacts })
        assertTrue(results.all { (it.firstWarningMeters["sandalye"] ?: 0.0) >= 2.0 })
        // Before the wall map was asked whether there's room beside it: 11 new routes, 55% longer walks.
        assertEquals(0, results.sumOf { it.reroutes })
        assertTrue(results.map { it.detour }.average() < 1.1, "walked ${results.map { it.detour }.average()}× the shortest way")
    }

    @Test
    fun `a closed corridor is got round`() {
        // Wall to desk, the west aisle is 3.5 m: a cart this wide leaves no way past.
        val cart = VirtualObstacle("araba", ObstacleKind.SUITCASE, 6.5, 7.6, 1.0, blocking = true, widthMeters = 3.4)
        for ((start, destination) in listOf("asansor" to "toplanti2", "giris" to "tuvalet")) {
            val results = walks(start, destination, cart)
            assertTrue(results.count { it.reached } >= SEEDS - 1, "$start→$destination: reached ${results.count { it.reached }} of $SEEDS")
            assertEquals(0, results.sumOf { it.unwarnedContacts })
            assertTrue(results.sumOf { it.reroutes } > 0, "$start→$destination: never routed round the closed corridor")
        }
    }

    private companion object {
        const val SEEDS = 6
    }
}
