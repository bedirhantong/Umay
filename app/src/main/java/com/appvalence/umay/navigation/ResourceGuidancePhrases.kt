package com.appvalence.umay.navigation

import android.content.Context
import com.appvalence.umay.R
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.orchestrator.FloorChangeMethod
import com.appvalence.umay.orchestrator.GuidancePhrases
import com.appvalence.umay.orchestrator.Turn
import com.appvalence.umay.orchestrator.TurnDirection
import kotlin.math.roundToInt

/**
 * Spoken guidance worded from string resources, so it's translated the same
 * way as the rest of the app — and spoken in the same language as the voice
 * reading it (see speech_language_tag).
 *
 * The Turkish sentences are built so a place name never needs a case suffix
 * ("Kapı yönünde…", not "Kapı'ya…"): the right suffix depends on how a name
 * the surveyor typed ends and is pronounced (Kapı'ya, Ofis'e, 203'e…), which
 * can't be guessed reliably, and a wrong one is exactly what makes speech
 * sound machine-made.
 *
 * [clockDirections] says turns and wall sides as clock hours ("saat 2
 * yönüne dön") instead of left and right — the convention many blind
 * walkers learn in orientation and mobility training (Settings).
 */
class ResourceGuidancePhrases(context: Context, private val clockDirections: Boolean = false) : GuidancePhrases {
    private val resources = context.applicationContext.resources

    private fun meters(distanceMeters: Double): String {
        // Whole meters: nobody paces out tenths, and "4.0" gets read as "four point zero".
        val rounded = distanceMeters.roundToInt().coerceAtLeast(1)
        return resources.getQuantityString(R.plurals.say_meters, rounded, rounded)
    }

    override fun walkToward(placeName: String, distanceMeters: Double, thenTurn: Turn?): String = when {
        thenTurn == null -> resources.getString(R.string.say_walk_straight, placeName, meters(distanceMeters))
        clockDirections -> resources.getString(R.string.say_walk_then_clock, placeName, meters(distanceMeters), thenTurn.clockHour)
        thenTurn.direction == TurnDirection.RIGHT -> resources.getString(R.string.say_walk_then_right, placeName, meters(distanceMeters))
        else -> resources.getString(R.string.say_walk_then_left, placeName, meters(distanceMeters))
    }

    override fun walkAhead(distanceMeters: Double, thenTurn: Turn?): String = when {
        thenTurn == null -> resources.getString(R.string.say_walk_ahead, meters(distanceMeters))
        clockDirections -> resources.getString(R.string.say_walk_ahead_then_clock, meters(distanceMeters), thenTurn.clockHour)
        thenTurn.direction == TurnDirection.RIGHT -> resources.getString(R.string.say_walk_ahead_then_right, meters(distanceMeters))
        else -> resources.getString(R.string.say_walk_ahead_then_left, meters(distanceMeters))
    }

    override fun followWall(side: TurnDirection): String = when {
        clockDirections -> resources.getString(R.string.say_wall_clock, Turn.clockHourOf(side))
        side == TurnDirection.LEFT -> resources.getString(R.string.say_wall_left)
        else -> resources.getString(R.string.say_wall_right)
    }

    override fun turnNow(turn: Turn): String = when {
        clockDirections -> resources.getString(R.string.say_turn_clock_now, turn.clockHour)
        turn.direction == TurnDirection.RIGHT -> resources.getString(R.string.say_turn_right_now)
        else -> resources.getString(R.string.say_turn_left_now)
    }

    override fun turnAround(): String =
        if (clockDirections) resources.getString(R.string.say_turn_clock_now, 6) else resources.getString(R.string.say_turn_around)

    override fun approachDestination(distanceMeters: Double): String =
        resources.getString(R.string.say_approach_destination, meters(distanceMeters))

    override fun changeFloor(method: FloorChangeMethod, goingUp: Boolean, targetFloor: Int, arrivalPlaceName: String): String {
        val template = when (method) {
            FloorChangeMethod.STAIRS -> if (goingUp) R.string.say_floor_stairs_up else R.string.say_floor_stairs_down
            FloorChangeMethod.ELEVATOR -> if (goingUp) R.string.say_floor_elevator_up else R.string.say_floor_elevator_down
            FloorChangeMethod.UNKNOWN -> if (goingUp) R.string.say_floor_either_up else R.string.say_floor_either_down
        }
        return resources.getString(template, resources.getString(R.string.common_floor, targetFloor), arrivalPlaceName)
    }

    override fun thenChangeFloor(method: FloorChangeMethod, goingUp: Boolean, targetFloor: Int): String {
        val res = when (method) {
            FloorChangeMethod.STAIRS -> if (goingUp) R.string.say_then_floor_stairs_up else R.string.say_then_floor_stairs_down
            FloorChangeMethod.ELEVATOR -> if (goingUp) R.string.say_then_floor_elevator_up else R.string.say_then_floor_elevator_down
            FloorChangeMethod.UNKNOWN -> if (goingUp) R.string.say_then_floor_either_up else R.string.say_then_floor_either_down
        }
        return resources.getString(res, resources.getString(R.string.common_floor, targetFloor))
    }

    override fun hazardAhead(type: NodeType): String? = when (type) {
        NodeType.STAIRS -> resources.getString(R.string.say_hazard_stairs)
        NodeType.DOOR -> resources.getString(R.string.say_hazard_door)
        NodeType.RAMP -> resources.getString(R.string.say_hazard_ramp)
        NodeType.ELEVATOR -> resources.getString(R.string.say_hazard_elevator)
        else -> null
    }

    override fun arrived(): String = resources.getString(R.string.say_arrived)

    override fun positionUncertain(): String = resources.getString(R.string.say_position_uncertain)

    override fun noRoute(): String = resources.getString(R.string.say_no_route)

    override fun noStepFreeRoute(): String = resources.getString(R.string.say_no_step_free_route)

    override fun offRoute(): String = resources.getString(R.string.say_off_route)

    override fun wrongWay(): String = resources.getString(R.string.say_wrong_way)
}
