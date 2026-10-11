package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.ObstacleKind
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

enum class TurnDirection { LEFT, RIGHT }

/**
 * A turn at the end of a leg: [angleDeg] is how far the new heading is from the
 * old one, positive to the right (clockwise), within -180..180.
 */
data class Turn(val angleDeg: Double) {
    val direction: TurnDirection get() = if (angleDeg >= 0) TurnDirection.RIGHT else TurnDirection.LEFT

    /**
     * The same turn on a clock face with 12 straight ahead: 3 is a right angle to
     * the right, 9 to the left, 6 straight back. Rounded half away from zero so a
     * 45° turn is 2 o'clock to the right and 10 to the left, mirror images.
     */
    val clockHour: Int
        get() {
            val hours = sign(angleDeg) * floor(abs(angleDeg) / 30.0 + 0.5)
            val hour = ((hours.toInt() % 12) + 12) % 12
            return if (hour == 0) 12 else hour
        }

    companion object {
        /** A wall alongside, as a clock hour (left: 9, right: 3). */
        fun clockHourOf(side: TurnDirection): Int = if (side == TurnDirection.LEFT) 9 else 3
    }
}

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
    fun walkToward(placeName: String, distanceMeters: Double, thenTurn: Turn?): String

    /** Toward a node nobody named — a bend in the corridor a camera survey added — optionally turning there. */
    fun walkAhead(distanceMeters: Double, thenTurn: Turn?): String

    /** A wall runs along this side of the leg: follow it ("shorelining"). Said after the leg's instruction. */
    fun followWall(side: TurnDirection): String

    /** Turn here, at the place the walker is reaching: said before the rest of the leg. */
    fun turnNow(turn: Turn): String

    /** Turn around: a new route starts behind the walker. Said before the leg. */
    fun turnAround(): String

    /** The final leg, to the destination itself. */
    fun approachDestination(distanceMeters: Double): String

    /** The leg that crosses floors, ending at [arrivalPlaceName] on [targetFloor]. */
    fun changeFloor(method: FloorChangeMethod, goingUp: Boolean, targetFloor: Int, arrivalPlaceName: String): String

    /** A warning about a tagged hazard at the place being approached; null when [type] isn't one. */
    fun hazardAhead(type: NodeType): String?

    fun arrived(): String

    fun noRoute(): String

    fun rerouted(): String

    /** Stairs were to be avoided, but every route to the destination takes them. */
    fun noStepFreeRoute(): String

    /** Left the route (another corridor): a new one was planned from here. */
    fun offRoute(): String

    /** Heading back the way they came. */
    fun wrongWay(): String

    /** There's room to walk past what's ahead on this [side]. */
    fun passObstacle(side: TurnDirection): String

    /** Something the camera saw ahead, by what it is ([ObstacleKind.UNKNOWN]: just "an obstacle"). */
    fun obstacle(priority: AlertPriority, kind: ObstacleKind, distanceMeters: Double): String
}
