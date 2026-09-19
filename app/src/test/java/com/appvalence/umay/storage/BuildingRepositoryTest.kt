package com.appvalence.umay.storage

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.testing.FakeBuildingMapStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BuildingRepositoryTest {
    private fun building(id: String) = BuildingMap(id = id, floors = emptyList())

    private fun TestScope.repositoryOver(store: FakeBuildingMapStore) =
        BuildingRepository({ store }, this, StandardTestDispatcher(testScheduler))

    @Test
    fun `nothing is known before the first load finishes`() = runTest {
        val repository = repositoryOver(FakeBuildingMapStore(listOf(building("A"))))
        assertNull(repository.buildings.value)

        repository.refresh()
        advanceUntilIdle()

        assertEquals(listOf("A"), repository.buildings.value?.map { it.id })
    }

    @Test
    fun `saving adds the building in order without re-reading every other one`() = runTest {
        val store = FakeBuildingMapStore(listOf(building("B")))
        val repository = repositoryOver(store)
        repository.refresh()
        advanceUntilIdle()

        assertTrue(repository.save(building("A")))
        assertTrue(repository.save(building("C")))

        assertEquals(listOf("A", "B", "C"), repository.buildings.value?.map { it.id })
        assertEquals(1, store.listCalls)
    }

    @Test
    fun `saving under an existing id replaces that building`() = runTest {
        val repository = repositoryOver(FakeBuildingMapStore(listOf(building("A"))))
        repository.refresh()
        advanceUntilIdle()

        val updated = BuildingMap(id = "A", floors = emptyList(), surveyStepLengthMeters = 0.7)
        repository.save(updated)

        assertEquals(listOf(updated), repository.buildings.value)
    }

    @Test
    fun `a failed write leaves the list untouched`() = runTest {
        val store = FakeBuildingMapStore(listOf(building("A"))).apply { failWrites = true }
        val repository = repositoryOver(store)
        repository.refresh()
        advanceUntilIdle()

        assertFalse(repository.save(building("B")))

        assertEquals(listOf("A"), repository.buildings.value?.map { it.id })
    }

    @Test
    fun `deleting removes just that building`() = runTest {
        val repository = repositoryOver(FakeBuildingMapStore(listOf(building("A"), building("B"))))
        repository.refresh()
        advanceUntilIdle()

        assertTrue(repository.delete("A"))

        assertEquals(listOf("B"), repository.buildings.value?.map { it.id })
    }

    @Test
    fun `a save made before the first load finishes isn't lost when it does`() = runTest {
        val repository = repositoryOver(FakeBuildingMapStore(listOf(building("A"))))

        repository.refresh()
        repository.save(building("B"))
        advanceUntilIdle()

        assertEquals(listOf("A", "B"), repository.buildings.value?.map { it.id })
    }
}
