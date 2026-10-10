package com.appvalence.umay.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/** Why a single listen attempt produced no transcript. */
enum class SpeechRecognitionFailure {
    /** Nothing on this phone can recognize speech without the network. */
    NOT_AVAILABLE,

    /** The on-device recognizer exists but has no model for this language yet; a download was asked for. */
    LANGUAGE_DOWNLOADING,

    /** The on-device recognizer has no model for this language, and none can be downloaded. */
    LANGUAGE_NOT_SUPPORTED,
    NO_SPEECH,
    PERMISSION_DENIED,
    OTHER,
}

sealed interface SpeechRecognitionResult {
    data class Recognized(val text: String) : SpeechRecognitionResult
    data class Failed(val reason: SpeechRecognitionFailure) : SpeechRecognitionResult
}

/** Whether a language can be recognized on-device right now (see [SpeechToText.onDeviceLanguageState]). */
enum class OnDeviceLanguageState { INSTALLED, DOWNLOADING, DOWNLOADABLE, UNSUPPORTED }

/**
 * One-shot speech-to-text for "ask Umay a question": listens for a single
 * utterance and returns its best transcript.
 *
 * The voice never leaves the phone. On Android 12 and newer only the platform's
 * on-device recognizer is used; if the phone has none, the feature is
 * unavailable rather than quietly falling back to a recognizer that may send
 * audio to a server. Older Android versions have no on-device recognizer API,
 * so the request carries [RecognizerIntent.EXTRA_PREFER_OFFLINE], which the
 * platform documents as "only use an offline speech recognition engine".
 *
 * Must be used from a single thread with a Looper (the main thread, in
 * practice — every caller in this app is already on [kotlinx.coroutines.Dispatchers.Main]).
 */
class SpeechToText(private val context: Context, private val locale: Locale = Locale.forLanguageTag("tr-TR")) {

    /** A quick check before asking for the microphone; [listenOnce] can still fail for the language. */
    fun isAvailable(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    } else {
        SpeechRecognizer.isRecognitionAvailable(context)
    }

    suspend fun listenOnce(): SpeechRecognitionResult {
        if (!isAvailable()) return SpeechRecognitionResult.Failed(SpeechRecognitionFailure.NOT_AVAILABLE)
        val intent = recognitionIntent()
        val recognizer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val state = try {
                onDeviceLanguageState(recognizer, intent)
            } catch (e: CancellationException) {
                recognizer.destroy()
                throw e
            }
            val blocked = when (state) {
                OnDeviceLanguageState.INSTALLED, null -> null
                OnDeviceLanguageState.DOWNLOADING -> SpeechRecognitionFailure.LANGUAGE_DOWNLOADING
                OnDeviceLanguageState.DOWNLOADABLE -> {
                    // The language pack comes from the recognizer's own app, not through Umay;
                    // nothing the user says is involved.
                    recognizer.triggerModelDownload(intent)
                    SpeechRecognitionFailure.LANGUAGE_DOWNLOADING
                }
                OnDeviceLanguageState.UNSUPPORTED -> SpeechRecognitionFailure.LANGUAGE_NOT_SUPPORTED
            }
            if (blocked != null) {
                recognizer.destroy()
                return SpeechRecognitionResult.Failed(blocked)
            }
        }

        return suspendCancellableCoroutine { continuation ->
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
                        finish(SpeechRecognitionResult.Failed(failureFor(error)))
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

            continuation.invokeOnCancellation {
                finished = true
                recognizer.destroy()
            }
            recognizer.startListening(intent)
        }
    }

    private fun recognitionIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Redundant with the on-device recognizer, and the only safeguard before Android 12.
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

    /** Null when the recognizer can't say (it then simply tries, and reports a missing language as an error). */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun onDeviceLanguageState(recognizer: SpeechRecognizer, intent: Intent): OnDeviceLanguageState? =
        suspendCancellableCoroutine { continuation ->
            recognizer.checkRecognitionSupport(
                intent,
                context.mainExecutor,
                object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        if (continuation.isActive) {
                            continuation.resume(
                                languageState(
                                    language = locale.language,
                                    installed = support.installedOnDeviceLanguages,
                                    pending = support.pendingOnDeviceLanguages,
                                    supported = support.supportedOnDeviceLanguages,
                                ),
                            )
                        }
                    }

                    override fun onError(error: Int) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                },
            )
        }

    companion object {
        /** Language tags come as "tr-TR", "tr" or "TR-tr" depending on the recognizer; only the language counts. */
        fun languageState(language: String, installed: List<String>, pending: List<String>, supported: List<String>): OnDeviceLanguageState {
            fun List<String>.has() = any { Locale.forLanguageTag(it).language.equals(language, ignoreCase = true) }
            return when {
                installed.has() -> OnDeviceLanguageState.INSTALLED
                pending.has() -> OnDeviceLanguageState.DOWNLOADING
                supported.has() -> OnDeviceLanguageState.DOWNLOADABLE
                else -> OnDeviceLanguageState.UNSUPPORTED
            }
        }

        fun failureFor(error: Int): SpeechRecognitionFailure = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechRecognitionFailure.NO_SPEECH
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechRecognitionFailure.PERMISSION_DENIED
            // With offline-only recognition, a "network" error means there was nothing offline to use.
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                SpeechRecognitionFailure.NOT_AVAILABLE
            ERROR_LANGUAGE_NOT_SUPPORTED, ERROR_LANGUAGE_UNAVAILABLE -> SpeechRecognitionFailure.LANGUAGE_NOT_SUPPORTED
            else -> SpeechRecognitionFailure.OTHER
        }

        // SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED / ERROR_LANGUAGE_UNAVAILABLE (API 31);
        // only ever delivered on 31+, but named here so the mapping compiles for every API level.
        private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
        private const val ERROR_LANGUAGE_UNAVAILABLE = 13
    }
}
