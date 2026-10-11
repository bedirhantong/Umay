package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.NavigationAlert
import com.appvalence.umay.feedback.SpeechQueuePolicy
import kotlin.math.roundToLong

/**
 * The phone's voice as the walker hears it: words take time to say, and the
 * walker acts on a sentence once it has been said, walking on meanwhile ("Sağa
 * dön." is taken in while the rest of the alert still plays). As in
 * TtsFeedbackController, an alert waits for the ones before it or cuts them
 * off ([SpeechQueuePolicy]); what's cut off is never heard.
 */
internal class SpokenQueue(private val charsPerSecond: Double = TTS_CHARS_PER_SECOND) {
    /** One sentence of an alert; [opensAlert] for its first. Its saying began at [startedAtMs]. */
    class Sentence(val text: String, val opensAlert: Boolean, val startedAtMs: Long)

    private class Utterance(val alert: NavigationAlert, val sentence: Sentence, val endsAtMs: Long)

    private val queue = ArrayDeque<Utterance>()
    private var busyUntilMs = 0L

    fun say(alert: NavigationAlert, nowMs: Long) {
        if (SpeechQueuePolicy.flushes(alert, queue.map { it.alert }.distinct())) {
            queue.clear()
            busyUntilMs = nowMs
        }
        var endsAt = maxOf(nowMs, busyUntilMs)
        SENTENCE_END.split(alert.message.trim()).forEachIndexed { i, text ->
            val startsAt = endsAt
            endsAt += durationMs(text)
            queue.addLast(Utterance(alert, Sentence(text, opensAlert = i == 0, startedAtMs = startsAt), endsAt))
        }
        busyUntilMs = endsAt
    }

    /** Whether anything is still to be said after [nowMs]. */
    fun isSpeaking(nowMs: Long): Boolean = queue.isNotEmpty() && queue.last().endsAtMs > nowMs

    /** Sentences finished by [nowMs], oldest first. */
    fun heard(nowMs: Long): List<Sentence> {
        val done = ArrayList<Sentence>()
        while (queue.isNotEmpty() && queue.first().endsAtMs <= nowMs) done += queue.removeFirst().sentence
        return done
    }

    private fun durationMs(text: String): Long = if (charsPerSecond.isInfinite()) 0L else (text.length / charsPerSecond * 1000).roundToLong()

    companion object {
        /**
         * Turkish TTS at the default rate: about 2.5 words, 15 characters a second. An
         * estimate, on the slow side; the field logs (speech timestamps) will tell.
         */
        const val TTS_CHARS_PER_SECOND = 15.0

        /** Every sentence heard the moment it's said (the simulation before 0.15). */
        val INSTANT = Double.POSITIVE_INFINITY

        // After a full stop, question or exclamation mark, before the next sentence.
        private val SENTENCE_END = Regex("""(?<=[.!?])\s+""")
    }
}
