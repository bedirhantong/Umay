package com.appvalence.umay.feedback

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.appvalence.umay.coremodel.NavigationAlert

/**
 * Vibrates a distinct pattern per alert (see [HapticPatterns]), alongside
 * speech. Alerts without a cue (e.g. the periodic repeat of an instruction)
 * produce nothing. A new pattern replaces whatever is still vibrating — the
 * newest alert is the one that matters. No-op on devices without a vibrator.
 */
class HapticFeedbackController(context: Context) : FeedbackController, HapticPlayer {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    override val isAvailable: Boolean get() = vibrator?.hasVibrator() == true

    override fun emit(alert: NavigationAlert) {
        play(HapticPatterns.forCue(alert.cue) ?: return)
    }

    override fun play(pattern: HapticPattern) {
        val vibrator = vibrator?.takeIf { it.hasVibrator() } ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(pattern.timingsMs, pattern.amplitudes, NO_REPEAT)
                } else {
                    VibrationEffect.createWaveform(pattern.timingsMs, NO_REPEAT)
                }
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern.timingsMs, NO_REPEAT)
            }
        }
    }

    override fun stop() {
        vibrator?.cancel()
    }

    private companion object {
        const val NO_REPEAT = -1
    }
}
