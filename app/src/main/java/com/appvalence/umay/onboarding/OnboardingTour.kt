package com.appvalence.umay.onboarding

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.appvalence.umay.R
import com.appvalence.umay.common.VoiceStatusNotice
import com.appvalence.umay.common.rememberIsReduceMotionEnabled
import com.appvalence.umay.common.rememberIsScreenReaderOn
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private data class TourPage(val icon: ImageVector, @param:StringRes val title: Int, @param:StringRes val body: Int)

/** What the user picked to do right after finishing the tour, if anything. */
enum class OnboardingNextStep { SURVEY, CALIBRATION }

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
fun OnboardingTour(onFinish: (OnboardingNextStep?) -> Unit, modifier: Modifier = Modifier, viewModel: OnboardingViewModel = hiltViewModel()) {
    val hasBarometer = viewModel.hasBarometer
    val pages = remember(hasBarometer) {
        listOf(
            TourPage(Icons.Filled.Hearing, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
            // Right after the welcome, before anything that could build overconfidence:
            // what Umay can't do matters as much as what it can.
            TourPage(Icons.Filled.HealthAndSafety, R.string.onboarding_safety_title, R.string.onboarding_safety_body),
            TourPage(Icons.Filled.Navigation, R.string.onboarding_guidance_title, R.string.onboarding_guidance_body),
            // How to hold the phone and how guidance feels (vibration) belong together:
            // both are about the physical experience of a walk, once guidance itself
            // (previous page) is understood.
            TourPage(Icons.Filled.Vibration, R.string.onboarding_touch_title, R.string.onboarding_touch_body),
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
    val reduceMotion = rememberIsReduceMotionEnabled()
    val voiceStatus by viewModel.voiceStatus.collectAsStateWithLifecycle()
    // Whatever was being said is cut off on leaving; the shared voice itself stays up.
    DisposableEffect(viewModel) { onDispose { viewModel.stopSpeaking() } }

    val title = stringResource(page.title)
    val body = stringResource(page.body)
    var replayCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(index, replayCount, screenReaderOn) {
        if (screenReaderOn) viewModel.stopSpeaking() else viewModel.speak("$title. $body")
    }

    fun finish(next: OnboardingNextStep? = null) {
        viewModel.stopSpeaking()
        onFinish(next)
    }

    Column(modifier = modifier.fillMaxSize().padding(24.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text(
                    text = stringResource(R.string.onboarding_progress, index + 1, pages.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Purely decorative echo of the text above — TalkBack already gets the
                // exact step count from it, so these dots stay out of its way.
                StepDots(
                    total = pages.size,
                    current = index,
                    modifier = Modifier.padding(top = 6.dp).semantics { invisibleToUser() },
                )
            }
            if (!isLast) {
                TextButton(onClick = { finish() }) { Text(stringResource(R.string.onboarding_skip)) }
            }
        }
        // First thing a new user should know if the device can't speak Turkish properly.
        VoiceStatusNotice(voiceStatus, onRecheck = viewModel::refreshVoice, modifier = Modifier.padding(top = 8.dp))
        // Explains, on the very first page, why Umay's own voice stays silent from here
        // on — otherwise a TalkBack user might wonder why the tour never speaks.
        if (screenReaderOn && index == 0) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_talkback_detected),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        AnimatedContent(
            targetState = index,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = {
                val duration = if (reduceMotion) 0 else 220
                val forward = targetState > initialState
                (
                    slideInHorizontally(tween(duration)) { width -> if (forward) width / 4 else -width / 4 } +
                        fadeIn(tween(duration))
                    ).togetherWith(
                    slideOutHorizontally(tween(duration)) { width -> if (forward) -width / 4 else width / 4 } +
                        fadeOut(tween(duration)),
                )
            },
            label = "onboarding_page",
        ) { pageIndex ->
            val currentPage = pages[pageIndex]
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        currentPage.icon,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(
                    text = stringResource(currentPage.title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp).semantics { heading() },
                )
                Text(
                    text = stringResource(currentPage.body),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 12.dp)
                        // With TalkBack on, this is how the new page gets read out: TalkBack
                        // announces a live region's text whenever it changes.
                        .semantics { if (screenReaderOn) liveRegion = LiveRegionMode.Polite },
                )
            }
        }

        if (isLast) {
            // The tour ends by naming the two things it just told the user still need
            // doing (map the building, measure a stride) as one-tap destinations,
            // instead of leaving that only as spoken advice to remember later.
            Text(
                text = stringResource(R.string.onboarding_setup_cta_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
                onClick = { finish(OnboardingNextStep.SURVEY) },
            ) {
                Icon(Icons.Filled.Sensors, contentDescription = null)
                Text(stringResource(R.string.onboarding_setup_cta_survey), modifier = Modifier.padding(start = 8.dp))
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
                onClick = { finish(OnboardingNextStep.CALIBRATION) },
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Text(stringResource(R.string.onboarding_setup_cta_calibration), modifier = Modifier.padding(start = 8.dp))
            }
        }

        if (!screenReaderOn) {
            OutlinedButton(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), onClick = { replayCount++ }) {
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
                    if (isLast) finish() else index++
                },
            ) { Text(stringResource(if (isLast) R.string.onboarding_start else R.string.onboarding_next)) }
        }
    }
}

/** Small filled/unfilled dots marking progress through the tour, current step highlighted. */
@Composable
private fun StepDots(total: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(total) { i ->
            val active = i == current
            Box(
                modifier = Modifier
                    .size(if (active) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
            )
        }
    }
}
