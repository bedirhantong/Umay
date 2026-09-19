package com.appvalence.umay.common

import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appvalence.umay.R
import com.appvalence.umay.appGraph
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.feedback.VoiceStatus
import com.appvalence.umay.settings.UserSettings
import java.util.Locale

/**
 * The language this app's spoken text is written in — read from resources,
 * so it always matches the strings actually being spoken: the default
 * values/ strings are Turkish, and a future translation sets its own tag
 * alongside its own text. Never the device language (see [TtsFeedbackController]).
 */
fun speechLocale(context: Context): Locale = Locale.forLanguageTag(context.getString(R.string.speech_language_tag))

/** Every voice in the app is created through this, so they all speak in the same, right voice, at the person's chosen pace. */
fun createSpeechController(context: Context): TtsFeedbackController =
    TtsFeedbackController(context, speechLocale(context), UserSettings(context).speechRate)

/**
 * The app-wide voice (see [com.appvalence.umay.AppGraph.speech]) for a screen
 * that talks. Whatever this screen started saying is cut off when it goes
 * away — but the voice itself stays up, so the next screen can speak right
 * away instead of starting a speech engine all over again.
 */
@Composable
fun rememberAppSpeech(): TtsFeedbackController {
    val speech = LocalContext.current.appGraph.speech
    DisposableEffect(speech) { onDispose { speech.stop() } }
    return speech
}

/**
 * Shown when the device can't speak the app's language properly, with a way
 * to fix it: opens the TTS engine's own voice-data installer (falling back to
 * the system text-to-speech settings). Renders nothing when all is well.
 */
@Composable
fun VoiceStatusNotice(controller: TtsFeedbackController, modifier: Modifier = Modifier) {
    val status by controller.voiceStatus.collectAsState()
    // Coming back from the voice installer is exactly when the answer may have changed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && controller.voiceStatus.value != VoiceStatus.READY) controller.refreshVoice()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val message = when (status) {
        VoiceStatus.MISSING_DATA -> R.string.voice_missing_data
        VoiceStatus.NOT_SUPPORTED -> R.string.voice_not_supported
        VoiceStatus.ENGINE_UNAVAILABLE -> R.string.voice_engine_unavailable
        VoiceStatus.INITIALIZING, VoiceStatus.READY -> return
    }
    val context = LocalContext.current
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = stringResource(message), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { openVoiceInstaller(context) }) {
                Text(stringResource(R.string.voice_install))
            }
        }
    }
}

private fun openVoiceInstaller(context: Context) {
    val opened = runCatching { context.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)) }.isSuccess
    if (!opened) runCatching { context.startActivity(Intent(TTS_SETTINGS_ACTION)) }
}

// Not in the public SDK constants, but the stable action every Android version's
// Settings app registers for its text-to-speech page.
private const val TTS_SETTINGS_ACTION = "com.android.settings.TTS_SETTINGS"
