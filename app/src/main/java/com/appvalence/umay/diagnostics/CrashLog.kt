package com.appvalence.umay.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.appvalence.umay.navigation.NavigationSessions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local-only crash reporting: an uncaught exception is written to a file in
 * the app's private storage, then handed on to the system as usual (so the
 * app still closes the normal way). Nothing leaves the device by itself —
 * the reports are shared by hand from the Dosyalar tab, the same way field
 * logs are — which keeps the "your data stays on your phone" promise intact.
 */
object CrashLog {
    private const val DIRECTORY_NAME = "crash-logs"
    private const val EXTENSION = "txt"
    private const val MAX_REPORTS = 20
    private const val TAG = "CrashLog"

    // Plain text, not an attachment type: a stack trace is meant to be read.
    const val SHARE_MIME_TYPE = "text/plain"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Must never throw itself: that would replace the real crash with a confusing one.
            try {
                write(appContext, thread, throwable)
            } catch (t: Throwable) {
                Log.e(TAG, "Could not write crash report", t)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun list(context: Context): List<File> =
        directory(context).listFiles { file -> file.extension == EXTENSION }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun delete(context: Context, file: File): Boolean = file.parentFile == directory(context) && file.delete()

    /** Everything needed to act on a crash seen only by a tester in the field. */
    fun formatReport(
        timeMs: Long,
        threadName: String,
        throwable: Throwable,
        appVersion: String,
        device: String,
        androidSdk: Int,
        activeNavigation: String?,
    ): String = buildString {
        appendLine("Umay crash report")
        appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.US).format(Date(timeMs))}")
        appendLine("app: $appVersion")
        appendLine("device: $device (Android SDK $androidSdk)")
        appendLine("thread: $threadName")
        appendLine("navigation: ${activeNavigation ?: "none"}")
        appendLine()
        append(throwable.stackTraceToString())
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val directory = directory(context).apply { mkdirs() }
        list(context).drop(MAX_REPORTS - 1).forEach { it.delete() }
        val now = System.currentTimeMillis()
        val appVersion = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
        val navigation = NavigationSessions.active.value?.let { "${it.buildingMap.id}: ${it.startNodeId} -> ${it.destinationNode.id}" }
        val report = formatReport(now, thread.name, throwable, appVersion, "${Build.MANUFACTURER} ${Build.MODEL}", Build.VERSION.SDK_INT, navigation)
        val name = "crash_${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))}.$EXTENSION"
        File(directory, name).writeText(report)
    }

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY_NAME)
}
