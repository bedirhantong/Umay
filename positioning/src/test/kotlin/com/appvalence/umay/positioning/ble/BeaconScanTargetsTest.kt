package com.appvalence.umay.positioning.ble

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BeaconScanTargetsTest {

    private val uuid = "f7826da6-4fa2-4e98-8024-bc5b71e0893e"

    @Test
    fun `iBeacons sharing a UUID collapse into one filter regardless of major and minor`() {
        val targets = BeaconScanTargets.forBeaconIds(listOf("$uuid-1-100", "$uuid-1-101", "$uuid-2-7"))

        assertEquals(listOf(ScanTarget.IBeaconUuid(uuid)), targets)
    }

    @Test
    fun `a MAC-address beacon id becomes an address filter`() {
        assertEquals(listOf(ScanTarget.DeviceAddress("AA:BB:CC:DD:EE:FF")), BeaconScanTargets.forBeaconIds(listOf("aa:bb:cc:dd:ee:ff")))
    }

    @Test
    fun `unrecognizable ids are skipped rather than failing the whole scan`() {
        assertEquals(listOf(ScanTarget.IBeaconUuid(uuid)), BeaconScanTargets.forBeaconIds(listOf("garbage", "$uuid-1-2")))
    }

    @Test
    fun `nothing to filter on means scan unfiltered`() {
        assertNull(BeaconScanTargets.forBeaconIds(emptyList()))
        assertNull(BeaconScanTargets.forBeaconIds(listOf("garbage")))
    }

    @Test
    fun `too many targets for the hardware falls back to unfiltered`() {
        val macs = (0..BeaconScanTargets.MAX_FILTERS).map { "AA:BB:CC:DD:EE:%02X".format(it) }

        assertNull(BeaconScanTargets.forBeaconIds(macs))
    }
}
