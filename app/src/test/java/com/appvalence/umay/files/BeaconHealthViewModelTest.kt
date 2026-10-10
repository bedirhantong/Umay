package com.appvalence.umay.files

import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.testing.FakeBeaconScanner
import com.appvalence.umay.testing.MainDispatcherRule
import com.appvalence.umay.testing.building
import com.appvalence.umay.testing.node
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BeaconHealthViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val scanner = FakeBeaconScanner()
    private val uuid = "f7826da6-4fa2-4e98-8024-bc5b71e0893e"
    private val office = building(
        "Ofis",
        nodes = listOf(
            node("a", 0.0).copy(fingerprint = listOf(RadioFingerprint("$uuid-1-1", -60.0), RadioFingerprint("$uuid-1-2", -70.0))),
            node("b", 5.0),
        ),
    )

    @Test
    fun `the map's beacons are checked off as they're heard, while the screen is open`() = runTest(main.dispatcher) {
        val viewModel = BeaconHealthViewModel(scanner)

        viewModel.start(office)
        assertTrue(scanner.started)
        assertEquals(2, viewModel.state.value.statuses.count { !it.heard })

        scanner.raw.emit(BleBeaconReading("$uuid-1-1", "AA:01", null, uuid, 1, 1, -62, -59, 1.0, 5L))
        advanceTimeBy(1_001)

        val statuses = viewModel.state.value.statuses
        assertEquals(listOf(false, true), statuses.map { it.heard })
        assertEquals(1, viewModel.state.value.scanSeconds)
        // Surveyed without signatures along the path: coverage can't be told.
        assertNull(viewModel.state.value.coverage.getValue(0))

        viewModel.stop()
        assertFalse(scanner.started)
    }
}
