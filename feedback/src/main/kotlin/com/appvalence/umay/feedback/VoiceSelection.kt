package com.appvalence.umay.feedback

import java.util.Locale

/** The parts of an installed TTS voice that matter for choosing one (a plain mirror of android.speech.tts.Voice). */
data class VoiceCandidate(
    val name: String,
    val locale: Locale,
    /** android.speech.tts.Voice.QUALITY_* — higher is better. */
    val quality: Int,
    val requiresNetwork: Boolean,
    val installed: Boolean,
)

/**
 * Picks the voice guidance should be spoken in: one whose language matches
 * the text being spoken (not the device's UI language — a phone set to
 * English still reads this app's Turkish text, and an English voice reading
 * Turkish is barely intelligible).
 *
 * Among matching voices: installed, on-device ones only — a not-yet-downloaded
 * voice silently falls back to something else, and a network voice sends every
 * sentence it reads to a server (and goes silent in a basement with no signal).
 * Then the exact country, then the highest quality.
 */
object VoiceSelection {
    fun pick(candidates: List<VoiceCandidate>, textLocale: Locale): VoiceCandidate? =
        candidates
            .filter { it.installed && !it.requiresNetwork && it.locale.language == textLocale.language }
            .sortedWith(
                compareBy<VoiceCandidate> { it.locale.country != textLocale.country }
                    .thenByDescending { it.quality },
            )
            .firstOrNull()
}
