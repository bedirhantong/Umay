package com.appvalence.umay.settings

import android.content.Context
import androidx.core.content.edit
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

    var onboardingCompleted: Boolean
        get() = preferences.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = preferences.edit { putBoolean(KEY_ONBOARDING_COMPLETED, value) }

    private companion object {
        const val PREFERENCES_NAME = "user_settings"
        const val KEY_STEP_LENGTH = "step_length_meters"
        const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
