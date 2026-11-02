package com.appvalence.umay.survey

import com.appvalence.umay.common.metricScaleMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetricScaleTest {
    @Test fun `scale stays within its pixel budget from building overview to centimeter zoom`() {
        for (scale in listOf(0.1, 0.7, 10.0, 37.0, 100.0, 1_000.0, 10_000.0)) {
            val meters = metricScaleMeters(scale, 80.0)
            assertTrue(meters > 0)
            assertTrue("Scale bar exceeded its screen budget", meters * scale <= 80.0 + 1e-9)
            assertTrue("Scale bar is too short to read", meters * scale >= 16.0 - 1e-9)
        }
        assertEquals(0.005, metricScaleMeters(10_000.0, 80.0), 1e-12)
    }
}
