package com.appvalence.umay.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

class TurnTest {

    @Test
    fun `right angles are 3 and 9 o'clock, straight back is 6`() {
        assertEquals(3, Turn(90.0).clockHour)
        assertEquals(9, Turn(-90.0).clockHour)
        assertEquals(6, Turn(180.0).clockHour)
        assertEquals(6, Turn(-180.0).clockHour)
    }

    @Test
    fun `a half-hour turn rounds the same way on both sides`() {
        assertEquals(2, Turn(45.0).clockHour)
        assertEquals(10, Turn(-45.0).clockHour)
    }

    @Test
    fun `a slight turn is 1 or 11 o'clock`() {
        assertEquals(1, Turn(30.0).clockHour)
        assertEquals(11, Turn(-30.0).clockHour)
    }

    @Test
    fun `nearly straight ahead is 12 o'clock`() {
        assertEquals(12, Turn(10.0).clockHour)
        assertEquals(12, Turn(-10.0).clockHour)
    }

    @Test
    fun `direction follows the sign`() {
        assertEquals(TurnDirection.RIGHT, Turn(40.0).direction)
        assertEquals(TurnDirection.LEFT, Turn(-40.0).direction)
    }

    @Test
    fun `a wall's side as a clock hour`() {
        assertEquals(9, Turn.clockHourOf(TurnDirection.LEFT))
        assertEquals(3, Turn.clockHourOf(TurnDirection.RIGHT))
    }
}
