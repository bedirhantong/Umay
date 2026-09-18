package com.appvalence.umay.positioning.ble

/** What a filtered BLE scan should look for (see [BeaconScanTargets.forBeaconIds]). */
sealed interface ScanTarget {
    /** Any iBeacon advertising this proximity UUID, whatever its major/minor. */
    data class IBeaconUuid(val uuid: String) : ScanTarget

    /** One specific non-iBeacon advertiser, by MAC address. */
    data class DeviceAddress(val address: String) : ScanTarget
}

/**
 * Turns the beacon ids a map was surveyed with ("uuid-major-minor" for
 * iBeacons, a MAC address otherwise — see [BleBeaconScanner]) into scan
 * filters.
 *
 * Filtering isn't an optimization here, it's what keeps positioning alive
 * with the screen off: since Android 8.1 an *unfiltered* scan is paused
 * while the screen is off, and a blind user walking with the phone locked
 * in a pocket is the normal case, not the edge case. A building's beacons
 * almost always share one UUID, so this is usually a single filter.
 */
object BeaconScanTargets {
    /**
     * Hardware filter slots are limited (commonly 16-32, shared with other apps);
     * beyond this, filtering is abandoned in favor of an unfiltered scan that at
     * least works with the screen on.
     */
    const val MAX_FILTERS = 16

    private val MAC_ADDRESS = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")
    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Null means "scan unfiltered": nothing to filter on, or too many targets to fit. */
    fun forBeaconIds(beaconIds: Collection<String>): List<ScanTarget>? {
        val targets = beaconIds.mapNotNull(::parse).distinct()
        return targets.takeIf { it.isNotEmpty() && it.size <= MAX_FILTERS }
    }

    private fun parse(beaconId: String): ScanTarget? {
        if (MAC_ADDRESS.matches(beaconId)) return ScanTarget.DeviceAddress(beaconId.uppercase())
        // "uuid-major-minor": the UUID itself contains four dashes, so take everything
        // before the last two segments.
        val parts = beaconId.split('-')
        if (parts.size != 7) return null
        val uuid = parts.take(5).joinToString("-")
        if (!UUID.matches(uuid) || parts[5].toIntOrNull() == null || parts[6].toIntOrNull() == null) return null
        return ScanTarget.IBeaconUuid(uuid.lowercase())
    }
}
