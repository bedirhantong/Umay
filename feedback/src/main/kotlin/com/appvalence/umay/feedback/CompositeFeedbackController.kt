package com.appvalence.umay.feedback

import com.appvalence.umay.coremodel.NavigationAlert

/** Delivers every alert through several channels at once — speech and vibration, say. */
class CompositeFeedbackController(private val controllers: List<FeedbackController>) : FeedbackController {
    override fun emit(alert: NavigationAlert) {
        controllers.forEach { it.emit(alert) }
    }
}
