package com.appvalence.umay.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
 *
 * Audio is tagged as *navigation guidance*, the way turn-by-turn apps do it:
 * the system routes it to a connected Bluetooth headset and briefly lowers
 * (ducks) music or a podcast while it speaks, then restores it. During a
 * phone call nothing is spoken — the system refuses the audio focus, and
 * talking over a call helps no one — while vibration cues (a separate
 * [FeedbackController]) keep working; the periodic repeat of the current
 * instruction catches the user up once the call ends.
 */
class TtsFeedbackController(
    context: Context,
    private val speechLocale: Locale = Locale.getDefault(),
    speechRate: Float = DEFAULT_SPEECH_RATE,
) : FeedbackController {

    private var isReady = false
    private val pendingUtterances = mutableListOf<NavigationAlert>()

    private val audioManager: AudioManager? = context.getSystemService(AudioManager::class.java)
    private val audioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest: AudioFocusRequest? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { }
            .build()
    } else {
        null
    }

    @Suppress("DEPRECATION")
    private val legacyFocusListener = AudioManager.OnAudioFocusChangeListener { }

    // Utterances queued or playing. Audio focus is held while this is non-empty and
    // released as soon as it empties, so ducked music comes back right after speech.
    // Touched from the main thread (speak) and the TTS engine's callback thread.
    private val activeUtterances = mutableSetOf<String>()
    private val status = MutableStateFlow(VoiceStatus.INITIALIZING)

    val voiceStatus: StateFlow<VoiceStatus> = status.asStateFlow()

    private lateinit var tts: TextToSpeech

    /**
     * 1.0 is the engine's normal pace. Experienced screen-reader users commonly
     * listen at 2x or faster; someone new to it may want it slower — this is a
     * personal setting, not something to hardcode.
     */
    var speechRate: Float = speechRate
        set(value) {
            field = value
            if (isReady) tts.setSpeechRate(value)
        }

    init {
        tts = TextToSpeech(context.applicationContext) { initStatus ->
            if (initStatus != TextToSpeech.SUCCESS) {
                status.value = VoiceStatus.ENGINE_UNAVAILABLE
                return@TextToSpeech
            }
            status.value = configureVoice()
            tts.setSpeechRate(speechRate)
            tts.setAudioAttributes(audioAttributes)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) = Unit
                override fun onDone(utteranceId: String) = finished(utteranceId)

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) = finished(utteranceId)
                override fun onError(utteranceId: String, errorCode: Int) = finished(utteranceId)
                override fun onStop(utteranceId: String, interrupted: Boolean) = finished(utteranceId)
            })
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
        if (!acquireAudioFocus()) return // in a call (see class doc)
        val queueMode = if (alert.priority == AlertPriority.CRITICAL) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }
        synchronized(activeUtterances) { activeUtterances += alert.id }
        if (tts.speak(alert.message, queueMode, Bundle.EMPTY, alert.id) != TextToSpeech.SUCCESS) finished(alert.id)
    }

    private fun acquireAudioFocus(): Boolean {
        val manager = audioManager ?: return true
        val mode = manager.mode
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) return false
        if (synchronized(activeUtterances) { activeUtterances.isNotEmpty() }) return true // already held
        val result = if (focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(legacyFocusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun finished(utteranceId: String) {
        val nothingLeft = synchronized(activeUtterances) {
            activeUtterances -= utteranceId
            activeUtterances.isEmpty()
        }
        if (nothingLeft) abandonAudioFocus()
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (focusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.abandonAudioFocusRequest(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(legacyFocusListener)
        }
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
        synchronized(activeUtterances) { activeUtterances.clear() }
        abandonAudioFocus()
    }

    companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
    }
}
