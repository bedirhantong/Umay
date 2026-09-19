package com.appvalence.umay.onboarding

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.common.rememberIsScreenReaderOn
import com.appvalence.umay.common.VoiceStatusNotice
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class TourPage(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val body: Int)

/**
 * First-launch spoken tour: what Umay does, how to hold the phone, how
 * guidance and floor changes work, and what has to happen before a first
 * walk (a building map, ideally a stride calibration).
 *
 * Every page is read aloud as it appears — by Umay's own voice, or, when
 * TalkBack is on, announced through TalkBack instead so the two never speak
 * over each other (see [rememberIsScreenReaderOn]). Large, fixed-position
 * buttons (back / next at the bottom, skip at the top) so the controls are
 * always in the same place to find by touch. Can be replayed any time from
 * Ayarlar.
 */
@Composable
fun OnboardingTour(onFinish: () -> Unit, modifier: Modifier = Modifier, viewModel: OnboardingViewModel = hiltViewModel()) {
    val hasBarometer = viewModel.hasBarometer
    val pages = remember(hasBarometer) {
        listOf(
            TourPage(Icons.Filled.Hearing, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
            // Right after the welcome, before anything that could build overconfidence:
            // what Umay can't do matters as much as what it can.
            TourPage(Icons.Filled.HealthAndSafety, R.string.onboarding_safety_title, R.string.onboarding_safety_body),
            TourPage(Icons.Filled.PhoneAndroid, R.string.onboarding_hold_title, R.string.onboarding_hold_body),
            TourPage(Icons.Filled.Navigation, R.string.onboarding_guidance_title, R.string.onboarding_guidance_body),
            TourPage(Icons.Filled.Vibration, R.string.onboarding_feel_title, R.string.onboarding_feel_body),
            TourPage(
                Icons.Filled.Layers,
                R.string.onboarding_floors_title,
                if (hasBarometer) R.string.onboarding_floors_body_barometer else R.string.onboarding_floors_body_no_barometer,
            ),
            TourPage(Icons.Filled.Map, R.string.onboarding_setup_title, R.string.onboarding_setup_body),
        )
    }
    var index by rememberSaveable { mutableIntStateOf(0) }
    val page = pages[index]
    val isLast = index == pages.lastIndex

    val screenReaderOn = rememberIsScreenReaderOn()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    // Whatever was being said is cut off on leaving; the shared voice itself stays up.
    DisposableEffect(viewModel) { onDispose { viewModel.stopSpeaking() } }

    val title = stringResource(page.title)
    val body = stringResource(page.body)
    var replayCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(index, replayCount, screenReaderOn) {
        if (screenReaderOn) viewModel.stopSpeaking() else viewModel.speak("$title. $body")
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = stringResource(R.string.onboarding_progress, index + 1, pages.size),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isLast) {
                TextButton(onClick = {
                    viewModel.stopSpeaking()
                    onFinish()
                }) { Text(stringResource(R.string.onboarding_skip)) }
            }
        }
        // First thing a new user should know if the device can't speak Turkish properly.
        VoiceStatusNotice(voiceStatus, onRecheck = viewModel::refreshVoice, modifier = Modifier.padding(top = 8.dp))

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(page.icon, contentDescription = null, modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp).semantics { heading() },
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 12.dp)
                    // With TalkBack on, this is how the new page gets read out: TalkBack
                    // announces a live region's text whenever it changes.
                    .semantics { if (screenReaderOn) liveRegion = LiveRegionMode.Polite },
            )
        }

        if (!screenReaderOn) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { replayCount++ }) {
                Icon(Icons.Filled.Replay, contentDescription = null)
                Text(stringResource(R.string.onboarding_replay), modifier = Modifier.padding(start = 8.dp))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
                enabled = index > 0,
                onClick = { index-- },
            ) { Text(stringResource(R.string.onboarding_back)) }
            Button(
                modifier = Modifier.weight(2f).heightIn(min = 56.dp),
                onClick = {
                    if (isLast) {
                        viewModel.stopSpeaking()
                        onFinish()
                    } else {
                        index++
                    }
                },
            ) { Text(stringResource(if (isLast) R.string.onboarding_start else R.string.onboarding_next)) }
        }
    }
}
