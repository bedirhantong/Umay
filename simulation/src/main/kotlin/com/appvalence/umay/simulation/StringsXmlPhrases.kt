package com.appvalence.umay.simulation

import com.appvalence.umay.coremodel.AlertPriority
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.ObstacleKind
import com.appvalence.umay.orchestrator.FloorChangeMethod
import com.appvalence.umay.orchestrator.GuidancePhrases
import com.appvalence.umay.orchestrator.Turn
import com.appvalence.umay.orchestrator.TurnDirection
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The app's spoken guidance, word for word, without Android: the same string
 * resources the app speaks (app/src/main/res/values/strings.xml), chosen the
 * way the app's ResourceGuidancePhrases chooses them. A simulated walk then
 * says exactly what the app would, and the simulated walker reacts to the
 * very sentences a blind user hears. Keep the choices here in step with
 * ResourceGuidancePhrases.
 */
class StringsXmlPhrases(
    private val strings: Map<String, String>,
    private val plurals: Map<String, Map<String, String>>,
    private val clockDirections: Boolean = false,
) : GuidancePhrases {

    private fun string(name: String, vararg args: Any): String {
        val template = strings[name] ?: error("strings.xml has no string named $name")
        return if (args.isEmpty()) template else String.format(TURKISH, template, *args)
    }

    private fun meters(distanceMeters: Double): String {
        val rounded = distanceMeters.roundToInt().coerceAtLeast(1)
        val forms = plurals["say_meters"] ?: error("strings.xml has no plurals named say_meters")
        val template = (if (rounded == 1) forms["one"] else null) ?: forms["other"] ?: error("say_meters has no 'other' form")
        return String.format(TURKISH, template, rounded)
    }

    override fun walkToward(placeName: String, distanceMeters: Double, thenTurn: Turn?): String = when {
        thenTurn == null -> string("say_walk_straight", placeName, meters(distanceMeters))
        clockDirections -> string("say_walk_then_clock", placeName, meters(distanceMeters), thenTurn.clockHour)
        thenTurn.direction == TurnDirection.RIGHT -> string("say_walk_then_right", placeName, meters(distanceMeters))
        else -> string("say_walk_then_left", placeName, meters(distanceMeters))
    }

    override fun walkAhead(distanceMeters: Double, thenTurn: Turn?): String = when {
        thenTurn == null -> string("say_walk_ahead", meters(distanceMeters))
        clockDirections -> string("say_walk_ahead_then_clock", meters(distanceMeters), thenTurn.clockHour)
        thenTurn.direction == TurnDirection.RIGHT -> string("say_walk_ahead_then_right", meters(distanceMeters))
        else -> string("say_walk_ahead_then_left", meters(distanceMeters))
    }

    override fun followWall(side: TurnDirection): String = when {
        clockDirections -> string("say_wall_clock", Turn.clockHourOf(side))
        side == TurnDirection.LEFT -> string("say_wall_left")
        else -> string("say_wall_right")
    }

    override fun turnNow(turn: Turn): String = when {
        clockDirections -> string("say_turn_clock_now", turn.clockHour)
        turn.direction == TurnDirection.RIGHT -> string("say_turn_right_now")
        else -> string("say_turn_left_now")
    }

    override fun turnAround(): String = if (clockDirections) string("say_turn_clock_now", 6) else string("say_turn_around")

    override fun passObstacle(side: TurnDirection): String = when {
        clockDirections -> string("say_pass_clock", if (side == TurnDirection.LEFT) 11 else 1)
        side == TurnDirection.LEFT -> string("say_pass_left")
        else -> string("say_pass_right")
    }

    override fun approachDestination(distanceMeters: Double): String = string("say_approach_destination", meters(distanceMeters))

    override fun changeFloor(method: FloorChangeMethod, goingUp: Boolean, targetFloor: Int, arrivalPlaceName: String): String {
        val name = when (method) {
            FloorChangeMethod.STAIRS -> if (goingUp) "say_floor_stairs_up" else "say_floor_stairs_down"
            FloorChangeMethod.ELEVATOR -> if (goingUp) "say_floor_elevator_up" else "say_floor_elevator_down"
            FloorChangeMethod.UNKNOWN -> if (goingUp) "say_floor_either_up" else "say_floor_either_down"
        }
        return string(name, string("common_floor", targetFloor), arrivalPlaceName)
    }

    override fun thenChangeFloor(method: FloorChangeMethod, goingUp: Boolean, targetFloor: Int): String {
        val name = when (method) {
            FloorChangeMethod.STAIRS -> if (goingUp) "say_then_floor_stairs_up" else "say_then_floor_stairs_down"
            FloorChangeMethod.ELEVATOR -> if (goingUp) "say_then_floor_elevator_up" else "say_then_floor_elevator_down"
            FloorChangeMethod.UNKNOWN -> if (goingUp) "say_then_floor_either_up" else "say_then_floor_either_down"
        }
        return string(name, string("common_floor", targetFloor))
    }

    override fun hazardAhead(type: NodeType): String? = when (type) {
        NodeType.STAIRS -> string("say_hazard_stairs")
        NodeType.DOOR -> string("say_hazard_door")
        NodeType.RAMP -> string("say_hazard_ramp")
        NodeType.ELEVATOR -> string("say_hazard_elevator")
        else -> null
    }

    override fun arrived(): String = string("say_arrived")

    override fun noRoute(): String = string("say_no_route")

    override fun rerouted(): String = string("say_rerouted")

    override fun noStepFreeRoute(): String = string("say_no_step_free_route")

    override fun offRoute(): String = string("say_off_route")

    override fun wrongWay(): String = string("say_wrong_way")

    override fun obstacle(priority: AlertPriority, kind: ObstacleKind, distanceMeters: Double): String {
        val what = string("obstacle_" + kind.name.lowercase(Locale.ROOT))
        return when (priority) {
            AlertPriority.CRITICAL -> string("say_obstacle_critical", what)
            AlertPriority.WARNING -> string("say_obstacle_warning", meters(distanceMeters), what)
            AlertPriority.INFO -> string("say_obstacle_info", meters(distanceMeters), what)
        }
    }

    companion object {
        private val TURKISH = Locale.forLanguageTag("tr")
        private const val STRINGS_PATH = "app/src/main/res/values/strings.xml"

        private val STRING = Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        private val PLURALS = Regex("""<plurals\s+name="([^"]+)"[^>]*>(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL)
        private val ITEM = Regex("""<item\s+quantity="([^"]+)"[^>]*>(.*?)</item>""", RegexOption.DOT_MATCHES_ALL)

        /** The app's own strings, found by walking up from the working directory to the repository root. */
        fun fromAppResources(clockDirections: Boolean = false): StringsXmlPhrases {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, STRINGS_PATH).isFile) dir = dir.parentFile
            val file = dir?.let { File(it, STRINGS_PATH) } ?: error("$STRINGS_PATH not found above ${File("").absolutePath}")
            return parse(file.readText(), clockDirections)
        }

        fun parse(xml: String, clockDirections: Boolean = false): StringsXmlPhrases {
            val strings = STRING.findAll(xml).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            val plurals = PLURALS.findAll(xml).associate { match ->
                match.groupValues[1] to ITEM.findAll(match.groupValues[2]).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
            }
            return StringsXmlPhrases(strings, plurals, clockDirections)
        }

        // What Android's resource compiler does to a plain string: escapes, entities, and
        // runs of whitespace collapsed. Quoted spans and inline markup aren't used here.
        private fun unescape(raw: String): String = raw.trim()
            .replace(Regex("\\s+"), " ")
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .replace("\\n", "\n")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
    }
}
