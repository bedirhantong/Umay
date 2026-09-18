package com.appvalence.umay.positioning

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.appvalence.umay.coremodel.BleBeaconReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

sealed class BleScanState {
    data object Idle : BleScanState()
    data object Scanning : BleScanState()
    data class Failed(val reason: String) : BleScanState()
}

/**
 * On-site beacon discovery tool (NFR-6 BLE fallback groundwork): scans for
 * every nearby BLE advertiser and reports a stable [BleBeaconReading] for
 * each one, iBeacon-decoded where possible. This is deliberately *not* a
 * [PositioningEngine] yet — trilateration needs each [BleBeaconReading.beaconId]
 * mapped to a surveyed physical position, which can only be done by walking
 * the building. Once that map exists, a `BleTrilaterationPositioningEngine`
 * can be built on top of [beaconUpdates] without changing this class.
 *
 * Callers must hold ACCESS_FINE_LOCATION (all API levels) and, on API 31+,
 * BLUETOOTH_SCAN before calling [start] — this class does not check app
 * permissions itself, but it does surface the other reasons a scan silently
 * returns nothing (Bluetooth off, device Location services off, or the
 * platform's scan client registration failing) via [scanState] instead of
 * failing silently (NFR-3: prefer a visible "not sure" state over silence).
 */
class BleBeaconScanner(private val context: Context) {

    private val sightings = MutableStateFlow<Map<String, BleBeaconReading>>(emptyMap())
    private val state = MutableStateFlow<BleScanState>(BleScanState.Idle)
    private var isScanning = false

    // One event per detection, unlike beaconUpdates() which only ever shows the
    // latest reading per beacon. BeaconSurveySolver needs the whole time series
    // (many readings per beacon, each tagged with when it arrived) to match
    // against the PDR trajectory poses recorded at those same moments.
    private val rawReadings = MutableSharedFlow<BleBeaconReading>(extraBufferCapacity = 64)

    // BLE RSSI fast-fades by roughly +/-4-6dB from multipath even when both
    // ends are stationary; a single raw sample makes the distance formula's
    // exponential term swing wildly. A per-beacon Kalman filter smooths this
    // optimally (see KalmanFilter1D) instead of a fixed-ratio EMA.
    private val rssiFilters = mutableMapOf<String, KalmanFilter1D>()

    fun beaconUpdates(): Flow<List<BleBeaconReading>> =
        sightings.map { it.values.sortedByDescending { reading -> reading.rssi } }

    fun scanState(): Flow<BleScanState> = state

    fun rawReadingUpdates(): Flow<BleBeaconReading> = rawReadings.asSharedFlow()

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val reading = result.toBeaconReading() ?: return
            val now = reading.timestampMs
            sightings.value = (sightings.value + (reading.beaconId to reading))
                .filterValues { now - it.timestampMs <= STALE_THRESHOLD_MS }
            rawReadings.tryEmit(reading)
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            state.value = BleScanState.Failed("Tarama başarısız (kod $errorCode)")
            Log.e(TAG, "BLE scan failed with error code $errorCode")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (isScanning) return

        val locationManager = context.getSystemService(LocationManager::class.java)
        if (locationManager == null || !LocationManagerCompat.isLocationEnabled(locationManager)) {
            state.value = BleScanState.Failed("Cihazın Konum (GPS) servisi kapalı — BLE taraması için açık olmalı")
            return
        }

        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            state.value = BleScanState.Failed("Bluetooth kapalı")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            state.value = BleScanState.Failed("BLE taraması bu cihazda desteklenmiyor")
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, scanCallback)
            isScanning = true
            state.value = BleScanState.Scanning
        } catch (e: SecurityException) {
            state.value = BleScanState.Failed("İzin reddedildi: ${e.message}")
            Log.e(TAG, "BLE scan start failed", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!isScanning) {
            state.value = BleScanState.Idle
            return
        }
        try {
            context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
                ?.stopScan(scanCallback)
        } catch (e: SecurityException) {
            Log.e(TAG, "BLE scan stop failed", e)
        }
        isScanning = false
        state.value = BleScanState.Idle
        sightings.value = emptyMap()
        rssiFilters.clear()
    }

    private fun ScanResult.toBeaconReading(): BleBeaconReading? {
        val manufacturerData = scanRecord?.getManufacturerSpecificData(APPLE_MANUFACTURER_ID)
        val iBeacon = manufacturerData?.let(::parseIBeacon)

        val macAddress = device.address ?: return null
        // Only an iBeacon's "measured power" byte is a calibrated 1-meter reference.
        // A generic ScanRecord.txPowerLevel is the radio's raw transmit power, a
        // different quantity entirely — feeding it into the distance formula below
        // produces nonsense results (multi-kilometer "distances").
        val txPower = iBeacon?.txPowerAtOneMeter
        val beaconId = iBeacon?.let { "${it.uuid}-${it.major}-${it.minor}" } ?: macAddress

        val filter = rssiFilters.getOrPut(beaconId) { KalmanFilter1D() }
        val smoothedRssiInt = filter.update(rssi.toDouble()).roundToInt()

        return BleBeaconReading(
            beaconId = beaconId,
            macAddress = macAddress,
            deviceName = scanRecord?.deviceName,
            uuid = iBeacon?.uuid,
            major = iBeacon?.major,
            minor = iBeacon?.minor,
            rssi = smoothedRssiInt,
            txPowerAtOneMeter = txPower,
            estimatedDistanceMeters = BleDistanceEstimator.estimateMeters(smoothedRssiInt, txPower),
            timestampMs = System.currentTimeMillis(),
        )
    }

    private data class ParsedIBeacon(val uuid: String, val major: Int, val minor: Int, val txPowerAtOneMeter: Int)

    private fun parseIBeacon(data: ByteArray): ParsedIBeacon? {
        if (data.size < IBEACON_PAYLOAD_LENGTH) return null
        if (data[0] != IBEACON_TYPE_BYTE || data[1] != IBEACON_LENGTH_BYTE) return null

        val uuidBytes = data.copyOfRange(2, 18)
        val uuid = buildString {
            uuidBytes.forEachIndexed { index, byte ->
                if (index in UUID_DASH_POSITIONS) append('-')
                append("%02x".format(byte))
            }
        }
        val major = ((data[18].toInt() and 0xFF) shl 8) or (data[19].toInt() and 0xFF)
        val minor = ((data[20].toInt() and 0xFF) shl 8) or (data[21].toInt() and 0xFF)
        val txPower = data[22].toInt()
        return ParsedIBeacon(uuid, major, minor, txPower)
    }

    companion object {
        private const val TAG = "BleBeaconScanner"
        private const val APPLE_MANUFACTURER_ID = 0x004C
        private const val IBEACON_TYPE_BYTE: Byte = 0x02
        private const val IBEACON_LENGTH_BYTE: Byte = 0x15
        private const val IBEACON_PAYLOAD_LENGTH = 23
        private val UUID_DASH_POSITIONS = setOf(4, 6, 8, 10)
        private const val STALE_THRESHOLD_MS = 15_000L
    }
}
