package com.appvalence.umay.coremodel

enum class AlertPriority { CRITICAL, WARNING, INFO }

data class NavigationAlert(
    val id: String,
    val priority: AlertPriority,
    val message: String,
    val relatedObstacle: DetectedObstacle?,
    val createdAtMs: Long,
)
