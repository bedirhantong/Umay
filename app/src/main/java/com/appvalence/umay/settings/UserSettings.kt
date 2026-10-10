package com.appvalence.umay.settings

import android.content.Context
import androidx.core.content.edit
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.positioning.pdr.StepDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Per-person, per-device preferences that outlive a single session. */
interface UserSettings {
    /** This person's calibrated stride, or null if they haven't calibrated yet. */
    var calibratedStepLengthMeters: Double?

    /** Vibration cues alongside speech (see HapticPatterns). On by default: they cost nothing and help in noise. */
    var hapticsEnabled: Boolean

    var speechRate: Float

    /**
     * Off by default: with the screen off, positioning and guidance carry on (see
     * NavigationSession) but the camera can't, so obstacle detection pauses. On keeps
     * the screen awake for the whole walk so it doesn't — at a real battery cost.
     */
    var keepScreenOnDuringNavigation: Boolean

    /** Routes keep off stairs (lifts and ramps only) whenever the building allows it. */
    var avoidStairs: Boolean

    /** Turns and wall sides said as clock hours ("saat 2 yönüne") instead of left/right. */
    var clockDirections: Boolean

    var onboardingCompleted: Boolean

    /** The stride dead reckoning should use: the calibrated one when there is one. */
    val stepLengthMeters: Double
        get() = calibratedStepLengthMeters ?: StepDetector.AVERAGE_STEP_LENGTH_METERS
}

@Singleton
class SharedPreferencesUserSettings @Inject constructor(@ApplicationContext context: Context) : UserSettings {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var calibratedStepLengthMeters: Double?
        get() = if (preferences.contains(KEY_STEP_LENGTH)) preferences.getFloat(KEY_STEP_LENGTH, 0f).toDouble() else null
        set(value) = preferences.edit {
            if (value == null) remove(KEY_STEP_LENGTH) else putFloat(KEY_STEP_LENGTH, value.toFloat())
        }

    override var hapticsEnabled: Boolean
        get() = preferences.getBoolean(KEY_HAPTICS_ENABLED, true)
        set(value) = preferences.edit { putBoolean(KEY_HAPTICS_ENABLED, value) }

    override var speechRate: Float
        get() = preferences.getFloat(KEY_SPEECH_RATE, TtsFeedbackController.DEFAULT_SPEECH_RATE)
        set(value) = preferences.edit { putFloat(KEY_SPEECH_RATE, value) }

    override var keepScreenOnDuringNavigation: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = preferences.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    override var avoidStairs: Boolean
        get() = preferences.getBoolean(KEY_AVOID_STAIRS, false)
        set(value) = preferences.edit { putBoolean(KEY_AVOID_STAIRS, value) }

    override var clockDirections: Boolean
        get() = preferences.getBoolean(KEY_CLOCK_DIRECTIONS, false)
        set(value) = preferences.edit { putBoolean(KEY_CLOCK_DIRECTIONS, value) }

    override var onboardingCompleted: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = preferences.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    private companion object {
        const val PREFERENCES_NAME = "user_settings"
        const val KEY_STEP_LENGTH = "step_length_meters"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on_during_navigation"
        const val KEY_AVOID_STAIRS = "avoid_stairs"
        const val KEY_CLOCK_DIRECTIONS = "clock_directions"
    }
}
