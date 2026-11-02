package com.appvalence.umay.perception

import kotlin.test.Test
import kotlin.test.assertEquals

class CameraOpticsTest {
    @Test
    fun `a frame on its side sees the long side's width across`() {
        assertEquals(CameraOptics.LONG_SIDE_FOV_DEGREES, CameraOptics.fovAcrossDegrees(640, 480))
        assertEquals(CameraOptics.LONG_SIDE_FOV_DEGREES, CameraOptics.fovDownDegrees(480, 640))
    }

    @Test
    fun `an upright 3 by 4 frame sees about 51 degrees across`() {
        assertEquals(51.08, CameraOptics.fovAcrossDegrees(480, 640), 0.01)
        assertEquals(51.08, CameraOptics.fovDownDegrees(640, 480), 0.01)
    }
}
