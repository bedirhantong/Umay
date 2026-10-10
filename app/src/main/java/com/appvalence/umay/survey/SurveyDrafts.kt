package com.appvalence.umay.survey

import android.content.Context
import com.appvalence.umay.coremodel.BleBeaconReading
import com.appvalence.umay.coremodel.SurveyDraft
import com.appvalence.umay.coremodel.SurveyDraftJson
import com.appvalence.umay.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** A survey brought back from disk (see [SurveyDraft]). */
data class RestoredSurvey(val draft: SurveyDraft, val readings: List<BleBeaconReading>)

/** Where an unfinished survey is kept while it's recorded, so ending the app doesn't lose it. */
interface SurveyDrafts {
    suspend fun load(): RestoredSurvey?

    suspend fun save(draft: SurveyDraft)

    suspend fun appendReadings(readings: List<BleBeaconReading>)

    suspend fun delete()
}

/**
 * [SurveyDrafts] in the app's private files. The draft is replaced atomically
 * (written beside, then renamed over), so a kill mid-write leaves the previous
 * one intact; sightings are appended a batch per line.
 */
@Singleton
class FileSurveyDrafts @Inject constructor(
    @param:ApplicationContext context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : SurveyDrafts {
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val draftFile = File(directory, DRAFT_FILE)
    private val readingsFile = File(directory, READINGS_FILE)
    private val lock = Mutex()

    override suspend fun load(): RestoredSurvey? = locked {
        val draft = runCatching { draftFile.readText() }.getOrNull()?.let(SurveyDraftJson::decode) ?: return@locked null
        val readings = runCatching { readingsFile.useLines { SurveyDraftJson.decodeReadings(it) } }.getOrDefault(emptyList())
        RestoredSurvey(draft, readings)
    }

    override suspend fun save(draft: SurveyDraft) {
        locked {
            runCatching {
                directory.mkdirs()
                val temporary = File(directory, "$DRAFT_FILE.tmp")
                temporary.writeText(SurveyDraftJson.encode(draft))
                if (!temporary.renameTo(draftFile)) {
                    draftFile.delete()
                    temporary.renameTo(draftFile)
                }
            }
        }
    }

    override suspend fun appendReadings(readings: List<BleBeaconReading>) {
        if (readings.isEmpty()) return
        locked {
            runCatching {
                directory.mkdirs()
                readingsFile.appendText(SurveyDraftJson.encodeReadings(readings) + "\n")
            }
        }
    }

    override suspend fun delete() {
        locked { directory.deleteRecursively() }
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(io) { lock.withLock { block() } }

    private companion object {
        // Also kept out of backups (res/xml/*backup* and *extraction* rules).
        const val DIRECTORY_NAME = "survey-draft"
        const val DRAFT_FILE = "draft.json"
        const val READINGS_FILE = "readings.jsonl"
    }
}
