package com.appvalence.umay.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirestoreBuildingMapRepositoryTest {

    @Test
    fun `document ids are safe regardless of what a human typed as a building name`() {
        val id = sanitizeBuildingDocumentId("Lab/ofis")

        assertTrue(id.matches(Regex("Lab_ofis-[0-9a-f]{8}")), id)
    }

    @Test
    fun `non-ASCII characters never reach the document id`() {
        val id = sanitizeBuildingDocumentId("Şube İç")

        assertTrue(id.matches(Regex("[A-Za-z0-9_-]+")), id)
    }

    @Test
    fun `the same building name always sanitizes to the same id`() {
        assertEquals(sanitizeBuildingDocumentId("Lab ofis"), sanitizeBuildingDocumentId("Lab ofis"))
    }

    @Test
    fun `two names that sanitize the same way still get distinct ids`() {
        val a = sanitizeBuildingDocumentId("Şube")
        val b = sanitizeBuildingDocumentId("Çube")

        assertTrue(a != b, "both sanitize to the same ASCII prefix but must not collide: $a vs $b")
    }

    @Test
    fun `a blank building name still produces a usable id`() {
        assertTrue(sanitizeBuildingDocumentId("   ").startsWith("building-"))
    }

    @Test
    fun `network-flavored error messages are recognized as no connection`() {
        assertTrue(looksLikeNoConnection(RuntimeException("UNAVAILABLE: io exception")))
        assertTrue(looksLikeNoConnection(RuntimeException("Unable to resolve host: network is unreachable")))
        assertTrue(looksLikeNoConnection(RuntimeException("client is offline")))
    }

    @Test
    fun `an unrelated server error is not mistaken for no connection`() {
        assertFalse(looksLikeNoConnection(RuntimeException("PERMISSION_DENIED: Missing or insufficient permissions")))
    }
}
