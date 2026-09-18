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
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
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
 * Lets someone pick a building map file exported from another device (see
 * [BuildingMapStorage.exportCopy]) and adds it to this device's buildings.
 * Returns the action that opens the file picker; the validation-failure,
 * overwrite-confirmation and success dialogs are rendered wherever this is
 * called from.
 *
 * Importing a building whose name already exists here asks before replacing
 * it — a re-survey on another phone is a legitimate reason to overwrite, but
 * losing a local survey by accident is not something to do silently.
 */
@Composable
fun rememberBuildingImporter(onImported: (BuildingMap) -> Unit): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pendingOverwrite by remember { mutableStateOf<BuildingMap?>(null) }
    var message by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun save(map: BuildingMap) {
        message = if (BuildingMapStorage(context).save(map)) {
            onImported(map)
            "İçe aktarıldı" to "'${map.id}' haritası eklendi (${map.floors.size} kat, ${map.floors.sumOf { it.nodes.size }} nokta)."
        } else {
            "Kaydedilemedi" to "Harita cihaza yazılamadı. Depolama alanı dolu olabilir."
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val read = readBuildingMap(context, uri)) {
                is ImportRead.Rejected -> message = "İçe aktarılamadı" to read.reason
                is ImportRead.Parsed ->
                    if (BuildingMapStorage(context).exists(read.map.id)) pendingOverwrite = read.map else save(read.map)
            }
        }
    }

    pendingOverwrite?.let { map ->
        AlertDialog(
            onDismissRequest = { pendingOverwrite = null },
            title = { Text("'${map.id}' zaten var") },
            text = { Text("Bu cihazda aynı adla bir bina var. İçe aktarılan harita onun yerine geçsin mi? Bu işlem geri alınamaz.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwrite = null
                    save(map)
                }) { Text("Üzerine yaz") }
            },
            dismissButton = { TextButton(onClick = { pendingOverwrite = null }) { Text("Vazgeç") } },
        )
    }

    message?.let { (title, body) ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("Tamam") } },
        )
    }

    return { launcher.launch(IMPORT_MIME_TYPES) }
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
    }.getOrElse { return@withContext ImportRead.Rejected("Dosya okunamadı.") }
        ?: return@withContext ImportRead.Rejected("Dosya okunamadı ya da bir harita dosyası için fazla büyük.")

    val map = runCatching { BuildingMapJson.decode(bytes.decodeToString()) }.getOrNull()
        ?: return@withContext ImportRead.Rejected("Bu dosya bir Umay haritası değil.")

    val problems = BuildingMapJson.problems(map)
    if (problems.isNotEmpty()) {
        return@withContext ImportRead.Rejected("Harita bozuk görünüyor: ${problems.joinToString("; ")}.")
    }
    ImportRead.Parsed(map)
}
