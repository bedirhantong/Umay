package com.appvalence.umay.coremodel

/**
 * Printed QR markers stuck at named places: when the camera sees one, the
 * phone knows exactly which place it's at. A marker holds only "UMAY1:" and
 * the place's node id — nothing about the building or the person — so a
 * marker on a wall says nothing to anyone without that building's map.
 */
object PlaceMarkers {
    private const val PREFIX = "UMAY1:"

    /** The QR code's side, printed (see the editor's marker sheet); distance is judged from it. */
    const val PRINTED_SIDE_METERS = 0.15

    fun contentFor(nodeId: String): String = PREFIX + nodeId

    /** The node id a marker points to; null for any other QR code. */
    fun nodeIdOf(content: String): String? = content.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it.isNotBlank() }
}

/** A marker the camera saw: which place it's at, and how far and in which direction from the camera. */
data class MarkerSighting(
    val nodeId: String,
    val distanceMeters: Double,
    /** Degrees right of where the camera points (negative: left). */
    val bearingDeg: Double,
    val timestampMs: Long,
)
