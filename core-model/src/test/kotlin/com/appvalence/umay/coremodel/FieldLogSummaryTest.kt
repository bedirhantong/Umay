package com.appvalence.umay.coremodel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldLogSummaryTest {
    private fun mark(t: Long, name: String, error: Double?) = FieldLogJson.encode(
        GroundTruthEvent(t, "id-$name", name, 0, 0.0, 0.0, if (error == null) null else 1.0, 0.0, if (error == null) 1 else 0, error),
    )

    @Test
    fun `mean and worst come from the marks, with the worst place's name`() {
        val lines = sequenceOf(
            FieldLogJson.encode(StatusEvent(0, "ON_ROUTE")),
            mark(1, "Giriş", 1.0),
            mark(2, "Kantin", 4.5),
            mark(3, "Kütüphane", 1.5),
        )

        val summary = FieldLogSummary.of(lines)

        assertEquals(3, summary.marks)
        assertEquals(2.333, summary.meanErrorMeters!!, 0.001)
        assertEquals(4.5, summary.worstErrorMeters)
        assertEquals("Kantin", summary.worstPlaceName)
    }

    @Test
    fun `a mark on the wrong floor is counted but has no error`() {
        val summary = FieldLogSummary.of(sequenceOf(mark(1, "Merdiven", null), mark(2, "Ofis", 2.0)))

        assertEquals(2, summary.marks)
        assertEquals(1, summary.wrongFloor)
        assertEquals(2.0, summary.meanErrorMeters)
    }

    @Test
    fun `a log without marks, or cut short mid-line, still summarizes`() {
        val summary = FieldLogSummary.of(sequenceOf(mark(1, "Giriş", 1.0), """{"type":"ground_truth","t":2,"nodeId":"x""""))

        assertEquals(1, summary.marks)
        val none = FieldLogSummary.of(emptySequence())
        assertEquals(0, none.marks)
        assertNull(none.meanErrorMeters)
        assertNull(none.worstPlaceName)
    }
}
