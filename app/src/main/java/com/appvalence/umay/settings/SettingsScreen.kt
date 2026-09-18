package com.appvalence.umay.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.appvalence.umay.R
import com.appvalence.umay.common.rememberIsScreenReaderOn
import com.appvalence.umay.common.VoiceStatusNotice
import com.appvalence.umay.common.createSpeechController
import com.appvalence.umay.coremodel.GuidanceCue
import com.appvalence.umay.feedback.HapticFeedbackController
import com.appvalence.umay.feedback.HapticPatterns
import com.appvalence.umay.feedback.VoiceStatus
import com.appvalence.umay.positioning.baro.BarometerSensor
import com.appvalence.umay.positioning.pdr.StepDetector
import com.appvalence.umay.positioning.pdr.StepLengthCalibration
import kotlin.math.roundToInt
import java.text.DecimalFormat

@Composable
fun SettingsScreen(onReplayTour: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings = remember { UserSettings(context) }
    var calibratedStepLength by remember { mutableStateOf(settings.calibratedStepLengthMeters) }
    var calibrating by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (calibrating) {
            StepCalibration(
                onSave = { stepLength ->
                    settings.calibratedStepLengthMeters = stepLength
                    calibratedStepLength = stepLength
                    calibrating = false
                },
                onCancel = { calibrating = false },
            )
            return@Column
        }

        SettingsCard(icon = Icons.AutoMirrored.Filled.DirectionsWalk, title = stringResource(R.string.settings_step_title)) {
            Text(
                text = calibratedStepLength
                    ?.let { stringResource(R.string.settings_step_calibrated, centimeters(it)) }
                    ?: stringResource(R.string.settings_step_default, centimeters(StepDetector.AVERAGE_STEP_LENGTH_METERS)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_step_explanation),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Button(modifier = Modifier.fillMaxWidth().padding(top = 12.dp), onClick = { calibrating = true }) {
                Text(stringResource(if (calibratedStepLength == null) R.string.settings_step_calibrate else R.string.settings_step_recalibrate))
            }
            if (calibratedStepLength != null) {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        settings.calibratedStepLengthMeters = null
                        calibratedStepLength = null
                    },
                ) { Text(stringResource(R.string.settings_step_reset)) }
            }
        }

        val hasBarometer = remember { BarometerSensor.isAvailable(context) }
        SettingsCard(icon = Icons.Filled.Layers, title = stringResource(R.string.settings_floor_title)) {
            Text(
                text = stringResource(if (hasBarometer) R.string.settings_floor_barometer else R.string.settings_floor_no_barometer),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SettingsCard(icon = Icons.Filled.RecordVoiceOver, title = stringResource(R.string.settings_voice_title)) {
            VoiceSettings(settings)
        }

        SettingsCard(icon = Icons.Filled.Vibration, title = stringResource(R.string.settings_haptics_title)) {
            HapticSettings(settings)
        }

        SettingsCard(icon = Icons.Filled.LightMode, title = stringResource(R.string.settings_screen_title)) {
            var keepScreenOn by remember { mutableStateOf(settings.keepScreenOnDuringNavigation) }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = keepScreenOn,
                        role = Role.Switch,
                        onValueChange = {
                            keepScreenOn = it
                            settings.keepScreenOnDuringNavigation = it
                        },
                    ),
            ) {
                Text(text = stringResource(R.string.settings_keep_screen_on), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = keepScreenOn, onCheckedChange = null)
            }
            Text(
                text = stringResource(R.string.settings_keep_screen_on_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SettingsCard(icon = Icons.Filled.Hearing, title = stringResource(R.string.settings_tour_title)) {
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onReplayTour) {
                Text(stringResource(R.string.settings_tour_replay))
            }
        }

        SettingsCard(icon = Icons.Filled.Info, title = stringResource(R.string.settings_about_title)) {
            AboutSection()
        }
    }
}

@Composable
private fun SettingsCard(icon: ImageVector, title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Column(horizontalAlignment = Alignment.Start) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp).semantics { heading() },
                )
            }
            content()
        }
    }
}

/** Which voice guidance will be spoken in and how fast, a way to hear it, and a fix if it's the wrong voice. */
@Composable
private fun VoiceSettings(settings: UserSettings) {
    val context = LocalContext.current
    val tts = remember { createSpeechController(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    val status by tts.voiceStatus.collectAsState()
    var rate by remember { mutableFloatStateOf(settings.speechRate) }

    when (status) {
        VoiceStatus.INITIALIZING -> Text(stringResource(R.string.voice_checking), style = MaterialTheme.typography.bodyMedium)
        VoiceStatus.READY -> Text(stringResource(R.string.voice_ready), style = MaterialTheme.typography.bodyMedium)
        else -> VoiceStatusNotice(tts)
    }
    val sample = stringResource(R.string.voice_sample)
    OutlinedButton(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { tts.speakNow(sample) }) {
        Text(stringResource(R.string.voice_try))
    }

    // Two plain buttons rather than a slider: each press is one clear step, easy to hit
    // and to count by touch, and it speaks its new pace right away — the value itself
    // is spoken as part of the sample, so this works the same with TalkBack on.
    fun changeRate(step: Int) {
        val index = (SPEECH_RATE_STEPS.indexOfFirst { it >= rate }.takeIf { it >= 0 } ?: SPEECH_RATE_STEPS.lastIndex) + step
        rate = SPEECH_RATE_STEPS[index.coerceIn(0, SPEECH_RATE_STEPS.lastIndex)]
        settings.speechRate = rate
        tts.speechRate = rate
        tts.speakNow(context.getString(R.string.settings_rate_sample, formatRate(rate)))
    }
    Text(
        text = stringResource(R.string.settings_rate_label, formatRate(rate)),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(top = 16.dp),
    )
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(modifier = Modifier.weight(1f), enabled = rate > SPEECH_RATE_STEPS.first(), onClick = { changeRate(-1) }) {
            Text(stringResource(R.string.settings_rate_slower))
        }
        OutlinedButton(modifier = Modifier.weight(1f), enabled = rate < SPEECH_RATE_STEPS.last(), onClick = { changeRate(+1) }) {
            Text(stringResource(R.string.settings_rate_faster))
        }
    }
}

// From "new to speech output" to "experienced screen-reader user" pace.
private val SPEECH_RATE_STEPS = listOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f)

private fun formatRate(rate: Float): String = DecimalFormat("0.##").format(rate)

private val HAPTIC_GUIDE = listOf(
    GuidanceCue.TURN_RIGHT to R.string.haptic_turn_right,
    GuidanceCue.TURN_LEFT to R.string.haptic_turn_left,
    GuidanceCue.CONTINUE to R.string.haptic_continue,
    GuidanceCue.HAZARD_AHEAD to R.string.haptic_hazard,
    GuidanceCue.FLOOR_CHANGE to R.string.haptic_floor_change,
    GuidanceCue.OBSTACLE_WARNING to R.string.haptic_obstacle_warning,
    GuidanceCue.OBSTACLE_CRITICAL to R.string.haptic_obstacle_critical,
    GuidanceCue.ARRIVED to R.string.haptic_arrived,
    GuidanceCue.REROUTED to R.string.haptic_rerouted,
    GuidanceCue.NO_ROUTE to R.string.haptic_no_route,
)

/**
 * On/off for vibration cues, plus a hands-on guide: a pattern is only useful
 * once someone has learned what it means, and that's learned by feeling it
 * here — not for the first time mid-corridor.
 */
@Composable
private fun HapticSettings(settings: UserSettings) {
    val context = LocalContext.current
    val haptics = remember { HapticFeedbackController(context) }
    DisposableEffect(Unit) { onDispose { haptics.stop() } }
    if (!haptics.isAvailable) {
        Text(stringResource(R.string.settings_haptics_unavailable), style = MaterialTheme.typography.bodyMedium)
        return
    }
    var enabled by remember { mutableStateOf(settings.hapticsEnabled) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(
                value = enabled,
                role = Role.Switch,
                onValueChange = {
                    enabled = it
                    settings.hapticsEnabled = it
                },
            ),
    ) {
        Text(text = stringResource(R.string.settings_haptics_toggle), style = MaterialTheme.typography.bodyLarge)
        // Row's toggleable owns the click and the merged accessible name (see BeaconScannerScreen).
        Switch(checked = enabled, onCheckedChange = null)
    }
    Text(
        text = stringResource(R.string.settings_haptics_learn),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    HAPTIC_GUIDE.forEach { (cue, label) ->
        OutlinedButton(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            onClick = { HapticPatterns.forCue(cue)?.let(haptics::play) },
        ) {
            Text(stringResource(label), modifier = Modifier.fillMaxWidth())
        }
    }
}

/**
 * Version, the safety disclaimer, and — since Umay is GPL-licensed — where its
 * source code is and what that license lets people do with it.
 */
@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?" }
    val sourceUrl = stringResource(R.string.source_code_url)
    Text(stringResource(R.string.settings_about_version, version), style = MaterialTheme.typography.bodyLarge)
    Text(
        text = stringResource(R.string.onboarding_safety_body),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(
        text = stringResource(R.string.settings_about_license),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    OutlinedButton(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, sourceUrl.toUri())) } },
    ) {
        Text(stringResource(R.string.settings_about_source))
    }
    Text(
        text = stringResource(R.string.settings_about_third_party),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

private enum class CalibrationPhase { SETUP, WALKING, RESULT }

/**
 * Walk a known distance, count the steps, divide (see [StepLengthCalibration]).
 * The "done" control while walking is one huge button filling most of the
 * screen, so it can be hit without looking — the person is walking, and may
 * well be blind — and every phase is spoken.
 */
@Composable
private fun StepCalibration(onSave: (Double) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val sensorsAvailable = remember { StepDetector.isAvailable(context) }
    val screenReaderOn = rememberIsScreenReaderOn()
    val tts = remember { createSpeechController(context) }
    DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    fun say(text: String) {
        if (!screenReaderOn) tts.speakNow(text)
    }

    var phase by remember { mutableStateOf(CalibrationPhase.SETUP) }
    var distanceText by remember { mutableStateOf(DEFAULT_CALIBRATION_DISTANCE_METERS.toString()) }
    var steps by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<StepLengthCalibration.Result?>(null) }
    val distance = distanceText.replace(',', '.').toDoubleOrNull()

    Text(
        text = stringResource(R.string.calibration_title),
        style = MaterialTheme.typography.headlineSmall,
        modifier = Modifier.semantics { heading() },
    )

    if (!sensorsAvailable) {
        Text(stringResource(R.string.calibration_no_sensors), modifier = Modifier.padding(top = 8.dp))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.common_back)) }
        return
    }

    val walkingPrompt = stringResource(R.string.calibration_walking_spoken)
    when (phase) {
        CalibrationPhase.SETUP -> {
            Text(
                text = stringResource(R.string.calibration_instructions),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedTextField(
                value = distanceText,
                onValueChange = { distanceText = it },
                label = { Text(stringResource(R.string.calibration_distance_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                isError = distance == null || distance < StepLengthCalibration.MIN_DISTANCE_METERS,
                supportingText = {
                    val minMeters = StepLengthCalibration.MIN_DISTANCE_METERS.roundToInt()
                    Text(pluralStringResource(R.plurals.calibration_distance_hint, minMeters, minMeters))
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
                enabled = distance != null && distance >= StepLengthCalibration.MIN_DISTANCE_METERS,
                onClick = {
                    steps = 0
                    phase = CalibrationPhase.WALKING
                    say(walkingPrompt)
                },
            ) { Text(stringResource(R.string.calibration_start)) }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }

        CalibrationPhase.WALKING -> {
            LaunchedEffect(Unit) {
                // The stride passed in doesn't matter here — only the step count is used.
                StepDetector(context).stepEvents().collect { steps++ }
            }
            Text(
                text = stringResource(R.string.calibration_walking),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
            Button(
                modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(top = 16.dp),
                onClick = {
                    val computed = StepLengthCalibration.compute(distance ?: 0.0, steps)
                    result = computed
                    phase = CalibrationPhase.RESULT
                    say(resultMessage(context, computed))
                },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.calibration_done), style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = pluralStringResource(R.plurals.calibration_step_count, steps, steps),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = { phase = CalibrationPhase.SETUP }) {
                Text(stringResource(R.string.common_cancel))
            }
        }

        CalibrationPhase.RESULT -> {
            val current = result
            Text(
                text = current?.let { resultMessage(context, it) }.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .padding(top = 12.dp)
                    .semantics { if (screenReaderOn) liveRegion = LiveRegionMode.Polite },
            )
            if (current is StepLengthCalibration.Result.Success) {
                Button(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 12.dp), onClick = { onSave(current.stepLengthMeters) }) {
                    Text(stringResource(R.string.common_save))
                }
            }
            OutlinedButton(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), onClick = { phase = CalibrationPhase.SETUP }) {
                Text(stringResource(R.string.calibration_retry))
            }
            TextButton(modifier = Modifier.fillMaxWidth(), onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

private fun resultMessage(context: Context, result: StepLengthCalibration.Result): String {
    val resources = context.resources
    return when (result) {
        is StepLengthCalibration.Result.Success -> centimeters(result.stepLengthMeters).let {
            resources.getQuantityString(R.plurals.calibration_result_success, it, it)
        }
        StepLengthCalibration.Result.DistanceTooShort -> StepLengthCalibration.MIN_DISTANCE_METERS.roundToInt().let {
            resources.getQuantityString(R.plurals.calibration_result_too_short, it, it)
        }
        is StepLengthCalibration.Result.TooFewSteps ->
            resources.getQuantityString(R.plurals.calibration_result_too_few_steps, result.steps, result.steps, StepLengthCalibration.MIN_STEPS)
        is StepLengthCalibration.Result.Implausible -> centimeters(result.stepLengthMeters).let {
            resources.getQuantityString(R.plurals.calibration_result_implausible, it, it)
        }
    }
}

private fun centimeters(meters: Double): Int = (meters * 100).roundToInt()

private const val DEFAULT_CALIBRATION_DISTANCE_METERS = 10
