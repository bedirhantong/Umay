package com.appvalence.umay.navigation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.isCorridorBend
import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.positioning.ble.BeaconScanTargets
import com.appvalence.umay.positioning.ble.BeaconScanner
import com.appvalence.umay.positioning.fingerprint.StartPlaceFinder
import com.appvalence.umay.fieldlog.FieldLogFiles
import com.appvalence.umay.settings.UserSettings
import com.appvalence.umay.storage.BuildingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A building picked for navigation, with everything the route picker needs about it. */
data class SelectedBuilding(
    val map: BuildingMap,
    /** Every node on every floor — a route can start and end on any of them. */
    val nodes: List<MapNode>,
    val nodeFloor: Map<String, Int>,
    val startIndex: Int,
    val destinationIndex: Int,
) {
    /** Labels carry the floor ("Koridor (Kat 1)") only when there's more than one to tell apart. */
    val showFloorInLabels: Boolean get() = map.floors.size > 1
    val canBegin: Boolean get() = nodes.isNotEmpty() && startIndex != destinationIndex
    val startNode: MapNode? get() = nodes.getOrNull(startIndex)
}

/** What the beacons heard on the route picker say about where the walk starts (see [StartPlaceFinder]). */
data class StartHint(
    /** Ids of the places to list first under "Bulunduğum yer", most likely first. */
    val nearbyIds: List<String> = emptyList(),
    /** A place confident enough to ask "are you here?" about; null once answered. */
    val suggested: MapNode? = null,
)

data class NavigationUiState(
    /** Null while buildings are still being read from disk. */
    val buildings: List<BuildingMap>? = null,
    /** Null until one is picked — skipped entirely when there's only one building. */
    val selected: SelectedBuilding? = null,
)

/**
 * Picks a building and a start/destination point, then starts a walk. The walk
 * itself is deliberately not owned here — it has to outlive this screen and
 * even the activity (see [NavigationSessions]) — so [activeSession] only
 * observes it.
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val buildings: BuildingRepository,
    private val sessions: NavigationSessions,
    private val settings: UserSettings,
    private val fieldLogs: FieldLogFiles,
    capabilities: DeviceCapabilities,
    private val speaker: Speaker,
    private val savedState: SavedStateHandle,
    private val scanner: BeaconScanner,
) : ViewModel() {
    val activeSession: StateFlow<NavigationSession?> = sessions.active

    val hasStepSensors: Boolean = capabilities.hasStepSensors

    val keepScreenOn: Boolean get() = settings.keepScreenOnDuringNavigation

    val isFieldLogRecording: Boolean get() = fieldLogs.isRecordingEnabled

    val state: StateFlow<NavigationUiState> = combine(
        buildings.buildings,
        savedState.getStateFlow<String?>(KEY_BUILDING, null),
        savedState.getStateFlow(KEY_START, 0),
        savedState.getStateFlow(KEY_DESTINATION, LAST_NODE),
        ::uiState,
    ).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        // Computed the same way, so the very first frame is already right.
        uiState(buildings.buildings.value, savedState[KEY_BUILDING], savedState[KEY_START] ?: 0, savedState[KEY_DESTINATION] ?: LAST_NODE),
    )

    private val _startHint = MutableStateFlow(StartHint())
    val startHint: StateFlow<StartHint> = _startHint.asStateFlow()

    private var hintJob: Job? = null

    // Places the person said they're not at: never suggested again for this building.
    private val declinedIds = mutableSetOf<String>()

    /**
     * Listens for the selected building's beacons while the route picker is on screen,
     * to put the likely start places first and, when sure, to ask about one. Does
     * nothing for a map surveyed without beacons. [stopFindingStart] ends it.
     */
    fun findStart() {
        if (hintJob != null) return
        val selected = state.value.selected ?: return
        val finder = StartPlaceFinder(selected.nodes)
        if (!finder.canFind) return
        val beaconIds = selected.nodes.flatMap { it.fingerprint }.map { it.beaconId }.toSet()
        scanner.start(BeaconScanTargets.forBeaconIds(beaconIds))
        hintJob = viewModelScope.launch {
            scanner.beaconUpdates().collect { readings ->
                val result = finder.update(readings)
                val suggested = result.suggested?.takeIf { it.id !in declinedIds && it.id != state.value.selected?.startNode?.id }
                _startHint.update { current ->
                    // Once asked, keep asking about the same place until answered.
                    StartHint(result.nearbyIds, current.suggested ?: suggested)
                }
            }
        }
    }

    fun stopFindingStart() {
        hintJob?.cancel()
        hintJob = null
        scanner.stop()
    }

    /** "Evet, buradayım". */
    fun acceptSuggestedStart() {
        val suggested = _startHint.value.suggested ?: return
        val selected = state.value.selected ?: return
        val index = selected.nodes.indexOfFirst { it.id == suggested.id }
        if (index >= 0) setStart(index)
        declinedIds += suggested.id // already the start: no need to ask again
        _startHint.update { it.copy(suggested = null) }
    }

    /** "Hayır". */
    fun declineSuggestedStart() {
        val suggested = _startHint.value.suggested ?: return
        declinedIds += suggested.id
        _startHint.update { it.copy(suggested = null) }
    }

    fun selectBuilding(id: String) {
        savedState[KEY_BUILDING] = id
        resetRoute()
    }

    fun changeBuilding() {
        savedState[KEY_BUILDING] = null
        resetRoute()
    }

    fun setStart(index: Int) {
        savedState[KEY_START] = index
    }

    fun setDestination(index: Int) {
        savedState[KEY_DESTINATION] = index
    }

    fun begin() {
        val selected = state.value.selected ?: return
        if (!selected.canBegin) return
        // The walk runs its own scan; two at once would only compete for the radio.
        stopFindingStart()
        sessions.start(selected.map, selected.nodes[selected.startIndex].id, selected.nodes[selected.destinationIndex])
    }

    fun deleteBuilding(id: String) {
        viewModelScope.launch { buildings.delete(id) }
    }

    fun reloadBuildings() = buildings.refresh()

    fun speak(text: String) = speaker.speakNow(text)

    fun stopSpeaking() = speaker.stop()

    override fun onCleared() {
        stopFindingStart()
    }

    private fun resetRoute() {
        stopFindingStart()
        declinedIds.clear()
        _startHint.value = StartHint()
        savedState[KEY_START] = 0
        savedState[KEY_DESTINATION] = LAST_NODE
    }

    private fun uiState(all: List<BuildingMap>?, selectedId: String?, start: Int, destination: Int): NavigationUiState {
        // Skip the picker entirely when there's only one building — that's the common
        // case, and making someone choose from a list of one is pure friction.
        val map = all?.let { if (it.size == 1) it.single() else it.firstOrNull { building -> building.id == selectedId } }
        return NavigationUiState(buildings = all, selected = map?.let { selectedBuilding(it, start, destination) })
    }

    private fun selectedBuilding(map: BuildingMap, start: Int, destination: Int): SelectedBuilding {
        // Beacon nodes stay pickable (a beacon-only map has nothing else); corridor bends don't.
        val nodes = map.floors.flatMap { it.nodes }.filterNot { it.isCorridorBend }
        val lastIndex = (nodes.size - 1).coerceAtLeast(0)
        return SelectedBuilding(
            map = map,
            nodes = nodes,
            nodeFloor = map.floors.flatMap { floor -> floor.nodes.map { it.id to floor.floorIndex } }.toMap(),
            startIndex = start.coerceIn(0, lastIndex),
            // Defaults to the last node: start and destination differ from the outset.
            destinationIndex = if (destination == LAST_NODE) lastIndex else destination.coerceIn(0, lastIndex),
        )
    }

    private companion object {
        const val KEY_BUILDING = "building"
        const val KEY_START = "start"
        const val KEY_DESTINATION = "destination"
        const val LAST_NODE = -1
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
