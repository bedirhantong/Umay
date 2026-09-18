package com.appvalence.umay.positioning.engine

import android.content.Context
import androidx.core.uwb.RangingParameters
import androidx.core.uwb.RangingResult
import androidx.core.uwb.UwbAddress
import androidx.core.uwb.UwbDevice
import androidx.core.uwb.UwbManager
import com.appvalence.umay.coremodel.UwbRangeReading
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** A fixed UWB anchor's identity: [anchorId] must match the [com.appvalence.umay.coremodel.MapNode.id] surveyed for it. */
data class UwbAnchorConfig(
    val anchorId: String,
    val uwbAddress: UwbAddress,
)

/**
 * Ranges the phone (acting as UWB controller) against a fixed set of anchors
 * (FR-1) via Jetpack UWB. [sessionId]/[sessionKeyInfo] must match whatever
 * out-of-band provisioning (e.g. BLE handshake) the physical anchors were
 * configured with — that provisioning protocol is anchor-hardware-specific
 * and intentionally out of scope here (see plan section 11).
 *
 * This talks to real UWB hardware and cannot be exercised without it; the
 * distance math it feeds ([Trilateration]) is unit-tested separately.
 */
class UwbRangingSource(
    private val context: Context,
    private val sessionId: Int,
    private val sessionKeyInfo: ByteArray,
    private val anchors: List<UwbAnchorConfig>,
) {
    fun rangeReadings(): Flow<UwbRangeReading> = flow {
        val uwbManager = UwbManager.createInstance(context)
        val sessionScope = uwbManager.controllerSessionScope()
        val addressToAnchorId = anchors.associate { it.uwbAddress.address.toList() to it.anchorId }

        val parameters = RangingParameters(
            uwbConfigType = RangingParameters.CONFIG_MULTICAST_DS_TWR,
            sessionId = sessionId,
            subSessionId = 0,
            sessionKeyInfo = sessionKeyInfo,
            subSessionKeyInfo = ByteArray(0),
            complexChannel = sessionScope.uwbComplexChannel,
            peerDevices = anchors.map { UwbDevice(it.uwbAddress) },
            updateRateType = RangingParameters.RANGING_UPDATE_RATE_FREQUENT,
        )

        sessionScope.prepareSession(parameters).collect { result ->
            if (result is RangingResult.RangingResultPosition) {
                val anchorId = addressToAnchorId[result.device.address.address.toList()]
                val distanceMeters = result.position.distance?.value?.toDouble()
                if (anchorId != null && distanceMeters != null) {
                    emit(
                        UwbRangeReading(
                            anchorId = anchorId,
                            distanceMeters = distanceMeters,
                            distanceStdDevMeters = ASSUMED_DISTANCE_STD_DEV_METERS,
                            lineOfSight = true,
                            timestampMs = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }
    }

    companion object {
        // Jetpack UWB 1.0.0 does not surface a line-of-sight flag; NLOS degradation
        // is left to be modeled once real anchor data is available to calibrate it.
        const val ASSUMED_DISTANCE_STD_DEV_METERS = 0.1
    }
}
