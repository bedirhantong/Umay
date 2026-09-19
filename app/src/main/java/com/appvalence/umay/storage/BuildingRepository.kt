package com.appvalence.umay.storage

import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.di.ApplicationScope
import com.appvalence.umay.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for which buildings are on this device, shared by
 * every screen.
 *
 * The disk is read once, off the main thread, and every change made through
 * here (a survey saved, a map imported or downloaded, one deleted) updates
 * [buildings] in place without re-reading the others, so every screen showing
 * it stays current on its own.
 */
@Singleton
class BuildingRepository @Inject constructor(
    // Lazy: creating the store touches the disk too (directories, one-time
    // migrations), so it's only created on first use, always inside withContext(io).
    private val store: dagger.Lazy<BuildingMapStore>,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    // Null until the first load finishes — "not loaded yet" must not look like "no
    // buildings", or a screen reader would announce an empty state that isn't true.
    private val _buildings = MutableStateFlow<List<BuildingMap>?>(null)
    val buildings: StateFlow<List<BuildingMap>?> = _buildings.asStateFlow()

    // Each disk operation *and* the list update that follows it happen under this lock,
    // so a reload can never land after — and overwrite — a save or delete that started later.
    private val lock = Mutex()

    /** Reloads everything from disk; also the initial load. */
    fun refresh() {
        scope.launch {
            lock.withLock { _buildings.value = withContext(io) { store.get().listBuildings() } }
        }
    }

    suspend fun save(map: BuildingMap): Boolean = lock.withLock {
        val saved = withContext(io) { store.get().save(map) }
        if (saved) _buildings.update { current -> ((current ?: emptyList()).filterNot { it.id == map.id } + map).sortedBy { it.id } }
        saved
    }

    suspend fun delete(buildingId: String): Boolean = lock.withLock {
        val deleted = withContext(io) { store.get().delete(buildingId) }
        if (deleted) _buildings.update { current -> current?.filterNot { it.id == buildingId } }
        deleted
    }

    suspend fun exists(buildingId: String): Boolean = lock.withLock { withContext(io) { store.get().exists(buildingId) } }

    suspend fun exportCopy(map: BuildingMap): File? = withContext(io) { store.get().exportCopy(map) }
}
