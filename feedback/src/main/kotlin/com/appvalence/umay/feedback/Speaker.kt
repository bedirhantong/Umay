package com.appvalence.umay.feedback

import kotlinx.coroutines.flow.StateFlow

/** Speaks narration outside navigation alerts — what screens and their ViewModels depend on, rather than Android's TTS directly. */
interface Speaker {
    val voiceStatus: StateFlow<VoiceStatus>

    var speechRate: Float

    /** Speaks [text] right away, cutting off anything still playing. */
    fun speakNow(text: String)

    /** Stops speaking without shutting down. */
    fun stop()

    /** Re-checks the available voice, e.g. after the person installed missing voice data. */
    fun refreshVoice()
}

/** Plays a vibration pattern on demand — the haptic counterpart of [Speaker]. */
interface HapticPlayer {
    val isAvailable: Boolean

    fun play(pattern: HapticPattern)

    fun stop()
}
