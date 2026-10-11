package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.perception.QrMarkerReader
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How far away the app's marker reader ([QrMarkerReader]) reads a printed
 * marker, in frames a [VirtualCamera] renders as the phone would see it: held
 * upright at the chest, its true view 50° across. A 15 cm code reads from
 * about a metre in the 480 × 640 frames navigation uses, two metres at
 * 960 × 1280; the distance it judges is checked too.
 */
class MarkerRangeTest {
    private val wall = VirtualBuilding("duvar", listOf(MapNode("m", 0.0, 1.0, NodeType.WAYPOINT)), emptyList(), emptyList(), walls = listOf(Wall(-8.0, 0.0, 8.0, 0.0)))
    private val marker = PlacedMarker("7d0c7e0e-5f7b-4f0e-9c55-1b2a3c4d5e6f", 0.0, 0.005, facingDeg = 0.0)

    private fun read(camera: VirtualCamera, distance: Double, angleDeg: Double = 0.0) = QrMarkerReader().read(
        camera.render(distance * sin(Math.toRadians(angleDeg)), distance * cos(Math.toRadians(angleDeg)), angleDeg + 180.0),
        camera.width, camera.height, 0,
    ).firstOrNull()

    @Test
    fun `in navigation frames a 15 cm marker reads from about a metre, its distance judged right`() {
        val camera = VirtualCamera(wall, listOf(marker), width = 480, height = 640, horizontalFovDeg = 50.0)
        val near = assertNotNull(read(camera, 1.0))
        assertTrue(abs(near.distanceMeters - 1.0) < 0.15, "judged ${near.distanceMeters} m for 1 m")
        assertTrue(abs(near.bearingDeg) < 3.0, "straight ahead, judged ${near.bearingDeg}°")
        assertNull(read(camera, 2.0), "read from 2 m: the frames are sharper than assumed, update the numbers")
    }

    @Test
    fun `twice the resolution reads twice as far`() {
        val camera = VirtualCamera(wall, listOf(marker), width = 960, height = 1280, horizontalFovDeg = 50.0)
        assertNotNull(read(camera, 2.0))
        assertNotNull(read(camera, 1.5, angleDeg = 30.0))
    }
}
