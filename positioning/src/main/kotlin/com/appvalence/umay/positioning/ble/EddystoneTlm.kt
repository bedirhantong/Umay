package com.appvalence.umay.positioning.ble

/**
 * Eddystone telemetry (TLM): the frame many beacons interleave with their
 * iBeacon advertisements to report battery, temperature and uptime. Only the
 * unencrypted version is readable, and a beacon may report 0 for "no battery
 * reading". https://github.com/google/eddystone/blob/master/eddystone-tlm/tlm-plain.md
 */
object EddystoneTlm {
    private const val FRAME_TYPE_TLM: Byte = 0x20
    private const val VERSION_PLAIN: Byte = 0x00
    private const val MIN_LENGTH = 4

    /** Battery voltage in mV from the 0xFEAA service data of a plain TLM frame; null otherwise. */
    fun batteryMillivolts(serviceData: ByteArray): Int? {
        if (serviceData.size < MIN_LENGTH) return null
        if (serviceData[0] != FRAME_TYPE_TLM || serviceData[1] != VERSION_PLAIN) return null
        val millivolts = ((serviceData[2].toInt() and 0xFF) shl 8) or (serviceData[3].toInt() and 0xFF)
        return millivolts.takeIf { it > 0 }
    }
}
