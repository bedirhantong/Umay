package com.appvalence.umay.feedback

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NavigationAlert
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechQueuePolicyTest {
    private fun alert(priority: AlertPriority = AlertPriority.INFO, guidance: Boolean = false) =
        NavigationAlert(id = "a", priority = priority, message = "", relatedObstacle = null, createdAtMs = 0, isGuidance = guidance)

    @Test
    fun `new guidance replaces guidance not yet said`() {
        assertTrue(SpeechQueuePolicy.flushes(alert(guidance = true), listOf(alert(guidance = true), alert(AlertPriority.WARNING, guidance = true))))
    }

    @Test
    fun `guidance waits for a warning or a marker read to finish`() {
        assertFalse(SpeechQueuePolicy.flushes(alert(guidance = true), listOf(alert(guidance = true), alert(AlertPriority.WARNING))))
        assertFalse(SpeechQueuePolicy.flushes(alert(guidance = true), listOf(alert())))
    }

    @Test
    fun `anything else waits its turn, unless it's critical`() {
        assertFalse(SpeechQueuePolicy.flushes(alert(AlertPriority.WARNING), listOf(alert(guidance = true))))
        assertTrue(SpeechQueuePolicy.flushes(alert(AlertPriority.CRITICAL), listOf(alert(AlertPriority.WARNING))))
    }

    @Test
    fun `with nothing playing there's nothing to cut off either way`() {
        assertTrue(SpeechQueuePolicy.flushes(alert(guidance = true), emptyList()))
        assertFalse(SpeechQueuePolicy.flushes(alert(), emptyList()))
    }
}
