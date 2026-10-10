package com.appvalence.umay.feedback

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VoiceSelectionTest {

    private val turkish = Locale.forLanguageTag("tr-TR")

    private fun voice(name: String, tag: String, quality: Int = 300, network: Boolean = false, installed: Boolean = true) =
        VoiceCandidate(name, Locale.forLanguageTag(tag), quality, network, installed)

    @Test
    fun `picks a voice in the text's language, never the device's`() {
        val voices = listOf(voice("en-high", "en-US", quality = 500), voice("tr-normal", "tr-TR", quality = 300))

        assertEquals("tr-normal", VoiceSelection.pick(voices, turkish)?.name)
    }

    @Test
    fun `prefers an on-device voice over a better network one`() {
        val voices = listOf(voice("tr-network", "tr-TR", quality = 500, network = true), voice("tr-local", "tr-TR", quality = 300))

        assertEquals("tr-local", VoiceSelection.pick(voices, turkish)?.name)
    }

    @Test
    fun `never picks a network voice, which would send every sentence to a server`() {
        assertNull(VoiceSelection.pick(listOf(voice("tr-network", "tr-TR", network = true)), turkish))
    }

    @Test
    fun `prefers the highest quality among otherwise equal voices`() {
        val voices = listOf(voice("tr-low", "tr-TR", quality = 200), voice("tr-high", "tr-TR", quality = 400))

        assertEquals("tr-high", VoiceSelection.pick(voices, turkish)?.name)
    }

    @Test
    fun `ignores voices whose data isn't downloaded`() {
        val voices = listOf(voice("tr-missing", "tr-TR", quality = 500, installed = false), voice("tr-ok", "tr-TR", quality = 200))

        assertEquals("tr-ok", VoiceSelection.pick(voices, turkish)?.name)
    }

    @Test
    fun `nothing when no voice speaks the language`() {
        assertNull(VoiceSelection.pick(listOf(voice("en", "en-US")), turkish))
    }
}
