package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FloorChangeConfirmationTest {
    private fun state(hint: FloorChangeHint?, floor: Int = 0) = NavigationState(
        position = Position(0.0, 0.0, floor, 0.0, 1.0, PositionSource.MAP_MATCHED, 0L),
        obstacles = emptyList(),
        alerts = emptyList(),
        route = emptyList(),
        pendingFloorChange = hint,
    )

    /** Standard-atmosphere pressure at [meters] above sea level. */
    private fun hPa(meters: Double) = 1013.25 * (1 - meters / 44_330.0).pow(5.255)

    private val upStairs = FloorChangeHint("S1", "Merdiven üst", targetFloor = 1)

    @Test
    fun `with a barometer, climbing a floor and stopping confirms it`() {
        val confirmation = FloorChangeConfirmation(hasBarometer = true)
        var t = 0L
        repeat(30) { confirmation.onPressure(hPa(100.0), t); t += 200 }
        assertNull(confirmation.onState(state(upStairs)))

        val confirmed = generateSequence(0.0) { it + 0.1 }.take(60).map { climbed ->
            t += 200
            confirmation.onPressure(hPa(100.0 + minOf(climbed, 3.4)), t)
        }.firstNotNullOfOrNull { it }

        assertEquals(FloorChangeConfirmation.Confirmed("S1", 1, FloorChangePhase.CONFIRMED_BY_BAROMETER), confirmed)
        // The hint lingers until a position on the new floor; it mustn't arm again meanwhile.
        assertNull(confirmation.onState(state(upStairs)))
    }

    @Test
    fun `without one, stairs confirm themselves from footsteps and lifts wait for the button`() {
        val confirmation = FloorChangeConfirmation(hasBarometer = false)
        assertNull(confirmation.onState(state(upStairs)))
        assertEquals(FloorChangePhase.CONFIRMED_AUTOMATICALLY, confirmation.onState(state(upStairs.copy(autoConfirm = true)))?.phase)

        val lift = FloorChangeHint("L1", "Asansör", targetFloor = 1)
        assertNull(confirmation.onState(state(lift)))
        assertEquals(FloorChangeConfirmation.Confirmed("L1", 1, FloorChangePhase.CONFIRMED_MANUALLY), confirmation.manual())
    }

    @Test
    fun `the button does nothing when no floor change is pending`() {
        val confirmation = FloorChangeConfirmation(hasBarometer = true)
        confirmation.onState(state(null))
        assertNull(confirmation.manual())
    }
}
