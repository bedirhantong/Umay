package com.appvalence.umay.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.positioning.ble.BeaconHealthTracker
import com.appvalence.umay.positioning.ble.BeaconScanner
import com.appvalence.umay.positioning.ble.BeaconStatus
import com.appvalence.umay.positioning.ble.BleScanState
import com.appvalence.umay.positioning.ble.CoverageCheck
import com.appvalence.umay.positioning.ble.WeakCoverage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BeaconHealthState(
    val buildingId: String? = null,
    /** The beacons the map relies on: unheard first, then weakest battery first. */
    val statuses: List<BeaconStatus> = emptyList(),
    /** Beacons of the building's family the map doesn't know (added since the survey). */
    val unknown: List<BeaconStatus> = emptyList(),
    /** Per floor; null for a floor surveyed before signatures were recorded along the path. */
    val coverage: Map<Int, List<WeakCoverage>?> = emptyMap(),
    /** Names the map gave beacon nodes ("Kat 1 · Beacon 3"), by beacon id. */
    val beaconNames: Map<String, String> = emptyMap(),
    val scanState: BleScanState = BleScanState.Idle,
    val scanSeconds: Int = 0,
)

/**
 * A check-up of a building's beacons while someone walks past them (see
 * [BeaconHealthTracker]), and where its map says another beacon is needed
 * ([CoverageCheck]). The scan is unfiltered — battery frames don't match an
 * iBeacon filter — so it runs only while the screen is shown.
 */
@HiltViewModel
class BeaconHealthViewModel @Inject constructor(private val scanner: BeaconScanner) : ViewModel() {
    private val _state = MutableStateFlow(BeaconHealthState())
    val state: StateFlow<BeaconHealthState> = _state.asStateFlow()

    private var job: Job? = null

    fun start(map: BuildingMap) {
        if (job != null && _state.value.buildingId == map.id) return
        stop()
        val nodes = map.floors.flatMap { it.nodes }
        val expected = (
            nodes.flatMap { it.fingerprint } + map.floors.flatMap { it.radioMap }.flatMap { it.fingerprint }
            ).map { it.beaconId }.toSet() + nodes.filter { it.type == NodeType.BLE_ANCHOR }.map { it.id }
        val tracker = BeaconHealthTracker(expected)
        _state.value = BeaconHealthState(
            buildingId = map.id,
            statuses = tracker.statuses(),
            coverage = map.floors.associate { it.floorIndex to CoverageCheck.weakSpots(it) },
            beaconNames = nodes.filter { it.type == NodeType.BLE_ANCHOR && it.name != it.id }.associate { it.id to it.name },
        )
        scanner.start(null)
        job = viewModelScope.launch {
            launch { scanner.scanState().collect { scanState -> _state.update { it.copy(scanState = scanState) } } }
            launch { scanner.rawReadingUpdates().collect(tracker::onReading) }
            var seconds = 0
            while (true) {
                delay(REFRESH_MS)
                seconds++
                _state.update { it.copy(statuses = tracker.statuses(), unknown = tracker.unknownBeacons(), scanSeconds = seconds) }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scanner.stop()
    }

    override fun onCleared() = stop()

    private companion object {
        const val REFRESH_MS = 1_000L
    }
}
