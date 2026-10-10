package com.appvalence.umay.survey.ar

import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Test

class ArAvailabilityTest {

    @Test
    fun `only a phone ARCore says can't is unsupported, a check without an answer can be retried`() {
        assertEquals(ArAvailability.UNSUPPORTED, availabilityOf(ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE))
        assertEquals(ArAvailability.UNKNOWN, availabilityOf(ArCoreApk.Availability.UNKNOWN_TIMED_OUT))
        assertEquals(ArAvailability.UNKNOWN, availabilityOf(ArCoreApk.Availability.UNKNOWN_ERROR))
        assertEquals(ArAvailability.NEEDS_INSTALL, availabilityOf(ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED))
        assertEquals(ArAvailability.READY, availabilityOf(ArCoreApk.Availability.SUPPORTED_INSTALLED))
    }
}
