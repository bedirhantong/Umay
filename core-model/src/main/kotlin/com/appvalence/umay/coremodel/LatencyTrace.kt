package com.appvalence.umay.coremodel

/**
 * Tracks timestamps across the sense -> detect -> fuse -> feedback pipeline
 * so the NFR-1 320ms end-to-end latency budget can be measured and enforced.
 */
data class LatencyTrace(
    val sensingStartMs: Long,
    val detectionDoneMs: Long? = null,
    val fusionDoneMs: Long? = null,
    val feedbackEmittedMs: Long? = null,
) {
    val totalMs: Long?
        get() = feedbackEmittedMs?.let { it - sensingStartMs }

    fun exceedsBudget(budgetMs: Long = DEFAULT_BUDGET_MS): Boolean =
        (totalMs ?: 0) > budgetMs

    companion object {
        const val DEFAULT_BUDGET_MS = 320L
    }
}
