package com.appvalence.umay.positioning.ble

import com.appvalence.umay.coremodel.BleBeaconReading
import kotlinx.coroutines.flow.Flow

/** BLE beacon discovery, as the rest of the app sees it (see [BleBeaconScanner]). */
interface BeaconScanner {
    /** The latest reading per beacon seen recently, strongest first. */
    fun beaconUpdates(): Flow<List<BleBeaconReading>>

    /** Every single sighting, in order. */
    fun rawReadingUpdates(): Flow<BleBeaconReading>

    fun scanState(): Flow<BleScanState>

    /** [targets] limits the scan to known beacons; null scans for every advertiser. */
    fun start(targets: List<ScanTarget>? = null)

    fun stop()
}
