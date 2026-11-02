package com.appvalence.umay.survey

import android.content.Context
import com.appvalence.umay.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A timeline of a camera survey for finding out what went wrong in it: when the camera
 * lost track, how many frames were used or thrown away and why, how the evidence for the
 * floor and walls built up. Numbers only — no images, no depth, no beacon identities.
 */
interface SurveyDiagnostics {
    fun log(event: String, vararg fields: Pair<String, Any?>)
}

/** Release builds, and tests. */
object NoSurveyDiagnostics : SurveyDiagnostics {
    override fun log(event: String, vararg fields: Pair<String, Any?>) = Unit
}

/**
 * Debug builds append to `files/diagnostics/survey.log` in the app's private storage (read
 * with `adb shell run-as`, see docs/YOL_HARITASI.md 0.20); a release build writes nothing.
 * One JSON object per line. Started over when it passes [MAX_BYTES].
 */
@Singleton
class FileSurveyDiagnostics @Inject constructor(
    @param:ApplicationContext context: Context,
) : SurveyDiagnostics {
    private val file = File(File(context.filesDir, "diagnostics").apply { mkdirs() }, "survey.log")

    @Synchronized
    override fun log(event: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            if (file.length() > MAX_BYTES) file.delete()
            val body = fields.joinToString("") { (key, value) -> ",\"$key\":${render(value)}" }
            file.appendText("{\"t\":${System.currentTimeMillis()},\"e\":\"$event\"$body}\n")
        }
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is Number, is Boolean -> value.toString()
        is Double -> if (value.isFinite()) value.toString() else "null"
        else -> "\"" + value.toString().replace("\\", "\\\\").replace("\"", "'") + "\""
    }

    private companion object {
        const val MAX_BYTES = 2_000_000L
    }
}
