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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.appvalence.umay.R
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.feedback.VoiceStatus
import java.util.Locale

/**
 * The language this app's spoken text is written in — read from resources,
 * so it always matches the strings actually being spoken: the default
 * values/ strings are Turkish, and a future translation sets its own tag
 * alongside its own text. Never the device language (see [TtsFeedbackController]).
 */
fun speechLocale(context: Context): Locale = Locale.forLanguageTag(context.getString(R.string.speech_language_tag))

/**
 * Shown when the device can't speak the app's language properly, with a way
 * to fix it: opens the TTS engine's own voice-data installer (falling back to
 * the system text-to-speech settings). Renders nothing when all is well.
 * [onRecheck] runs whenever the screen comes back to the front while the voice
 * isn't ready — coming back from the installer is exactly when it may have changed.
 */
@Composable
fun VoiceStatusNotice(status: VoiceStatus, onRecheck: () -> Unit, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentStatus = rememberUpdatedState(status)
    val currentOnRecheck = rememberUpdatedState(onRecheck)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && currentStatus.value != VoiceStatus.READY) currentOnRecheck.value()
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
