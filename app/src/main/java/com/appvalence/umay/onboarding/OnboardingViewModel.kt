package com.appvalence.umay.onboarding

import androidx.lifecycle.ViewModel
import com.appvalence.umay.common.DeviceCapabilities
import com.appvalence.umay.feedback.Speaker
import com.appvalence.umay.feedback.VoiceStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val speaker: Speaker,
    capabilities: DeviceCapabilities,
) : ViewModel() {
    val voiceStatus: StateFlow<VoiceStatus> = speaker.voiceStatus

    /** Floor changes are explained differently with and without a barometer. */
    val hasBarometer: Boolean = capabilities.hasBarometer

    fun speak(text: String) = speaker.speakNow(text)

    fun stopSpeaking() = speaker.stop()

    fun refreshVoice() = speaker.refreshVoice()
}
