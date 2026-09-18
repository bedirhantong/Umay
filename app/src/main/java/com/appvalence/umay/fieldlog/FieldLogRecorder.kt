package com.appvalence.umay.fieldlog

import android.util.Log
import com.appvalence.umay.coremodel.FieldLogEvent
import com.appvalence.umay.coremodel.FieldLogJson
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

/**
 * Appends [FieldLogEvent]s to one session's JSON Lines file (see
 * [FieldLogStore.newSessionFile]).
 *
 * Encoding and disk writes happen on a dedicated background thread — [record]
 * is called from the navigation screen's collectors on the main thread, and
 * BLE sightings alone can arrive dozens of times a second. Flushed at least
 * every [FLUSH_INTERVAL_MS], so a crash or a killed process loses at most
 * that much of the tail, and the file stays valid JSON Lines up to its last
 * complete line either way.
 *
 * Logging is strictly best-effort: a write failure (storage full, say) stops
 * recording for the rest of the session but never propagates into
 * navigation — a user being guided must not lose guidance because a
 * diagnostic file couldn't be written.
 */
class FieldLogRecorder(val file: File) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "umay-field-log").apply { isDaemon = true }
    }
    private var writer: BufferedWriter? = null
    private var lastFlushAtMs = 0L

    @Volatile
    var failed: Boolean = false
        private set

    fun record(event: FieldLogEvent) {
        if (failed) return
        submit {
            val out = writer ?: file.bufferedWriter().also { writer = it }
            out.write(FieldLogJson.encode(event))
            out.newLine()
            val now = System.currentTimeMillis()
            if (now - lastFlushAtMs >= FLUSH_INTERVAL_MS) {
                out.flush()
                lastFlushAtMs = now
            }
        }
    }

    /** Flushes and closes the file. Safe to call more than once; later [record]s are ignored. */
    fun close() {
        submit {
            writer?.flush()
            writer?.close()
            writer = null
        }
        executor.shutdown()
    }

    private fun submit(block: () -> Unit) {
        try {
            executor.execute {
                if (failed) return@execute
                try {
                    block()
                } catch (e: Exception) {
                    failed = true
                    runCatching { writer?.close() }
                    writer = null
                    Log.e(TAG, "Field log write failed, recording stopped for this session", e)
                }
            }
        } catch (_: RejectedExecutionException) {
            // Already closed — nothing left to record into.
        }
    }

    private companion object {
        const val TAG = "FieldLogRecorder"
        const val FLUSH_INTERVAL_MS = 2_000L
    }
}
