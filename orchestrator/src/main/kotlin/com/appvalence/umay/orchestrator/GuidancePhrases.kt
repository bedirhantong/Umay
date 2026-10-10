package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.ObstacleKind

enum class TurnDirection { LEFT, RIGHT }

/** How a floor crossing is made, from how its arrival node was tagged during the survey. */
enum class FloorChangeMethod { STAIRS, ELEVATOR, UNKNOWN }

/**
 * Every sentence [NavigationOrchestrator] speaks, requested by *meaning*
 * ("walk toward X for N meters, then turn right") rather than written out.
 * The orchestrator decides what to say; an implementation decides how to
 * say it in one language. This is what makes guidance translatable: the app
 * implements it on top of string resources, so a new language means new
 * strings.xml entries, not new code here.
 *
 * Implementations get raw distances and decide their own rounding and
 * phrasing — "4 metre" reads naturally where "4.0 metre" is spoken as
 * "four point zero".
 */
interface GuidancePhrases {
    /** Toward a named place, optionally turning once there. */
    fun walkToward(placeName: String, distanceMeters: Double, thenTurn: TurnDirection?): String

    /** Toward a node nobody named — a bend in the corridor a camera survey added — optionally turning there. */
    fun walkAhead(distanceMeters: Double, thenTurn: TurnDirection?): String

    /** The final leg, to the destination itself. */
    fun approachDestination(distanceMeters: Double): String

    /** The leg that crosses floors, ending at [arrivalPlaceName] on [targetFloor]. */
    fun changeFloor(method: FloorChangeMethod, goingUp: Boolean, targetFloor: Int, arrivalPlaceName: String): String

    /** A warning about a tagged hazard at the place being approached; null when [type] isn't one. */
    fun hazardAhead(type: NodeType): String?

    fun arrived(): String

    fun noRoute(): String

    fun rerouted(): String

    /** Something the camera saw ahead, by what it is ([ObstacleKind.UNKNOWN]: just "an obstacle"). */
    fun obstacle(priority: AlertPriority, kind: ObstacleKind, distanceMeters: Double): String
}
