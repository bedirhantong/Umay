package com.appvalence.umay.fieldlog

import android.content.Context
import androidx.core.content.edit
import com.appvalence.umay.common.newestFirst
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Field-test session logs (see [FieldLogRecorder]) and the persisted on/off
 * switch for recording them. Every file operation is disk I/O: call off the
 * main thread.
 */
interface FieldLogFiles {
    /** Off by default: recording is a field-testing aid, not something every user needs running. */
    var isRecordingEnabled: Boolean

    /** Newest first. */
    fun listLogs(): List<File>

    fun delete(file: File): Boolean

    fun deleteAll()

    /** A fresh file for a new session; see [FieldLogStore.newSessionFile]. */
    fun newSessionFile(buildingId: String, nowMs: Long = System.currentTimeMillis()): File
}

/** Logs stay in the app's private storage until someone explicitly shares them. */
@Singleton
class FieldLogStore @Inject constructor(@ApplicationContext context: Context) : FieldLogFiles {
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var isRecordingEnabled: Boolean
        get() = preferences.getBoolean(KEY_RECORDING_ENABLED, false)
        set(value) = preferences.edit { putBoolean(KEY_RECORDING_ENABLED, value) }

    override fun listLogs(): List<File> =
        directory.listFiles { file -> file.extension == LOG_EXTENSION }
            ?.newestFirst()
            .orEmpty()

    override fun delete(file: File): Boolean = file.parentFile == directory && file.delete()

    override fun deleteAll() {
        listLogs().forEach { it.delete() }
    }

    /**
     * Named so a list of shared logs sorts by time and says which building each one
     * is from. Prunes the oldest logs beyond [MAX_LOG_FILES] first, so forgetting to
     * clean up never quietly fills the phone's storage.
     */
    override fun newSessionFile(buildingId: String, nowMs: Long): File {
        directory.mkdirs()
        listLogs().drop(MAX_LOG_FILES - 1).forEach { it.delete() }
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(nowMs))
        val building = buildingId.replace(UNSAFE_FILENAME_CHARS, "_").take(40).ifBlank { "bina" }
        return File(directory, "umay_${building}_$timestamp.$LOG_EXTENSION")
    }

    companion object {
        private const val DIRECTORY_NAME = "field-logs"
        private const val LOG_EXTENSION = "jsonl"
        private const val PREFERENCES_NAME = "field_log"
        private const val KEY_RECORDING_ENABLED = "recording_enabled"
        const val MAX_LOG_FILES = 50

        // JSON Lines has no registered MIME type; octet-stream makes every share
        // target treat it as an opaque attachment instead of pasting it as text.
        const val SHARE_MIME_TYPE = "application/octet-stream"
        private val UNSAFE_FILENAME_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
