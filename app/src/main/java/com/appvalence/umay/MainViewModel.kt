package com.appvalence.umay

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.appvalence.umay.settings.UserSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** App-level UI state: whether the spoken first-run tour is showing. */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val settings: UserSettings,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    // First launch opens on the tour instead of dropping a blind user onto an
    // unexplained screen; replayable from Ayarlar.
    val showTour: StateFlow<Boolean> = savedState.getStateFlow(KEY_SHOW_TOUR, !settings.onboardingCompleted)

    fun onTourFinished() {
        settings.onboardingCompleted = true
        savedState[KEY_SHOW_TOUR] = false
    }

    fun replayTour() {
        savedState[KEY_SHOW_TOUR] = true
    }

    private companion object {
        const val KEY_SHOW_TOUR = "show_tour"
    }
}
