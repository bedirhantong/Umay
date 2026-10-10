package com.appvalence.umay.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.common.StoredFile
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.common.Distribution
import com.appvalence.umay.di.IoDispatcher
import com.appvalence.umay.diagnostics.CrashReports
import com.appvalence.umay.fieldlog.FieldLogFiles
import com.appvalence.umay.storage.BuildingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Something went wrong handing a file to another app. */
enum class FilesMessage { EXPORT_FAILED, LOGS_SHARE_FAILED, LOG_SHARE_FAILED, CRASH_SHARE_FAILED }

data class FilesUiState(
    val buildings: List<BuildingMap> = emptyList(),
    val logs: List<StoredFile> = emptyList(),
    val crashReports: List<StoredFile> = emptyList(),
    val recordingEnabled: Boolean = false,
    /** An exported copy of a building, ready for the share sheet. */
    val pendingExport: File? = null,
    val message: FilesMessage? = null,
)

/**
 * Everything that moves data on or off this device: building maps (export to
 * share a survey with another phone) and field-test logs (turn recording on,
 * share the logs for offline calibration, clean them up). All file access
 * happens off the main thread.
 */
@HiltViewModel
class FilesViewModel @Inject constructor(
    private val buildings: BuildingRepository,
    private val fieldLogs: FieldLogFiles,
    private val crashReports: CrashReports,
    @param:IoDispatcher private val io: CoroutineDispatcher,
    distribution: Distribution,
) : ViewModel() {
    /** False in the institution build, which has no internet to reach the pool with. */
    val showsSharedMapPool: Boolean = distribution.sharedMapPool

    private val local = MutableStateFlow(FilesUiState(recordingEnabled = fieldLogs.isRecordingEnabled))

    val state: StateFlow<FilesUiState> = combine(buildings.buildings, local) { all, current -> current.copy(buildings = all.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), local.value.copy(buildings = buildings.buildings.value.orEmpty()))

    /** Logs and crash reports appear from outside this screen (a walk, a crash), so they're re-read each time it's shown. */
    fun refreshFiles() {
        viewModelScope.launch {
            val logs = withContext(io) { StoredFile.of(fieldLogs.listLogs()) }
            val crashes = withContext(io) { StoredFile.of(crashReports.list()) }
            local.update { it.copy(logs = logs, crashReports = crashes) }
        }
    }

    fun setRecordingEnabled(enabled: Boolean) {
        fieldLogs.isRecordingEnabled = enabled
        local.update { it.copy(recordingEnabled = enabled) }
    }

    fun deleteBuilding(id: String) {
        viewModelScope.launch { buildings.delete(id) }
    }

    fun deleteLog(file: File) = deleteThenRefresh { fieldLogs.delete(file) }

    fun deleteAllLogs() = deleteThenRefresh { fieldLogs.deleteAll() }

    fun deleteCrashReport(file: File) = deleteThenRefresh { crashReports.delete(file) }

    fun export(building: BuildingMap) {
        viewModelScope.launch {
            val copy = buildings.exportCopy(building)
            local.update { if (copy == null) it.copy(message = FilesMessage.EXPORT_FAILED) else it.copy(pendingExport = copy) }
        }
    }

    fun exportHandled() = local.update { it.copy(pendingExport = null) }

    fun showMessage(message: FilesMessage) = local.update { it.copy(message = message) }

    fun messageShown() = local.update { it.copy(message = null) }

    private fun deleteThenRefresh(delete: () -> Unit) {
        viewModelScope.launch {
            withContext(io) { delete() }
            refreshFiles()
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
