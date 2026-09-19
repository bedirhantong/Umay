package com.appvalence.umay.cloudmap

import com.appvalence.umay.cloud.RemoteMapFailure
import com.appvalence.umay.cloud.asRemoteMapFailure
import com.appvalence.umay.common.Clock
import com.appvalence.umay.testing.FakeConnectivityMonitor
import com.appvalence.umay.testing.FakePublishedBuildings
import com.appvalence.umay.testing.FakeRemoteRepository
import com.appvalence.umay.testing.MainDispatcherRule
import com.appvalence.umay.testing.building
import com.appvalence.umay.testing.summary
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
class SharedMapPoolTest {
    @get:Rule
    val main = MainDispatcherRule()

    private val remote = FakeRemoteRepository()
    private val published = FakePublishedBuildings()
    private var now = 1_000_000L

    private fun TestScope.pool() = SharedMapPool(
        scope = backgroundScope,
        remoteRepository = { remote },
        connectivity = FakeConnectivityMonitor(),
        published = published,
        clock = Clock { now },
        io = main.dispatcher,
    )

    @Test
    fun `the listing is fetched once and then served from cache until it's stale`() = runTest(main.dispatcher) {
        remote.listing = Result.success(listOf(summary("x")))
        val pool = pool()

        pool.refreshIfStale()
        advanceUntilIdle()
        pool.refreshIfStale()
        advanceUntilIdle()
        assertEquals(1, remote.listCalls)
        assertEquals(listOf(summary("x")), pool.listing.value.summaries)

        now += SharedMapPool.LISTING_MAX_AGE_MS + 1
        pool.refreshIfStale()
        advanceUntilIdle()
        assertEquals(2, remote.listCalls)
    }

    @Test
    fun `a failed listing is kept as a typed failure and retried on the next visit`() = runTest(main.dispatcher) {
        remote.listing = Result.failure(com.appvalence.umay.cloud.RemoteMapException(RemoteMapFailure.NoConnection))
        val pool = pool()

        pool.refreshIfStale()
        advanceUntilIdle()
        assertEquals(RemoteMapFailure.NoConnection, pool.listing.value.failure)
        assertFalse(pool.listing.value.loading)

        remote.listing = Result.success(emptyList())
        pool.refreshIfStale()
        advanceUntilIdle()
        assertNull(pool.listing.value.failure)
        assertEquals(2, remote.listCalls)
    }

    @Test
    fun `publishing records the publication and refreshes the listing`() = runTest(main.dispatcher) {
        val pool = pool()

        val result = pool.publish(building("Ofis"))
        advanceUntilIdle()

        assertTrue(result.isSuccess)
        assertEquals("remote-Ofis", published.remoteIdFor("Ofis"))
        assertEquals(setOf("Ofis"), pool.publishedIds.value)
        assertEquals(1, remote.listCalls)
    }

    @Test
    fun `a refresh asked for mid-refresh still happens afterwards`() = runTest(main.dispatcher) {
        val pool = pool()

        pool.refresh()
        pool.refresh()
        advanceUntilIdle()

        assertEquals(2, remote.listCalls)
    }

    @Test
    fun `unpublishing uses the remote id this device published under`() = runTest(main.dispatcher) {
        published.markPublished("Ofis", "remote-Ofis")
        val pool = pool()

        pool.unpublish("Ofis")
        advanceUntilIdle()

        assertEquals(listOf("remote-Ofis"), remote.unpublished)
        assertNull(published.remoteIdFor("Ofis"))
        assertTrue(pool.publishedIds.value.isEmpty())
    }

    @Test
    fun `a rejected publish leaves no local record`() = runTest(main.dispatcher) {
        remote.failure = RemoteMapFailure.NotOwner
        val pool = pool()

        val result = pool.publish(building("Ofis"))

        assertEquals(RemoteMapFailure.NotOwner, result.exceptionOrNull()?.asRemoteMapFailure())
        assertNull(published.remoteIdFor("Ofis"))
    }

    @Test
    fun `connectivity follows the monitor while someone is watching`() = runTest(main.dispatcher) {
        val connectivity = FakeConnectivityMonitor(online = false)
        val pool = SharedMapPool(backgroundScope, { remote }, connectivity, published, Clock { now }, main.dispatcher)
        backgroundScope.launch { pool.isOnline.collect {} }
        advanceUntilIdle()
        assertFalse(pool.isOnline.value)

        connectivity.isOnline.value = true
        advanceUntilIdle()

        assertTrue(pool.isOnline.value)
    }
}
