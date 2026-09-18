package com.appvalence.umay.feedback

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NavigationAlert
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/** Whether guidance can actually be spoken in the right language on this device. */
enum class VoiceStatus {
    INITIALIZING,

    /** A voice in the text's language is installed and selected. */
    READY,

    /** The engine supports the language but its voice data isn't downloaded yet — speech falls back to another voice. */
    MISSING_DATA,

    /** The installed TTS engine has no voice for the language at all. */
    NOT_SUPPORTED,

    /** No working TTS engine on the device. */
    ENGINE_UNAVAILABLE,
}

/**
 * Speaks a NavigationAlert via Android TextToSpeech (FR-13). CRITICAL alerts
 * flush the queue and interrupt whatever is currently being spoken so they
 * reach the user as fast as the TTS engine allows (FR-14); WARNING/INFO
 * alerts queue behind what is already playing.
 *
 * [speechLocale] is the language of the *text* being spoken — not the
 * device's UI language. The two differ whenever the phone is set to a
 * language the app isn't translated into, and an English voice reading
 * Turkish is barely intelligible. The best installed voice for it is picked
 * via [VoiceSelection]; [voiceStatus] reports when none is available, so
 * the UI can offer to install one instead of speaking in a foreign accent
 * without explanation. Speech still goes ahead in that case — for someone
 * who can't see the screen, accented guidance beats silence.
 */
class TtsFeedbackController(
    context: Context,
    private val speechLocale: Locale = Locale.getDefault(),
) : FeedbackController {

    private var isReady = false
    private val pendingUtterances = mutableListOf<NavigationAlert>()
    private val status = MutableStateFlow(VoiceStatus.INITIALIZING)

    val voiceStatus: StateFlow<VoiceStatus> = status.asStateFlow()

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { initStatus ->
            if (initStatus != TextToSpeech.SUCCESS) {
                status.value = VoiceStatus.ENGINE_UNAVAILABLE
                return@TextToSpeech
            }
            status.value = configureVoice()
            isReady = true
            pendingUtterances.forEach { speak(it) }
            pendingUtterances.clear()
        }
    }

    private fun configureVoice(): VoiceStatus {
        // Some engines throw from getVoices() (or return null) instead of an empty set.
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        val candidates = voices.map { voice ->
            VoiceCandidate(
                name = voice.name,
                locale = voice.locale,
                quality = voice.quality,
                requiresNetwork = voice.isNetworkConnectionRequired,
                installed = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty(),
            )
        }
        val best = VoiceSelection.pick(candidates, speechLocale)
        val chosen = best?.let { pick -> voices.firstOrNull { it.name == pick.name } }
        if (chosen != null && tts.setVoice(chosen) == TextToSpeech.SUCCESS) return VoiceStatus.READY

        return when (tts.setLanguage(speechLocale)) {
            TextToSpeech.LANG_MISSING_DATA -> VoiceStatus.MISSING_DATA
            TextToSpeech.LANG_NOT_SUPPORTED -> VoiceStatus.NOT_SUPPORTED
            else -> VoiceStatus.READY
        }
    }

    override fun emit(alert: NavigationAlert) {
        if (!isReady) {
            pendingUtterances += alert
            return
        }
        speak(alert)
    }

    private fun speak(alert: NavigationAlert) {
        val queueMode = if (alert.priority == AlertPriority.CRITICAL) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }
        tts.speak(alert.message, queueMode, Bundle.EMPTY, alert.id)
    }

    /**
     * Speaks [text] right away, cutting off anything still playing — for
     * narration that replaces itself (an onboarding page the person just
     * moved past), not for navigation alerts, which go through [emit].
     */
    fun speakNow(text: String) {
        emit(
            NavigationAlert(
                id = "narration-${System.nanoTime()}",
                priority = AlertPriority.CRITICAL, // CRITICAL is what flushes the queue
                message = text,
                relatedObstacle = null,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
    }

    /** Stops speaking without shutting down, e.g. when the person skips ahead. */
    fun stop() {
        if (isReady) tts.stop()
        pendingUtterances.clear()
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
