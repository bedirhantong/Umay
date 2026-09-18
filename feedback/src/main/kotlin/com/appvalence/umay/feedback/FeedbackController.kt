package com.appvalence.umay.feedback

import com.appvalence.umay.coremodel.NavigationAlert

/**
 * Renders a NavigationAlert to the user via TTS / sound icon / haptics (Faz 3).
 * CRITICAL alerts must reach the user within the remaining slice of the
 * NFR-1 320ms end-to-end latency budget.
 */
interface FeedbackController {
    fun emit(alert: NavigationAlert)
}
