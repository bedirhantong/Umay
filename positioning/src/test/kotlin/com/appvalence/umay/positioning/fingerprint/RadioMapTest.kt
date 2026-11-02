package com.appvalence.umay.positioning.fingerprint

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.Position
import com.appvalence.umay.coremodel.PositionSource
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.RadioSample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two beacons at the ends of a 30 m corridor along y; each loses 1.5 dB a metre. */
internal fun corridorRssi(beacon: String, y: Double): Double = when (beacon) {
    "b1" -> -50.0 - 1.5 * y
    else -> -50.0 - 1.5 * (30.0 - y)
}.coerceAtLeast(-95.0)

internal fun corridorRadioMap(spacing: Double = 3.0): List<RadioSample> =
    generateSequence(0.0) { it + spacing }.takeWhile { it <= 30.0 }.map { y ->
        RadioSample(0.0, y, listOf(RadioFingerprint("b1", corridorRssi("b1", y)), RadioFingerprint("b2", corridorRssi("b2", y))))
    }.toList()

class RadioMapTest {

    private val radio = RadioMap(corridorRadioMap())

    @Test
    fun `between two samples the expected signal is in between`() {
        val expected = radio.expectedAt(0.0, 4.5)!!

        assertTrue(expected.getValue("b1") < corridorRssi("b1", 3.0) && expected.getValue("b1") > corridorRssi("b1", 6.0), "$expected")
    }

    @Test
    fun `far from every sample nothing is expected`() {
        assertNull(radio.expectedAt(50.0, 50.0))
    }

    @Test
    fun `a scan fits best where it was heard`() {
        val heardAt20 = mapOf("b1" to corridorRssi("b1", 20.0), "b2" to corridorRssi("b2", 20.0))

        val fits = (0..30 step 5).associateWith { y -> radio.logLikelihood(heardAt20, 0.0, y.toDouble(), sigmaDb = 6.0)!! }

        assertEquals(20, fits.maxBy { it.value }.key)
    }
}

class RadioSamplerTest {

    private fun at(y: Double, t: Long) = Position(0.0, y, 0, 0.0, 0.1, PositionSource.MAP_MATCHED, t)
    private fun heard(id: String, rssi: Int, t: Long) = BleBeaconReading(id, "", null, "u", 1, 1, rssi, -59, 1.0, t)

    @Test
    fun `samples come every few metres, from what was heard just now`() {
        val sampler = RadioSampler(spacingMeters = 3.0, maxAgeMs = 3_000)
        val beacons = listOf(heard("b1", -60, 9_000), heard("b2", -70, 9_500), heard("old", -40, 1_000))

        val first = assertNotNull(sampler.offer(at(0.0, 10_000), beacons))
        assertEquals(listOf("b1", "b2"), first.fingerprint.map { it.beaconId })
        assertNull(sampler.offer(at(2.0, 10_500), beacons))
        assertNotNull(sampler.offer(at(3.5, 11_000), beacons))
    }

    @Test
    fun `fewer than two beacons heard makes no sample`() {
        assertNull(RadioSampler().offer(at(0.0, 10_000), listOf(heard("b1", -60, 9_900))))
    }
}

class RadioMapBudgetTest {

    @Test
    fun `an oversized radio map is thinned, not dropped`() {
        val dense = mapOf(0 to corridorRadioMap(spacing = 0.1))

        val fitted = RadioMapBudget.fit(dense, maxEncodedChars = 10_000)

        val samples = fitted.getValue(0)
        assertTrue(samples.size < dense.getValue(0).size)
        assertTrue(abs(samples.last().y - 30.0) < 1.0, "coverage kept to the corridor's end: ${samples.last().y}")
    }
}
