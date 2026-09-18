package com.appvalence.umay.settings

import android.content.Context
import androidx.core.content.edit
import com.appvalence.umay.feedback.TtsFeedbackController
import com.appvalence.umay.positioning.pdr.StepDetector

/** Per-person, per-device preferences that outlive a single session. */
class UserSettings(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    /** This person's calibrated stride, or null if they haven't calibrated yet. */
    var calibratedStepLengthMeters: Double?
        get() = if (preferences.contains(KEY_STEP_LENGTH)) preferences.getFloat(KEY_STEP_LENGTH, 0f).toDouble() else null
        set(value) = preferences.edit {
            if (value == null) remove(KEY_STEP_LENGTH) else putFloat(KEY_STEP_LENGTH, value.toFloat())
        }

    /** The stride dead reckoning should use: the calibrated one when there is one. */
    val stepLengthMeters: Double
        get() = calibratedStepLengthMeters ?: StepDetector.AVERAGE_STEP_LENGTH_METERS

    /** Vibration cues alongside speech (see HapticPatterns). On by default: they cost nothing and help in noise. */
    var hapticsEnabled: Boolean
        get() = preferences.getBoolean(KEY_HAPTICS_ENABLED, true)
        set(value) = preferences.edit { putBoolean(KEY_HAPTICS_ENABLED, value) }

    var speechRate: Float
        get() = preferences.getFloat(KEY_SPEECH_RATE, TtsFeedbackController.DEFAULT_SPEECH_RATE)
        set(value) = preferences.edit { putFloat(KEY_SPEECH_RATE, value) }

    /**
     * Off by default: with the screen off, positioning and guidance carry on (see
     * NavigationSession) but the camera can't, so obstacle detection pauses. On keeps
     * the screen awake for the whole walk so it doesn't — at a real battery cost.
     */
    var keepScreenOnDuringNavigation: Boolean
        get() = preferences.getBoolean(KEY_KEEP_SCREEN_ON, false)
        set(value) = preferences.edit { putBoolean(KEY_KEEP_SCREEN_ON, value) }

    var onboardingCompleted: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = preferences.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    private companion object {
        const val PREFERENCES_NAME = "user_settings"
        const val KEY_STEP_LENGTH = "step_length_meters"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        const val KEY_HAPTICS_ENABLED = "haptics_enabled"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_KEEP_SCREEN_ON = "keep_screen_on_during_navigation"
    }
}
