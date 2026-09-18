package com.appvalence.umay.positioning.engine

import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingResettable : Resettable {
    var lastReset: DoubleArray? = null
    override fun resetTo(x: Double, y: Double, headingDeg: Double, accuracyMeters: Double) {
        lastReset = doubleArrayOf(x, y, headingDeg, accuracyMeters)
    }
}

class PositionFusionEngineTest {

    @Test
    fun `a lone PDR update passes through relabeled as fused without resetting the integrator`() = runTest {
        val resettable = RecordingResettable()
        val engine = PositionFusionEngine(resettable)
        val pdrPosition = Position(1.0, 2.0, 0, 45.0, 0.6, PositionSource.PEDESTRIAN_DEAD_RECKONING, 100L)

        val result = engine.fuse(emptyFlow(), flowOf(pdrPosition)).toList()

        assertEquals(1, result.size)
        assertEquals(1.0, result[0].x)
        assertEquals(2.0, result[0].y)
        assertEquals(PositionSource.FUSED, result[0].source)
        assertNull(resettable.lastReset)
    }

    @Test
    fun `the first UWB fix with no prior estimate is taken as-is`() = runTest {
        val resettable = RecordingResettable()
        val engine = PositionFusionEngine(resettable)
        val uwbPosition = Position(5.0, 5.0, 0, 0.0, 0.3, PositionSource.UWB_TRILATERATION, 100L)

        val result = engine.fuse(flowOf(uwbPosition), emptyFlow()).toList()

        assertEquals(1, result.size)
        assertEquals(5.0, result[0].x, 1e-9)
        assertEquals(5.0, result[0].y, 1e-9)
        assertEquals(PositionSource.FUSED, result[0].source)
    }

    @Test
    fun `a UWB fix after a PDR estimate blends the two and resets the integrator`() = runTest {
        val resettable = RecordingResettable()
        val engine = PositionFusionEngine(resettable)

        // Drifted PDR estimate with poor accuracy (large variance), then a precise UWB fix.
        val pdrFlow = flow { emit(Position(10.0, 10.0, 0, 90.0, 2.0, PositionSource.PEDESTRIAN_DEAD_RECKONING, 0L)) }
        val uwbFlow = flow {
            delay(10)
            emit(Position(0.0, 0.0, 0, 0.0, 0.1, PositionSource.UWB_TRILATERATION, 10L))
        }

        val result = engine.fuse(uwbFlow, pdrFlow).toList()

        assertEquals(2, result.size)
        val blended = result[1]
        // The accurate UWB fix should dominate the blend (its variance is far smaller).
        assertTrue(blended.x < 1.0, "expected blended x close to the UWB fix but was ${blended.x}")
        assertTrue(blended.accuracyMeters < 0.1, "expected fused accuracy tighter than either source")
        assertEquals(PositionSource.FUSED, blended.source)

        // The PDR integrator must be corrected to the blended point, not the raw UWB one.
        val reset = resettable.lastReset
        assertTrue(reset != null)
        assertEquals(blended.x, reset[0], 1e-9)
        assertEquals(blended.y, reset[1], 1e-9)
    }
}
