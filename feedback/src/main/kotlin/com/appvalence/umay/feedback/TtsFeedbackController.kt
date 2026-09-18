package com.appvalence.umay.feedback

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NavigationAlert
import java.util.Locale

/**
 * Speaks a NavigationAlert via Android TextToSpeech (FR-13). CRITICAL alerts
 * flush the queue and interrupt whatever is currently being spoken so they
 * reach the user as fast as the TTS engine allows (FR-14); WARNING/INFO
 * alerts queue behind what is already playing.
 */
class TtsFeedbackController(context: Context) : FeedbackController {

    private var isReady = false
    private val pendingUtterances = mutableListOf<NavigationAlert>()

    private lateinit var tts: TextToSpeech

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            isReady = status == TextToSpeech.SUCCESS
            if (isReady) {
                tts.language = Locale.getDefault()
                pendingUtterances.forEach { speak(it) }
                pendingUtterances.clear()
            }
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

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
