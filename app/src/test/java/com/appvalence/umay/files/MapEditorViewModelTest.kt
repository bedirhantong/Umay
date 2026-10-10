package com.appvalence.umay.files

import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.testing.FakeBuildingMapStore
import com.appvalence.umay.testing.MainDispatcherRule
import com.appvalence.umay.testing.building
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapEditorViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val office = building("Ofis")
    private val store = FakeBuildingMapStore(listOf(office, building("Depo")))

    private fun TestScope.viewModel() = MapEditorViewModel(BuildingRepository({ store }, backgroundScope, main.dispatcher)).also { it.open(office) }

    @Test
    fun `edits stay in the working copy until saved`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.updatePlace("a", "Ana giriş", NodeType.DOOR)

        assertTrue(viewModel.state.value.hasChanges)
        assertEquals("A", store.files.getValue("Ofis").floors[0].nodes.first { it.id == "a" }.name)

        viewModel.save()
        advanceUntilIdle()

        val saved = store.files.getValue("Ofis").floors[0].nodes.first { it.id == "a" }
        assertEquals("Ana giriş", saved.name)
        assertEquals(NodeType.DOOR, saved.type)
        assertFalse(viewModel.state.value.hasChanges)
        assertEquals(MapEditorMessage.SAVED, viewModel.state.value.message)
    }

    @Test
    fun `a name and type change is undone in one step`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.updatePlace("a", "Ana giriş", NodeType.DOOR)

        viewModel.undo()

        assertFalse(viewModel.state.value.hasChanges)
        assertEquals(0, viewModel.state.value.undoCount)
    }

    @Test
    fun `renaming the building moves it, and the screen is told before the old name goes`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        var followedTo: String? = null
        viewModel.renameBuilding("  Yeni ofis ")

        viewModel.save { followedTo = it; assertTrue("old map still there when told", "Ofis" in store.files) }
        advanceUntilIdle()

        assertEquals("Yeni ofis", followedTo)
        assertTrue("Yeni ofis" in store.files)
        assertFalse("Ofis" in store.files)
    }

    @Test
    fun `a name another building already has is refused`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.renameBuilding("Depo")

        viewModel.save()
        advanceUntilIdle()

        assertEquals(MapEditorMessage.NAME_TAKEN, viewModel.state.value.message)
        assertTrue("Ofis" in store.files)
    }

    @Test
    fun `a removed connection that strands a place is pointed out`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        val link = com.appvalence.umay.coremodel.MapEdits.placeLinks(office.floors[0]).single()

        viewModel.disconnect(link)

        assertEquals(1, viewModel.state.value.unreachable.size)
    }
}
