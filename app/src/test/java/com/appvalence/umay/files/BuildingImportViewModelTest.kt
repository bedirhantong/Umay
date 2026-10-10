package com.appvalence.umay.files

import android.net.Uri
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.MapProblem
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.testing.FakeBuildingMapStore
import com.appvalence.umay.testing.MainDispatcherRule
import com.appvalence.umay.testing.building
import com.appvalence.umay.testing.node
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildingImportViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val store = FakeBuildingMapStore()

    private fun TestScope.viewModel() = BuildingImportViewModel(
        reader = object : BuildingFileReader {
            override suspend fun read(uri: Uri): ImportRead = error("not used: tests import through import { }")
        },
        buildings = BuildingRepository({ store }, backgroundScope, main.dispatcher),
    )

    @Test
    fun `a new building is saved and reported`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.import { ImportRead.Parsed(building("Ofis")) }
        advanceUntilIdle()

        assertTrue("Ofis" in store.files)
        assertEquals(ImportMessage.Imported("Ofis", floors = 1, points = 2), viewModel.state.value.message)
    }

    @Test
    fun `a building that already exists waits for confirmation before being replaced`() = runTest(main.dispatcher) {
        store.files["Ofis"] = building("Ofis", nodes = listOf(node("x", 0.0)))
        val viewModel = viewModel()
        val incoming = building("Ofis")

        viewModel.import { ImportRead.Parsed(incoming) }
        advanceUntilIdle()
        assertEquals(incoming, viewModel.state.value.pendingOverwrite)
        assertEquals(1, store.files.getValue("Ofis").floors.single().nodes.size)

        viewModel.confirmOverwrite()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingOverwrite)
        assertEquals(incoming, store.files["Ofis"])
    }

    @Test
    fun `dismissing the overwrite keeps the local building`() = runTest(main.dispatcher) {
        val local = building("Ofis", nodes = listOf(node("x", 0.0)))
        store.files["Ofis"] = local
        val viewModel = viewModel()

        viewModel.import { ImportRead.Parsed(building("Ofis")) }
        advanceUntilIdle()
        viewModel.dismissOverwrite()
        advanceUntilIdle()

        assertNull(viewModel.state.value.pendingOverwrite)
        assertEquals(local, store.files["Ofis"])
    }

    @Test
    fun `a rejected file is reported with its reason and saves nothing`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.import { ImportRead.Rejected(ImportRejection.TooLarge) }
        advanceUntilIdle()

        assertEquals(ImportMessage.Rejected(ImportRejection.TooLarge), viewModel.state.value.message)
        assertTrue(store.files.isEmpty())
    }

    @Test
    fun `a failed write is reported`() = runTest(main.dispatcher) {
        store.failWrites = true
        val viewModel = viewModel()

        viewModel.import { ImportRead.Parsed(building("Ofis")) }
        advanceUntilIdle()

        assertEquals(ImportMessage.SaveFailed, viewModel.state.value.message)
    }

    @Test
    fun `the message goes away once shown`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.import { ImportRead.Rejected(ImportRejection.NotAMap) }
        advanceUntilIdle()

        viewModel.messageShown()

        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `parsing rejects something that isn't a map at all`() {
        assertEquals(ImportRead.Rejected(ImportRejection.NotAMap), parseBuildingMap("hello".encodeToByteArray()))
    }

    @Test
    fun `parsing rejects a map with broken structure, saying what's wrong`() {
        val broken = building("Ofis", nodes = listOf(node("a", 0.0), node("a", 1.0)))
        val result = parseBuildingMap(BuildingMapJson.encode(broken).encodeToByteArray())
        assertTrue(result is ImportRead.Rejected && (result.reason as ImportRejection.Corrupt).problems.contains(MapProblem.DuplicateNodeIds))
    }

    @Test
    fun `parsing accepts a valid map`() {
        val map = building("Ofis")
        assertEquals(ImportRead.Parsed(map), parseBuildingMap(BuildingMapJson.encode(map).encodeToByteArray()))
    }

    @Test
    fun `a map from a newer Umay asks for an update instead of calling the file broken`() {
        val newer = BuildingMapJson.encode(building("Ofis")).replaceFirst("\"formatVersion\": ${BuildingMapJson.FORMAT_VERSION}", "\"formatVersion\": 99")

        assertEquals(ImportRead.Rejected(ImportRejection.NewerFormat), parseBuildingMap(newer.encodeToByteArray()))
    }
}
