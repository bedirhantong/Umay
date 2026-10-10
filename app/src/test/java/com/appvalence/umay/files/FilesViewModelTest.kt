package com.appvalence.umay.files

import com.appvalence.umay.common.Distribution
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.testing.FakeBuildingMapStore
import com.appvalence.umay.testing.FakeCrashReports
import com.appvalence.umay.testing.FakeFieldLogFiles
import com.appvalence.umay.testing.MainDispatcherRule
import com.appvalence.umay.testing.building
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FilesViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val store = FakeBuildingMapStore(listOf(building("Ofis")))
    private lateinit var logs: FakeFieldLogFiles
    private lateinit var crashes: FakeCrashReports

    private fun TestScope.viewModel(): FilesViewModel {
        logs = FakeFieldLogFiles(mutableListOf(folder.newFile("umay_a.jsonl"), folder.newFile("umay_b.jsonl")))
        crashes = FakeCrashReports(mutableListOf(folder.newFile("crash_1.txt")))
        val buildings = BuildingRepository({ store }, backgroundScope, main.dispatcher).apply { refresh() }
        return FilesViewModel(buildings, logs, crashes, main.dispatcher, Distribution(sharedMapPool = true)).also { viewModel ->
            backgroundScope.launch { viewModel.state.collect {} }
        }
    }

    @Test
    fun `logs and crash reports are read when the screen asks`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.refreshFiles()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(listOf("umay_a", "umay_b"), state.logs.map { it.name })
        assertEquals(listOf("crash_1"), state.crashReports.map { it.name })
        assertEquals(listOf("Ofis"), state.buildings.map { it.id })
    }

    @Test
    fun `deleting a log re-reads the list`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        viewModel.refreshFiles()
        advanceUntilIdle()

        viewModel.deleteLog(logs.files.first())
        advanceUntilIdle()

        assertEquals(listOf("umay_b"), viewModel.state.value.logs.map { it.name })
    }

    @Test
    fun `deleting every log empties the list`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.deleteAllLogs()
        advanceUntilIdle()

        assertTrue(viewModel.state.value.logs.isEmpty())
    }

    @Test
    fun `the recording switch is remembered`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.setRecordingEnabled(true)

        assertTrue(logs.isRecordingEnabled)
        assertTrue(viewModel.state.value.recordingEnabled)
    }

    @Test
    fun `exporting hands over a file to share, or says it couldn't`() = runTest(main.dispatcher) {
        val viewModel = viewModel()

        viewModel.export(building("Ofis"))
        advanceUntilIdle()
        assertEquals(FilesMessage.EXPORT_FAILED, viewModel.state.value.message)

        val exported = File(folder.root, "Ofis.umay.json")
        store.exportFile = exported
        viewModel.messageShown()
        viewModel.export(building("Ofis"))
        advanceUntilIdle()
        assertEquals(exported, viewModel.state.value.pendingExport)

        viewModel.exportHandled()
        assertEquals(null, viewModel.state.value.pendingExport)
    }

    @Test
    fun `deleting a building removes it from the list`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.deleteBuilding("Ofis")
        advanceUntilIdle()

        assertTrue(viewModel.state.value.buildings.isEmpty())
    }
}
