package com.appvalence.umay.files

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.appvalence.umay.R
import com.appvalence.umay.appGraph
import com.appvalence.umay.common.buildingSummary
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.MapProblem
import com.appvalence.umay.storage.BuildingMapStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

private sealed interface ImportRead {
    data class Parsed(val map: BuildingMap) : ImportRead
    data class Rejected(val reason: String) : ImportRead
}

// Generous for a surveyed building (even a large multi-floor one is well under 1 MB),
// but small enough that picking the wrong file — a video, say — fails fast instead of
// trying to hold it all in memory.
private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

// Other apps label a received .json inconsistently (WhatsApp/Downloads often say
// octet-stream), so accept the common variants rather than hide the right file.
private val IMPORT_MIME_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")

/**
 * Adds a building map exported from another device (see
 * [BuildingMapStorage.exportCopy]) to this one — picked in the system file
 * picker ([pickFile]) or handed over by another app, e.g. a `.umay.json`
 * tapped in WhatsApp or a file manager ([importFrom]). Both go through the
 * same validation and dialogs.
 */
class BuildingImporter internal constructor(
    val pickFile: () -> Unit,
    val importFrom: (Uri) -> Unit,
)

/**
 * The validation-failure, overwrite-confirmation and success dialogs are
 * rendered wherever this is called from.
 *
 * Importing a building whose name already exists here asks before replacing
 * it — a re-survey on another phone is a legitimate reason to overwrite, but
 * losing a local survey by accident is not something to do silently.
 */
@Composable
fun rememberBuildingImporter(): BuildingImporter {
    val context = LocalContext.current
    val buildings = context.appGraph.buildings
    val scope = rememberCoroutineScope()
    var pendingOverwrite by remember { mutableStateOf<BuildingMap?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun save(map: BuildingMap) {
        scope.launch {
            message = if (buildings.save(map)) {
                context.getString(R.string.import_success_title) to
                    context.getString(R.string.import_success_body, map.id, context.buildingSummary(map.floors.size, map.floors.sumOf { it.nodes.size }))
            } else {
                context.getString(R.string.import_save_failed_title) to context.getString(R.string.import_save_failed_body)
            }
        }
    }

    fun importFrom(uri: Uri) {
        scope.launch {
            when (val read = readBuildingMap(context, uri)) {
                is ImportRead.Rejected -> message = context.getString(R.string.import_failed_title) to read.reason
                is ImportRead.Parsed ->
                    if (buildings.exists(read.map.id)) pendingOverwrite = read.map else save(read.map)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    pendingOverwrite?.let { map ->
        AlertDialog(
            onDismissRequest = { pendingOverwrite = null },
            title = { Text(stringResource(R.string.import_exists_title, map.id)) },
            text = { Text(stringResource(R.string.import_exists_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwrite = null
                    save(map)
                }) { Text(stringResource(R.string.import_overwrite)) }
            },
            dismissButton = { TextButton(onClick = { pendingOverwrite = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    message?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.common_ok)) } },
        )
    }

    return remember(launcher) { BuildingImporter(pickFile = { launcher.launch(IMPORT_MIME_TYPES) }, importFrom = ::importFrom) }
}

private suspend fun readBuildingMap(context: Context, uri: Uri): ImportRead = withContext(Dispatchers.IO) {
    val bytes = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_IMPORT_BYTES) return@runCatching null
            }
            out.toByteArray()
        }
    }.getOrElse { return@withContext ImportRead.Rejected(context.getString(R.string.import_unreadable)) }
        ?: return@withContext ImportRead.Rejected(context.getString(R.string.import_too_large))

    val map = runCatching { BuildingMapJson.decode(bytes.decodeToString()) }.getOrNull()
        ?: return@withContext ImportRead.Rejected(context.getString(R.string.import_not_a_map))

    val problems = BuildingMapJson.problems(map)
    if (problems.isNotEmpty()) {
        val details = problems.joinToString("; ") { problem ->
            when (problem) {
                MapProblem.BlankName -> context.getString(R.string.import_problem_blank_name)
                MapProblem.NoNodes -> context.getString(R.string.import_problem_no_nodes)
                MapProblem.DuplicateNodeIds -> context.getString(R.string.import_problem_duplicate_nodes)
                MapProblem.DuplicateFloors -> context.getString(R.string.import_problem_duplicate_floors)
                is MapProblem.DanglingEdges -> context.resources.getQuantityString(R.plurals.import_problem_dangling_edges, problem.count, problem.count)
                is MapProblem.DanglingTransitions ->
                    context.resources.getQuantityString(R.plurals.import_problem_dangling_transitions, problem.count, problem.count)
            }
        }
        return@withContext ImportRead.Rejected(context.getString(R.string.import_corrupt, details))
    }
    ImportRead.Parsed(map)
}
