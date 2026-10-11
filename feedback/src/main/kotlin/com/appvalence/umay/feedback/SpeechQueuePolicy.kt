package com.appvalence.umay.feedback

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NavigationAlert

/**
 * Whether a new alert waits its turn or cuts off what's still queued or
 * playing. A critical one always cuts in. New guidance replaces old guidance:
 * spoken one after the other, a sentence about the corridor just walked would
 * hold back "Sola dön." until the walker had passed the turn, and "Hedefe
 * ulaştın." until they'd walked on past the door (simulated walks with speech
 * taking its time: 20 of 36 arrived, 8 at a slower speech rate; replacing,
 * 35–36). It never cuts off anything else, though: an obstacle warning or a
 * marker read is still said in full.
 */
object SpeechQueuePolicy {
    /** [active]: the alerts still queued or playing. */
    fun flushes(alert: NavigationAlert, active: Collection<NavigationAlert>): Boolean =
        alert.priority == AlertPriority.CRITICAL || (alert.isGuidance && active.all { it.isGuidance })
}
