package com.appvalence.umay.perception

import kotlin.test.Test
import kotlin.test.assertEquals

class SignTextFormattingTest {

    @Test
    fun `keeps real words and phrases as-is`() {
        assertEquals(listOf("203 Sınıfı", "Çıkış"), SignTextFormatting.clean(listOf("203 Sınıfı", "Çıkış")))
    }

    @Test
    fun `drops single-character blocks, the usual texture misreads`() {
        assertEquals(listOf("Giriş"), SignTextFormatting.clean(listOf("I", "Giriş", "-")))
    }

    @Test
    fun `keeps two-character blocks — short but real, like a floor number`() {
        assertEquals(listOf("B2"), SignTextFormatting.clean(listOf("B2")))
    }

    @Test
    fun `trims surrounding whitespace before filtering length`() {
        assertEquals(listOf("Asansör"), SignTextFormatting.clean(listOf("  Asansör  ", " a ")))
    }

    @Test
    fun `drops duplicate blocks but keeps first-seen order`() {
        assertEquals(listOf("Çıkış", "203"), SignTextFormatting.clean(listOf("Çıkış", "203", "Çıkış")))
    }

    @Test
    fun `nothing in, nothing out`() {
        assertEquals(emptyList(), SignTextFormatting.clean(emptyList()))
    }

    @Test
    fun `blank blocks are dropped like any other noise`() {
        assertEquals(emptyList(), SignTextFormatting.clean(listOf("", "  ")))
    }
}
