package com.appvalence.umay.files

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.appvalence.umay.R
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.coremodel.MapProblem

// Other apps label a received .json inconsistently (WhatsApp/Downloads often say
// octet-stream), so accept the common variants rather than hide the right file.
private val IMPORT_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/**
 * Opens the system file picker for a building map and hands the choice to the
 * shared [BuildingImportViewModel]; returns the action that opens the picker.
 */
@Composable
fun rememberMapFilePicker(importViewModel: BuildingImportViewModel = hiltViewModel()): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) importViewModel.importFrom(uri)
    }
    return { launcher.launch(IMPORT_MIME_TYPES) }
}

/** The overwrite-confirmation and result dialogs for every import; shown once, at the activity level. */
@Composable
fun BuildingImportDialogs(viewModel: BuildingImportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    state.pendingOverwrite?.let { map ->
        AlertDialog(
            onDismissRequest = viewModel::dismissOverwrite,
            title = { Text(stringResource(R.string.import_exists_title, map.id)) },
            text = { Text(stringResource(R.string.import_exists_body)) },
            confirmButton = { TextButton(onClick = viewModel::confirmOverwrite) { Text(stringResource(R.string.import_overwrite)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissOverwrite) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    state.message?.let { message ->
        val (title, body) = importMessageText(message)
        AlertDialog(
            onDismissRequest = viewModel::messageShown,
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = viewModel::messageShown) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

@Composable
private fun importMessageText(message: ImportMessage): Pair<String, String> = when (message) {
    is ImportMessage.Imported ->
        stringResource(R.string.import_success_title) to
            stringResource(R.string.import_success_body, message.buildingName, buildingSummary(message.floors, message.points))
    ImportMessage.SaveFailed -> stringResource(R.string.import_save_failed_title) to stringResource(R.string.import_save_failed_body)
    is ImportMessage.Rejected -> stringResource(R.string.import_failed_title) to rejectionText(message.reason)
}

@Composable
private fun rejectionText(reason: ImportRejection): String {
    val resources = LocalResources.current
    return when (reason) {
        ImportRejection.Unreadable -> stringResource(R.string.import_unreadable)
        ImportRejection.TooLarge -> stringResource(R.string.import_too_large)
        ImportRejection.NotAMap -> stringResource(R.string.import_not_a_map)
        is ImportRejection.Corrupt -> stringResource(
            R.string.import_corrupt,
            reason.problems.joinToString("; ") { problem ->
                when (problem) {
                    MapProblem.BlankName -> resources.getString(R.string.import_problem_blank_name)
                    MapProblem.NoNodes -> resources.getString(R.string.import_problem_no_nodes)
                    MapProblem.DuplicateNodeIds -> resources.getString(R.string.import_problem_duplicate_nodes)
                    MapProblem.DuplicateFloors -> resources.getString(R.string.import_problem_duplicate_floors)
                    is MapProblem.DanglingEdges -> resources.getQuantityString(R.plurals.import_problem_dangling_edges, problem.count, problem.count)
                    is MapProblem.DanglingTransitions ->
                        resources.getQuantityString(R.plurals.import_problem_dangling_transitions, problem.count, problem.count)
                }
            },
        )
    }
}
