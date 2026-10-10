package com.appvalence.umay.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.MapDoors
import com.appvalence.umay.coremodel.MapEdits
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.storage.BuildingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class MapEditorMessage { SAVED, SAVE_FAILED, NAME_TAKEN, NAME_BLANK }

data class MapEditorState(
    /** The map as it's saved on the phone; null before [MapEditorViewModel.open]. */
    val original: BuildingMap? = null,
    /** The map with the edits made so far. */
    val map: BuildingMap? = null,
    val undoCount: Int = 0,
    val saving: Boolean = false,
    val message: MapEditorMessage? = null,
) {
    val hasChanges: Boolean get() = map != original

    /** Places the edits have left with no way to get to them (see [MapEdits.unreachablePlaces]). */
    val unreachable: List<MapNode> get() = map?.let(MapEdits::unreachablePlaces).orEmpty()
}

/**
 * Corrects a saved map without walking the building again (see [MapEdits]).
 * Edits apply to a working copy, each one undoable; nothing reaches the phone's
 * saved maps until [save].
 */
@HiltViewModel
class MapEditorViewModel @Inject constructor(private val buildings: BuildingRepository) : ViewModel() {
    private val _state = MutableStateFlow(MapEditorState())
    val state: StateFlow<MapEditorState> = _state.asStateFlow()

    private val history = ArrayDeque<BuildingMap>()

    /** Starts editing [map], unless it's already the one being edited (a rotation, say). */
    fun open(map: BuildingMap) {
        if (_state.value.original?.id == map.id) return
        history.clear()
        _state.value = MapEditorState(original = map, map = map)
    }

    /** A place's name and type at once: one change, undone in one go. */
    fun updatePlace(nodeId: String, name: String, type: NodeType) = edit { MapEdits.setType(MapEdits.rename(it, nodeId, name), nodeId, type) }

    fun deletePlace(nodeId: String) = edit { MapEdits.deletePlace(it, nodeId) }

    fun disconnect(link: MapEdits.PlaceLink) = edit { MapEdits.disconnect(it, link) }

    fun connect(a: String, b: String) = edit { MapEdits.connect(it, a, b) }

    fun eraseWalls(floorIndex: Int, x: Double, y: Double) = edit { MapEdits.eraseWalls(it, floorIndex, x, y, ERASE_RADIUS_METERS) }

    fun clearWalls(floorIndex: Int) = edit { MapEdits.clearWalls(it, floorIndex) }

    /** The building's name is also its key on the phone; a clash is caught on [save]. */
    fun renameBuilding(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _state.update { it.copy(message = MapEditorMessage.NAME_BLANK) }
            return
        }
        edit { it.copy(id = trimmed) }
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        _state.update { it.copy(map = previous, undoCount = history.size, message = null) }
    }

    /**
     * [onRenamed] runs as soon as a renamed building is saved under its new name,
     * before the old name is removed — so whatever shows the building can follow it.
     */
    fun save(onRenamed: (String) -> Unit = {}) {
        val current = _state.value
        val original = current.original ?: return
        // A place made (or unmade) a door gets (or loses) its gap in the walls.
        val map = current.map?.let(MapDoors::apply) ?: return
        if (current.saving || !current.hasChanges) return
        _state.update { it.copy(saving = true, message = null) }
        viewModelScope.launch {
            val renamed = map.id != original.id
            val message = when {
                // Edits never produce one, but a map that fails these checks would be unusable.
                BuildingMapJson.problems(map).isNotEmpty() -> MapEditorMessage.SAVE_FAILED
                renamed && buildings.exists(map.id) -> MapEditorMessage.NAME_TAKEN
                !buildings.save(map) -> MapEditorMessage.SAVE_FAILED
                else -> {
                    if (renamed) {
                        onRenamed(map.id)
                        buildings.delete(original.id)
                    }
                    history.clear()
                    MapEditorMessage.SAVED
                }
            }
            _state.update {
                if (message == MapEditorMessage.SAVED) {
                    MapEditorState(original = map, map = map, message = message)
                } else {
                    it.copy(saving = false, message = message)
                }
            }
        }
    }

    fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    /** Forgets the edit in progress (leaving without saving). */
    fun close() {
        history.clear()
        _state.value = MapEditorState()
    }

    private fun edit(change: (BuildingMap) -> BuildingMap) {
        val current = _state.value.map ?: return
        val changed = change(current)
        if (changed == current) return
        history.addLast(current)
        if (history.size > MAX_UNDO) history.removeFirst()
        _state.update { it.copy(map = changed, undoCount = history.size, message = null) }
    }

    companion object {
        /** About a person's width: enough to wipe out someone who walked past the camera. */
        const val ERASE_RADIUS_METERS = 0.5
        const val MAX_UNDO = 50
    }
}
