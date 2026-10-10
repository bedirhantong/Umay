package com.appvalence.umay.navigation

import android.content.Context
import com.appvalence.umay.R
import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.ObstacleKind
import com.appvalence.umay.orchestrator.FloorChangeMethod
import com.appvalence.umay.orchestrator.GuidancePhrases
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
 */
class ResourceGuidancePhrases(context: Context) : GuidancePhrases {
    private val resources = context.applicationContext.resources

    private fun meters(distanceMeters: Double): String {
        // Whole meters: nobody paces out tenths, and "4.0" gets read as "four point zero".
        val rounded = distanceMeters.roundToInt().coerceAtLeast(1)
        return resources.getQuantityString(R.plurals.say_meters, rounded, rounded)
    }

    override fun walkToward(placeName: String, distanceMeters: Double, thenTurn: TurnDirection?): String = when (thenTurn) {
        null -> resources.getString(R.string.say_walk_straight, placeName, meters(distanceMeters))
        TurnDirection.RIGHT -> resources.getString(R.string.say_walk_then_right, placeName, meters(distanceMeters))
        TurnDirection.LEFT -> resources.getString(R.string.say_walk_then_left, placeName, meters(distanceMeters))
    }

    override fun walkAhead(distanceMeters: Double, thenTurn: TurnDirection?): String = when (thenTurn) {
        null -> resources.getString(R.string.say_walk_ahead, meters(distanceMeters))
        TurnDirection.RIGHT -> resources.getString(R.string.say_walk_ahead_then_right, meters(distanceMeters))
        TurnDirection.LEFT -> resources.getString(R.string.say_walk_ahead_then_left, meters(distanceMeters))
    }

    override fun followWall(side: TurnDirection): String =
        resources.getString(if (side == TurnDirection.LEFT) R.string.say_wall_left else R.string.say_wall_right)

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

    override fun hazardAhead(type: NodeType): String? = when (type) {
        NodeType.STAIRS -> resources.getString(R.string.say_hazard_stairs)
        NodeType.DOOR -> resources.getString(R.string.say_hazard_door)
        NodeType.RAMP -> resources.getString(R.string.say_hazard_ramp)
        NodeType.ELEVATOR -> resources.getString(R.string.say_hazard_elevator)
        else -> null
    }

    override fun arrived(): String = resources.getString(R.string.say_arrived)

    override fun noRoute(): String = resources.getString(R.string.say_no_route)

    override fun rerouted(): String = resources.getString(R.string.say_rerouted)

    override fun noStepFreeRoute(): String = resources.getString(R.string.say_no_step_free_route)

    override fun offRoute(): String = resources.getString(R.string.say_off_route)

    override fun wrongWay(): String = resources.getString(R.string.say_wrong_way)

    // A critical warning is kept as short as possible — it has to be over before the
    // person takes another step — so it says *stop*, not how many meters away.
    override fun obstacle(priority: AlertPriority, kind: ObstacleKind, distanceMeters: Double): String {
        val what = resources.getString(obstacleName(kind))
        return when (priority) {
            AlertPriority.CRITICAL -> resources.getString(R.string.say_obstacle_critical, what)
            AlertPriority.WARNING -> resources.getString(R.string.say_obstacle_warning, meters(distanceMeters), what)
            AlertPriority.INFO -> resources.getString(R.string.say_obstacle_info, meters(distanceMeters), what)
        }
    }

    private fun obstacleName(kind: ObstacleKind): Int = when (kind) {
        ObstacleKind.PERSON -> R.string.obstacle_person
        ObstacleKind.BICYCLE -> R.string.obstacle_bicycle
        ObstacleKind.MOTORCYCLE -> R.string.obstacle_motorcycle
        ObstacleKind.FIRE_HYDRANT -> R.string.obstacle_fire_hydrant
        ObstacleKind.BENCH -> R.string.obstacle_bench
        ObstacleKind.DOG -> R.string.obstacle_dog
        ObstacleKind.SUITCASE -> R.string.obstacle_suitcase
        ObstacleKind.CHAIR -> R.string.obstacle_chair
        ObstacleKind.COUCH -> R.string.obstacle_couch
        ObstacleKind.POTTED_PLANT -> R.string.obstacle_potted_plant
        ObstacleKind.BED -> R.string.obstacle_bed
        ObstacleKind.TABLE -> R.string.obstacle_table
        ObstacleKind.TOILET -> R.string.obstacle_toilet
        ObstacleKind.REFRIGERATOR -> R.string.obstacle_refrigerator
        ObstacleKind.UNKNOWN -> R.string.obstacle_unknown
    }
}
