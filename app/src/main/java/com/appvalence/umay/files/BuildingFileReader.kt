package com.appvalence.umay.files

import android.content.Context
import android.net.Uri
import com.appvalence.umay.coremodel.BuildingMap
import com.appvalence.umay.coremodel.BuildingMapJson
import com.appvalence.umay.coremodel.MapProblem
import com.appvalence.umay.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** Why a picked or shared file couldn't be imported as a building map. */
sealed interface ImportRejection {
    data object Unreadable : ImportRejection
    data object TooLarge : ImportRejection
    data object NotAMap : ImportRejection
    data class Corrupt(val problems: List<MapProblem>) : ImportRejection
}

sealed interface ImportRead {
    data class Parsed(val map: BuildingMap) : ImportRead
    data class Rejected(val reason: ImportRejection) : ImportRead
}

/** Reads a building map from a file picked in the system picker or handed over by another app. */
interface BuildingFileReader {
    suspend fun read(uri: Uri): ImportRead
}

class ContentResolverBuildingFileReader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : BuildingFileReader {
    override suspend fun read(uri: Uri): ImportRead = withContext(io) {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_IMPORT_BYTES) return@runCatching TOO_LARGE
                }
                out.toByteArray()
            }
        }.getOrNull() ?: return@withContext ImportRead.Rejected(ImportRejection.Unreadable)
        if (bytes === TOO_LARGE) return@withContext ImportRead.Rejected(ImportRejection.TooLarge)
        parseBuildingMap(bytes)
    }

    private companion object {
        val TOO_LARGE = ByteArray(0)
    }
}

// Generous for a surveyed building (even a large multi-floor one is well under 1 MB),
// but small enough that picking the wrong file — a video, say — fails fast instead of
// trying to hold it all in memory.
internal const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

/** The format and consistency checks every imported file goes through. */
internal fun parseBuildingMap(bytes: ByteArray): ImportRead {
    val map = runCatching { BuildingMapJson.decode(bytes.decodeToString()) }.getOrNull()
        ?: return ImportRead.Rejected(ImportRejection.NotAMap)
    val problems = BuildingMapJson.problems(map)
    if (problems.isNotEmpty()) return ImportRead.Rejected(ImportRejection.Corrupt(problems))
    return ImportRead.Parsed(map)
}
