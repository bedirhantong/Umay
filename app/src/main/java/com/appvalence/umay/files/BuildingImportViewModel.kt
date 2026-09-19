package com.appvalence.umay.files

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.storage.BuildingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The outcome of an import, for the dialog that reports it. */
sealed interface ImportMessage {
    data class Imported(val buildingName: String, val floors: Int, val points: Int) : ImportMessage
    data object SaveFailed : ImportMessage
    data class Rejected(val reason: ImportRejection) : ImportMessage
}

data class ImportUiState(
    /** A valid map whose name already exists here — waiting for "replace it?". */
    val pendingOverwrite: BuildingMap? = null,
    val message: ImportMessage? = null,
)

/**
 * Adds a building map exported from another device to this one — picked in the
 * system file picker or handed over by another app (a `.umay.json` tapped in
 * WhatsApp or a file manager). Every entry point goes through the same
 * validation and the same dialogs.
 *
 * Scoped to the activity and shared by every screen that imports, so its
 * dialogs are shown in one place (see [BuildingImportDialogs]) whichever
 * screen started the import.
 *
 * Importing a building whose name already exists asks before replacing it — a
 * re-survey on another phone is a legitimate reason to overwrite, but losing a
 * local survey by accident is not something to do silently.
 */
@HiltViewModel
class BuildingImportViewModel @Inject constructor(
    private val reader: BuildingFileReader,
    private val buildings: BuildingRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(ImportUiState())
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    fun importFrom(uri: Uri) = import { reader.read(uri) }

    internal fun import(read: suspend () -> ImportRead) {
        viewModelScope.launch {
            when (val result = read()) {
                is ImportRead.Rejected -> _state.update { it.copy(message = ImportMessage.Rejected(result.reason)) }
                is ImportRead.Parsed ->
                    if (buildings.exists(result.map.id)) {
                        _state.update { it.copy(pendingOverwrite = result.map) }
                    } else {
                        save(result.map)
                    }
            }
        }
    }

    fun confirmOverwrite() {
        val map = _state.value.pendingOverwrite ?: return
        _state.update { it.copy(pendingOverwrite = null) }
        viewModelScope.launch { save(map) }
    }

    fun dismissOverwrite() = _state.update { it.copy(pendingOverwrite = null) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private suspend fun save(map: BuildingMap) {
        val message = if (buildings.save(map)) {
            ImportMessage.Imported(map.id, map.floors.size, map.floors.sumOf { it.nodes.size })
        } else {
            ImportMessage.SaveFailed
        }
        _state.update { it.copy(message = message) }
    }
}
