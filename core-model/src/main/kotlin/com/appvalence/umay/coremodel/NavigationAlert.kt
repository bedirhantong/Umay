package com.appvalence.umay.coremodel

enum class AlertPriority { CRITICAL, WARNING, INFO }

/**
 * What kind of moment an alert marks, independent of its wording, so
 * non-speech feedback (vibration) reacts to *meaning* instead of parsing
 * Turkish text. [NONE] for alerts that shouldn't produce a cue of their own —
 * notably the periodic repeat of an instruction the user already heard: a
 * buzz every few seconds while walking a straight corridor would train
 * people to ignore the buzzes that matter.
 */
enum class GuidanceCue {
    NONE,
    CONTINUE,
    TURN_LEFT,
    TURN_RIGHT,
    HAZARD_AHEAD,
    FLOOR_CHANGE,
    ARRIVED,
    OBSTACLE_WARNING,
    OBSTACLE_CRITICAL,
    REROUTED,
    NO_ROUTE,
}

data class NavigationAlert(
    val id: String,
    val priority: AlertPriority,
    val message: String,
    val relatedObstacle: DetectedObstacle?,
    val createdAtMs: Long,
    val cue: GuidanceCue = GuidanceCue.NONE,
)
