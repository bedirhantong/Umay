package com.appvalence.umay.simulation

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IlcDatasetTest {

    /**
     * A walk through [corners] at 1.26 m/s in the dataset's own text format: the phone
     * flat in front of the walker, a footfall every 0.55 s on the accelerometer, the
     * gyroscope turning with the walk, a ground-truth point every 2 s.
     */
    private fun syntheticWalk(name: String, vararg corners: Pair<Double, Double>): IlcPath {
        val speed = 1.26
        val legs = corners.toList().zipWithNext()
        val lengths = legs.map { (a, b) -> hypot(b.first - a.first, b.second - a.second) }
        val total = lengths.sum() / speed
        fun at(seconds: Double): Pair<Pair<Double, Double>, Double> {
            var left = seconds * speed
            for ((i, leg) in legs.withIndex()) {
                val (a, b) = leg
                val bearing = Math.toDegrees(atan2(b.first - a.first, b.second - a.second))
                if (left <= lengths[i] || i == legs.lastIndex) {
                    val f = (left / lengths[i]).coerceIn(0.0, 1.0)
                    return (a.first + (b.first - a.first) * f to a.second + (b.second - a.second) * f) to bearing
                }
                left -= lengths[i]
            }
            error("unreachable")
        }
        val lines = mutableListOf("#\tstartTime:1000", "#\tSiteID:test")
        var previousAzimuth = at(0.0).second
        var t = 0.0
        while (t <= total) {
            val ms = 1_000L + (t * 1000).toLong()
            val (_, azimuth) = at(t)
            var turn = azimuth - previousAzimuth
            if (turn > 180) turn -= 360
            if (turn < -180) turn += 360
            previousAzimuth = azimuth
            // Clockwise is a falling rate about the vertical for a phone lying flat.
            val gyroZ = -Math.toRadians(turn) / 0.02
            lines += "$ms\tTYPE_ACCELEROMETER\t0.1\t0.2\t${9.80665 + 2.5 * sin(2 * PI * 1.8 * t)}\t3"
            lines += "$ms\tTYPE_GYROSCOPE\t0.0\t0.0\t$gyroZ\t3"
            lines += "$ms\tTYPE_ROTATION_VECTOR\t0.0\t0.0\t0.0\t3"
            if ((t * 50).toLong() % 100 == 0L) {
                val (point, _) = at(t)
                lines += "$ms\tTYPE_WAYPOINT\t${point.first}\t${point.second}"
            }
            t += 0.02
        }
        val (end, _) = at(total)
        lines += "${1_000L + (total * 1000).toLong() + 1}\tTYPE_WAYPOINT\t${end.first}\t${end.second}"
        return IlcPath.parse(name, lines)
    }

    @Test
    fun `reads every kind of line it needs and skips broken ones`() {
        val path = IlcPath.parse(
            "p",
            listOf(
                "#\tSiteName:test",
                "1000\tTYPE_WAYPOINT\t1.5\t2.5",
                "1010\tTYPE_ACCELEROMETER\t0.1\t0.2\t9.8\t3",
                "1010\tTYPE_GYROSCOPE\t0.01\t0.02\t0.03\t3",
                "1010\tTYPE_ROTATION_VECTOR\t0.0\t0.0\t0.1\t3",
                "1020\tTYPE_BEACON\tFDA50693-A4E2-4FB1-AFCF-C6EB07647825\t10073\t61418\t-65\t-82\t5.5\t6B:11:4C:D1:29:F2\t1020",
                "1030\tTYPE_WIFI\tcafe\t0e:74:9c:a7:b2:e4\t-43\t5805\t1029",
                "1040\tTYPE_ACCELEROMETER\tbroken",
                "2000\tTYPE_WAYPOINT\t3.5\t2.5",
            ),
        )
        assertEquals(1, path.accelerometer.size)
        assertEquals(1, path.gyroscope.size)
        assertEquals(1, path.rotation.size)
        assertEquals(listOf(IlcPath.Scan(1020, "FDA50693-A4E2-4FB1-AFCF-C6EB07647825-10073-61418", -82)), path.beacons)
        assertEquals(listOf(IlcPath.Scan(1030, "0e:74:9c:a7:b2:e4", -43)), path.wifi)
        assertEquals(2.0, path.truthLengthMeters, 1e-9)
        assertEquals(Point(2.5, 2.5), path.truthAt(1500))
        assertNull(path.truthAt(999))
    }

    @Test
    fun `heading from the gyroscope turns clockwise for a right turn`() {
        val path = syntheticWalk("r", 0.0 to 0.0, 0.0 to 10.0, 10.0 to 10.0)
        val headings = path.gyroHeadings()
        assertEquals(90.0, headings.last().second - headings.first().second, 1.0)
    }

    @Test
    fun `the app's step detection counts the synthetic footfalls`() {
        val path = syntheticWalk("s", 0.0 to 0.0, 0.0 to 20.0)
        val steps = path.steps(0.7)
        // 20 m at 1.26 m/s and 1.8 steps a second: about 28 footfalls.
        assertTrue(steps.size in 25..30, "${steps.size} steps")
    }

    @Test
    fun `corridors from different walks meet where they cross`() {
        val floor = IlcFloor("test/F1", listOf(syntheticWalk("a", 0.0 to 5.0, 10.0 to 5.0), syntheticWalk("b", 5.0 to 0.0, 5.0 to 10.0)))
        val map = floor.surveyMap(excluded = null, radio = IlcRadio.NONE).floors.single()
        val center = map.nodes.single { hypot(it.x - 5.0, it.y - 5.0) < 0.8 }
        assertEquals(4, map.edges.count { it.fromId == center.id || it.toId == center.id })
    }

    @Test
    fun `a real-format walk replays through the app's positioning on a map from the others`() {
        val l = arrayOf(0.0 to 0.0, 0.0 to 15.0, 12.0 to 15.0)
        val floor = IlcFloor("test/F1", listOf(syntheticWalk("a", *l), syntheticWalk("b", *l.reversedArray()), syntheticWalk("c", *l)))
        val result = assertNotNull(replayIlcWalk(floor, floor.paths.first(), seed = 3))
        assertTrue(result.particleMean < 1.5, "mean error ${result.particleMean}")
        assertEquals(1.0, result.coverage, 1e-9)
    }
}
