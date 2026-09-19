package com.appvalence.umay.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Why a single listen attempt produced no transcript. */
enum class SpeechRecognitionFailure { NOT_AVAILABLE, NO_SPEECH, PERMISSION_DENIED, OTHER }

sealed interface SpeechRecognitionResult {
    data class Recognized(val text: String) : SpeechRecognitionResult
    data class Failed(val reason: SpeechRecognitionFailure) : SpeechRecognitionResult
}

/**
 * One-shot speech-to-text for "ask Umay a question": listens for a single
 * utterance and returns its best transcript. Uses the platform's
 * [SpeechRecognizer] directly — only RECORD_AUDIO is needed, nothing this
 * app adds or pays for itself. Whether that ends up running fully on-device
 * or over the network is entirely up to whatever recognition service is
 * configured on the phone (Google's on Android usually offers an on-device
 * option); this class has no opinion either way.
 *
 * Must be used from a single thread with a Looper (the main thread, in
 * practice — every caller in this app is already on [kotlinx.coroutines.Dispatchers.Main]).
 */
class SpeechToText(private val context: Context, private val locale: Locale = Locale.forLanguageTag("tr-TR")) {
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    suspend fun listenOnce(): SpeechRecognitionResult {
        if (!isAvailable()) return SpeechRecognitionResult.Failed(SpeechRecognitionFailure.NOT_AVAILABLE)

        return suspendCancellableCoroutine { continuation ->
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            var finished = false

            fun finish(result: SpeechRecognitionResult) {
                if (finished) return
                finished = true
                recognizer.destroy()
                if (continuation.isActive) continuation.resume(result)
            }

            recognizer.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit

                    override fun onError(error: Int) {
                        val reason = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechRecognitionFailure.NO_SPEECH
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionFailure.PERMISSION_DENIED
                            else -> SpeechRecognitionFailure.OTHER
                        }
                        finish(SpeechRecognitionResult.Failed(reason))
                    }

                    override fun onResults(results: Bundle) {
                        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                        finish(
                            if (text.isNullOrBlank()) {
                                SpeechRecognitionResult.Failed(SpeechRecognitionFailure.NO_SPEECH)
                            } else {
                                SpeechRecognitionResult.Recognized(text)
                            },
                        )
                    }

                    override fun onPartialResults(partialResults: Bundle?) = Unit
                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                },
            )

            continuation.invokeOnCancellation { recognizer.destroy() }

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }
            recognizer.startListening(intent)
        }
    }
}
