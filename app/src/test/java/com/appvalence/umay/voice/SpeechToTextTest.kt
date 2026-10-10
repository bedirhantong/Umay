package com.appvalence.umay.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechToTextTest {

    @Test
    fun `an installed language is ready, however the recognizer spells its tag`() {
        assertEquals(OnDeviceLanguageState.INSTALLED, SpeechToText.languageState("tr", listOf("en-US", "tr-TR"), emptyList(), emptyList()))
        assertEquals(OnDeviceLanguageState.INSTALLED, SpeechToText.languageState("tr", listOf("tr"), emptyList(), emptyList()))
    }

    @Test
    fun `a language being downloaded, or only downloadable, is not ready yet`() {
        assertEquals(OnDeviceLanguageState.DOWNLOADING, SpeechToText.languageState("tr", listOf("en-US"), listOf("tr-TR"), listOf("tr-TR")))
        assertEquals(OnDeviceLanguageState.DOWNLOADABLE, SpeechToText.languageState("tr", listOf("en-US"), emptyList(), listOf("tr-TR")))
    }

    @Test
    fun `a language the on-device recognizer can't do is unsupported, never sent online instead`() {
        assertEquals(OnDeviceLanguageState.UNSUPPORTED, SpeechToText.languageState("tr", listOf("en-US"), emptyList(), listOf("en-GB")))
    }

    @Test
    fun `with offline-only recognition, network errors mean recognition isn't available`() {
        assertEquals(SpeechRecognitionFailure.NOT_AVAILABLE, SpeechToText.failureFor(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SpeechRecognitionFailure.NOT_AVAILABLE, SpeechToText.failureFor(SpeechRecognizer.ERROR_SERVER))
        assertEquals(SpeechRecognitionFailure.LANGUAGE_NOT_SUPPORTED, SpeechToText.failureFor(12))
        assertEquals(SpeechRecognitionFailure.NO_SPEECH, SpeechToText.failureFor(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SpeechRecognitionFailure.PERMISSION_DENIED, SpeechToText.failureFor(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
    }
}
