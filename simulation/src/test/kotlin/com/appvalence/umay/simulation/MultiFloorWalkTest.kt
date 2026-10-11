package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.coremodel.NodeType
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Walks between the floors of a two-storey virtual office
 * ([VirtualBuildings.twoFloorOffice]), guided by the app: the walker climbs
 * the stairs a step at a time or stands in the lift, and the app learns of it
 * the way it does on a phone — from the barometer, else from footsteps on
 * stairs, else from the walker's "Kat değiştirdim" (FloorChangeConfirmation).
 *
 * Found on the way here, and fixed in the app: the instruction to take the
 * lift came only once the estimate was within 2 m of it, which never happened
 * for someone waiting at a lift in a dead end; a barometer that noticed a
 * floor change before guidance expected one was ignored; and twenty steps
 * climbing a staircase in a dead end carried the position estimate off along
 * a corridor.
 */
class MultiFloorWalkTest {
    private val building = VirtualBuildings.twoFloorOffice()
    private val phrases = StringsXmlPhrases.fromAppResources()

    private fun walks(start: String, destination: String, barometer: Boolean = true, avoidStairs: Boolean = false) =
        (1..SEEDS).map { simulateGuidedWalk(building, start, destination, it, phrases = phrases, barometer = barometer, avoidStairs = avoidStairs) }

    private fun assertAllReached(results: List<GuidedWalkResult>, allowMissed: Int = 0) {
        val reached = results.count { it.reached }
        assertTrue(reached >= results.size - allowMissed, "reached $reached of ${results.size}")
    }

    @Test
    fun `up by lift, confirmed by the barometer or by the walker's button`() {
        val withBarometer = walks("giris", "mudur")
        assertAllReached(withBarometer)
        assertTrue(withBarometer.all { it.floorConfirmations == listOf(FloorChangePhase.CONFIRMED_BY_BAROMETER) })

        val without = walks("giris", "mudur", barometer = false)
        assertAllReached(without)
        assertTrue(without.all { it.floorConfirmations == listOf(FloorChangePhase.CONFIRMED_MANUALLY) })
    }

    @Test
    fun `up the stairs, confirmed by the barometer or by footsteps`() {
        assertAllReached(walks("yazici", "salon"))
        val without = walks("yazici", "salon", barometer = false)
        assertAllReached(without)
        assertTrue(without.all { FloorChangePhase.CONFIRMED_AUTOMATICALLY in it.floorConfirmations })
    }

    @Test
    fun `down the stairs`() {
        assertAllReached(walks("mudur", "mutfak"), allowMissed = 1)
        assertAllReached(walks("salon", "danisma", barometer = false))
    }

    @Test
    fun `a walker who avoids stairs is taken by lift, even from right beside the stairs`() {
        val results = walks("yazici", "mudur", avoidStairs = true) + walks("yazici", "mudur", barometer = false, avoidStairs = true)
        assertAllReached(results)
        assertTrue(results.all { it.crossings == listOf(NodeType.ELEVATOR) }, results.map { it.crossings }.toString())
    }

    @Test
    fun `once on the new floor, the app knows it within a few steps`() {
        val results = walks("yazici", "salon") + walks("giris", "mudur")
        val wrongFloor = results.map { it.wrongFloorSteps }.average()
        assertTrue(wrongFloor < 3.0, "on the wrong floor for %.1f steps on average".format(wrongFloor))
    }

    private companion object {
        const val SEEDS = 6
    }
}
