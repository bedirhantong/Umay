package com.appvalence.umay.cloudmap

import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.common.Clock
import com.appvalence.umay.storage.BuildingRepository
import com.appvalence.umay.testing.FakeBuildingMapStore
import com.appvalence.umay.testing.FakeConnectivityMonitor
import com.appvalence.umay.testing.FakePublishedBuildings
import com.appvalence.umay.testing.FakeRemoteRepository
import com.appvalence.umay.testing.MainDispatcherRule
import com.appvalence.umay.testing.building
import com.appvalence.umay.testing.node
import com.appvalence.umay.testing.summary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedMapPoolViewModelTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val remote = FakeRemoteRepository()
    private val store = FakeBuildingMapStore()
    private val connectivity = FakeConnectivityMonitor(online = true)

    private fun TestScope.viewModel(): SharedMapPoolViewModel {
        val pool = SharedMapPool(backgroundScope, { remote }, connectivity, FakePublishedBuildings(), Clock { 0L }, main.dispatcher)
        return SharedMapPoolViewModel(pool, BuildingRepository({ store }, backgroundScope, main.dispatcher)).also { viewModel ->
            backgroundScope.launch { viewModel.state.collect {} }
        }
    }

    @Test
    fun `coming online loads the listing`() = runTest(main.dispatcher) {
        remote.listing = Result.success(listOf(summary("x")))
        val viewModel = viewModel()
        advanceUntilIdle()

        assertEquals(listOf(summary("x")), viewModel.state.value.listing.summaries)
        assertTrue(viewModel.state.value.isOnline)
    }

    @Test
    fun `publishing asks first, then reports success and marks the building published`() = runTest(main.dispatcher) {
        val viewModel = viewModel()
        val ofis = building("Ofis")

        viewModel.requestPublish(ofis)
        assertEquals(ofis, viewModel.state.value.dialogs.pendingPublish)

        viewModel.confirmPublish()
        advanceUntilIdle()

        assertNull(viewModel.state.value.dialogs.pendingPublish)
        assertEquals(PoolMessage.Published("Ofis"), viewModel.state.value.dialogs.message)
        assertEquals(setOf("Ofis"), viewModel.state.value.publishedIds)
    }

    @Test
    fun `a failed publish reports why`() = runTest(main.dispatcher) {
        remote.failure = RemoteMapFailure.NoConnection
        val viewModel = viewModel()

        viewModel.requestPublish(building("Ofis"))
        viewModel.confirmPublish()
        advanceUntilIdle()

        assertEquals(PoolMessage.Failed(RemoteMapFailure.NoConnection), viewModel.state.value.dialogs.message)
    }

    @Test
    fun `a downloaded building that's new here is saved straight away`() = runTest(main.dispatcher) {
        remote.maps["x"] = building("Kütüphane")
        val viewModel = viewModel()

        viewModel.download(summary("x"))
        advanceUntilIdle()

        assertTrue("Kütüphane" in store.files)
        assertEquals(PoolMessage.Downloaded("Kütüphane"), viewModel.state.value.dialogs.message)
    }

    @Test
    fun `a downloaded building that already exists here waits for confirmation`() = runTest(main.dispatcher) {
        val local = building("Kütüphane", nodes = listOf(node("z", 0.0)))
        store.files["Kütüphane"] = local
        val incoming = building("Kütüphane")
        remote.maps["x"] = incoming
        val viewModel = viewModel()

        viewModel.download(summary("x"))
        advanceUntilIdle()
        assertEquals(incoming, viewModel.state.value.dialogs.pendingOverwrite)
        assertEquals(local, store.files["Kütüphane"])

        viewModel.confirmOverwrite()
        advanceUntilIdle()
        assertEquals(incoming, store.files["Kütüphane"])
        assertEquals(PoolMessage.Downloaded("Kütüphane"), viewModel.state.value.dialogs.message)
    }

    @Test
    fun `a downloaded map that fails validation is refused`() = runTest(main.dispatcher) {
        remote.maps["x"] = building("Bozuk", nodes = listOf(node("a", 0.0), node("a", 1.0)))
        val viewModel = viewModel()

        viewModel.download(summary("x"))
        advanceUntilIdle()

        assertEquals(PoolMessage.MapCorrupt, viewModel.state.value.dialogs.message)
        assertTrue(store.files.isEmpty())
    }
}
