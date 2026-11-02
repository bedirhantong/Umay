package com.appvalence.umay.coremodel

/**
 * How a recorded walk went, at a glance, from its "Buradayım" marks
 * ([GroundTruthEvent]): how far off the app was on average and at worst, and
 * where. What a field tester wants to see on the spot, before (or instead of)
 * sending the log anywhere (docs/YOL_HARITASI.md 0.9).
 */
data class FieldLogSummary(
    /** "Buradayım" presses. */
    val marks: Int,
    /** Marks with an error: the app's estimate was on the same floor as the mark. */
    val measured: Int,
    val meanErrorMeters: Double?,
    val worstErrorMeters: Double?,
    /** The place named at the worst mark. */
    val worstPlaceName: String?,
) {
    /** Marks where the app thought the walker was on another floor. */
    val wrongFloor: Int get() = marks - measured

    companion object {
        /**
         * Reads [lines] of a field log one at a time, decoding only the marks — the
         * session's first line carries the whole map, and a log can run to megabytes.
         * Lines that don't decode (a log cut short by a crash) are skipped.
         */
        fun of(lines: Sequence<String>): FieldLogSummary {
            var marks = 0
            val errors = ArrayList<Pair<Double, String>>()
            for (line in lines) {
                if (GROUND_TRUTH_TYPE !in line) continue
                val mark = runCatching { FieldLogJson.decode(line) }.getOrNull() as? GroundTruthEvent ?: continue
                marks++
                mark.errorMeters?.let { errors += it to mark.nodeName }
            }
            val worst = errors.maxByOrNull { it.first }
            return FieldLogSummary(
                marks = marks,
                measured = errors.size,
                meanErrorMeters = if (errors.isEmpty()) null else errors.sumOf { it.first } / errors.size,
                worstErrorMeters = worst?.first,
                worstPlaceName = worst?.second,
            )
        }

        // A cheap test before decoding: every ground-truth line carries its type tag.
        private const val GROUND_TRUTH_TYPE = "\"ground_truth\""
    }
}
