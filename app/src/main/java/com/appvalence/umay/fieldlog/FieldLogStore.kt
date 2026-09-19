package com.appvalence.umay.fieldlog

import android.content.Context
import androidx.core.content.edit
import com.appvalence.umay.common.newestFirst
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where field-test session logs live (see [FieldLogRecorder]), plus the
 * persisted on/off switch for recording them. Logs stay in the app's
 * private storage until someone explicitly shares them.
 */
class FieldLogStore(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY_NAME)
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** Off by default: recording is a field-testing aid, not something every user needs running. */
    var isRecordingEnabled: Boolean
        get() = preferences.getBoolean(KEY_RECORDING_ENABLED, false)
        set(value) = preferences.edit { putBoolean(KEY_RECORDING_ENABLED, value) }

    /** Newest first. Disk I/O: call off the main thread. */
    fun listLogs(): List<File> =
        directory.listFiles { file -> file.extension == LOG_EXTENSION }
            ?.newestFirst()
            .orEmpty()

    fun delete(file: File): Boolean = file.parentFile == directory && file.delete()

    fun deleteAll() {
        listLogs().forEach { it.delete() }
    }

    /**
     * A fresh file for a new session, named so a list of shared logs sorts by
     * time and says which building each one is from. Prunes the oldest logs
     * beyond [MAX_LOG_FILES] first, so forgetting to clean up never quietly
     * fills the phone's storage.
     */
    fun newSessionFile(buildingId: String, nowMs: Long = System.currentTimeMillis()): File {
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
