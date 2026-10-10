package com.appvalence.umay.navigation

import androidx.lifecycle.SavedStateHandle
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.RadioFingerprint
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.testing.FakeBuildingMapStore
import com.appvalence.umay.testing.FakeBeaconScanner
import com.appvalence.umay.testing.FakeCapabilities
import com.appvalence.umay.testing.FakeFieldLogFiles
import com.appvalence.umay.testing.FakeNavigationSessions
import com.appvalence.umay.testing.FakeSpeaker
import com.appvalence.umay.testing.FakeUserSettings
import com.appvalence.umay.testing.MainDispatcherRule
import com.appvalence.umay.testing.building
import com.appvalence.umay.testing.node
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val sessions = FakeNavigationSessions()
    private val scanner = FakeBeaconScanner()
    private val threeNodes = building("Ofis", nodes = listOf(node("a", 0.0), node("b", 5.0), node("c", 10.0)))

    private fun TestScope.viewModel(buildings: List<BuildingMap>, saved: SavedStateHandle = SavedStateHandle()): NavigationViewModel {
        val repository = BuildingRepository({ FakeBuildingMapStore(buildings) }, backgroundScope, main.dispatcher).apply { refresh() }
        return NavigationViewModel(repository, sessions, FakeUserSettings(), FakeFieldLogFiles(), FakeCapabilities(), FakeSpeaker(), saved, scanner).also { viewModel ->
            backgroundScope.launch { viewModel.state.collect {} }
        }
    }

    @Test
    fun `the only building is picked on its own, from its first to its last point`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(threeNodes))
        advanceUntilIdle()

        val selected = viewModel.state.value.selected!!
        assertEquals("Ofis", selected.map.id)
        assertEquals(0, selected.startIndex)
        assertEquals(2, selected.destinationIndex)
        assertTrue(selected.canBegin)
    }

    @Test
    fun `with several buildings nothing is picked until one is chosen`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(threeNodes, building("Okul")))
        advanceUntilIdle()
        assertNull(viewModel.state.value.selected)

        viewModel.selectBuilding("Okul")
        advanceUntilIdle()

        assertEquals("Okul", viewModel.state.value.selected?.map?.id)
    }

    @Test
    fun `beginning starts a walk between the chosen points`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(threeNodes))
        advanceUntilIdle()

        viewModel.setStart(1)
        viewModel.setDestination(0)
        advanceUntilIdle()
        viewModel.begin()

        assertEquals(listOf(FakeNavigationSessions.Start(threeNodes, "b", "a")), sessions.starts)
    }

    @Test
    fun `a walk from a point to itself can't begin`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(threeNodes))
        advanceUntilIdle()

        viewModel.setDestination(0)
        advanceUntilIdle()
        viewModel.begin()

        assertFalse(viewModel.state.value.selected!!.canBegin)
        assertTrue(sessions.starts.isEmpty())
    }

    @Test
    fun `changing building forgets the route picked in the previous one`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(threeNodes, building("Okul")))
        viewModel.selectBuilding("Ofis")
        viewModel.setStart(2)
        advanceUntilIdle()

        viewModel.changeBuilding()
        viewModel.selectBuilding("Ofis")
        advanceUntilIdle()

        assertEquals(0, viewModel.state.value.selected!!.startIndex)
    }

    @Test
    fun `the choice survives the process being restarted`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle(mapOf("building" to "Ofis", "start" to 1, "destination" to 2))
        val viewModel = viewModel(listOf(threeNodes, building("Okul")), saved)
        advanceUntilIdle()

        val selected = viewModel.state.value.selected!!
        assertEquals("Ofis", selected.map.id)
        assertEquals(1, selected.startIndex)
        assertEquals(2, selected.destinationIndex)
    }

    @Test
    fun `an out-of-range saved point is clamped instead of crashing`() = runTest(main.dispatcher) {
        val saved = SavedStateHandle(mapOf("start" to 99))
        val viewModel = viewModel(listOf(threeNodes), saved)
        advanceUntilIdle()

        assertEquals(2, viewModel.state.value.selected!!.startIndex)
    }

    // --- Finding where the walk starts ---

    private fun reading(id: String, rssi: Int) = BleBeaconReading(id, "", null, "u", 1, 1, rssi, -59, 1.0, 0L)

    // Three places, each with its own signature over beacons b1 and b2.
    private val fingerprinted = building(
        "Ofis",
        nodes = listOf(
            node("a", 0.0).copy(fingerprint = listOf(RadioFingerprint("b1", -55.0), RadioFingerprint("b2", -85.0))),
            node("b", 5.0).copy(fingerprint = listOf(RadioFingerprint("b1", -70.0), RadioFingerprint("b2", -70.0))),
            node("c", 10.0).copy(fingerprint = listOf(RadioFingerprint("b1", -85.0), RadioFingerprint("b2", -55.0))),
        ),
    )

    private suspend fun TestScope.hearAt(rssi1: Int, rssi2: Int, times: Int = 3) {
        repeat(times) {
            scanner.beacons.value = listOf(reading("b1", rssi1 + it % 2), reading("b2", rssi2))
            advanceUntilIdle()
        }
    }

    @Test
    fun `a confident place is suggested as the start, and accepting it sets it`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(fingerprinted))
        advanceUntilIdle()
        viewModel.findStart()
        assertTrue(scanner.started)

        hearAt(-70, -70)

        assertEquals("b", viewModel.startHint.value.suggested?.id)
        assertEquals("b", viewModel.startHint.value.nearbyIds.first())
        viewModel.acceptSuggestedStart()
        assertEquals(1, viewModel.state.value.selected!!.startIndex)
        assertEquals(null, viewModel.startHint.value.suggested)
    }

    @Test
    fun `a declined place isn't suggested again`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(fingerprinted))
        advanceUntilIdle()
        viewModel.findStart()
        hearAt(-70, -70)

        viewModel.declineSuggestedStart()
        hearAt(-70, -70)

        assertEquals(null, viewModel.startHint.value.suggested)
    }

    @Test
    fun `starting the walk stops the picker's own scan`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(fingerprinted))
        advanceUntilIdle()
        viewModel.findStart()

        viewModel.begin()

        assertFalse(scanner.started)
    }

    @Test
    fun `a map surveyed without beacons doesn't scan at all`() = runTest(main.dispatcher) {
        val viewModel = viewModel(listOf(threeNodes))
        advanceUntilIdle()

        viewModel.findStart()

        assertFalse(scanner.started)
    }
}
