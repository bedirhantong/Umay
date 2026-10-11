package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.FloorChangePhase
import com.appvalence.umay.positioning.baro.BarometricFloorChangeDetector

/**
 * Decides when a floor change the route is waiting for has happened, so
 * positioning can move to the new floor: from air pressure on a phone with a
 * barometer (stairs and elevators alike), from footsteps on stairs otherwise
 * ([FloorChangeHint.autoConfirm]), and always from the walker's own "Kat
 * değiştirdim" ([manual]). Pure, so the simulated walks run exactly this.
 *
 * Feed it every navigation state ([onState]) and every pressure reading
 * ([onPressure]); each returns a [Confirmed] when the change should be
 * applied. Not thread-safe.
 */
class FloorChangeConfirmation(
    private val hasBarometer: Boolean,
    private val detector: BarometricFloorChangeDetector = BarometricFloorChangeDetector(),
) {
    data class Confirmed(val targetNodeId: String, val targetFloor: Int, val phase: FloorChangePhase)

    private var pending: FloorChangeHint? = null

    // A crossing the barometer already confirmed: the hint lingers until the next
    // footstep gives a position on the new floor, and mustn't re-arm meanwhile.
    private var barometerConfirmedTargetId: String? = null

    /** Altitude climbed since the crossing was announced, for display; null when none is pending. */
    fun climbedMeters(): Double? = detector.climbedMeters()

    fun onState(state: NavigationState): Confirmed? {
        // The barometer listens for the crossing at the end of this leg already: someone
        // waiting at a lift may be well ahead of where guidance places them.
        val hint = state.pendingFloorChange ?: state.upcomingFloorChange
        pending = hint
        if (hint == null) {
            detector.disarm()
            barometerConfirmedTargetId = null
            return null
        }
        if (hasBarometer) {
            if (hint.targetNodeId != barometerConfirmedTargetId) detector.arm(fromFloor = state.position.floor, toFloor = hint.targetFloor)
            return null
        }
        // No barometer: a STAIRS crossing confirms itself once enough footsteps have piled
        // up (NavigationOrchestrator.AUTO_FLOOR_CHANGE_STEP_COUNT) — no tap needed, which
        // matters since a blind user's hands may well be full on a staircase. Elevators
        // never set autoConfirm; those wait for the button.
        return if (hint.autoConfirm && hint == state.pendingFloorChange) Confirmed(hint.targetNodeId, hint.targetFloor, FloorChangePhase.CONFIRMED_AUTOMATICALLY) else null
    }

    fun onPressure(hPa: Double, timestampMs: Long): Confirmed? {
        if (!detector.onPressure(hPa, timestampMs)) return null
        val hint = pending ?: return null
        barometerConfirmedTargetId = hint.targetNodeId
        return Confirmed(hint.targetNodeId, hint.targetFloor, FloorChangePhase.CONFIRMED_BY_BAROMETER)
    }

    /** The walker says they've changed floor; null when no change is pending. */
    fun manual(): Confirmed? = pending?.let { Confirmed(it.targetNodeId, it.targetFloor, FloorChangePhase.CONFIRMED_MANUALLY) }
}
