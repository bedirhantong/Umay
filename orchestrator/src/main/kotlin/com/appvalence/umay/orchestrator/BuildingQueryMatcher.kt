package com.appvalence.umay.orchestrator

import com.appvalence.umay.coremodel.MapNode
import com.appvalence.umay.coremodel.NodeType
import com.appvalence.umay.coremodel.Position
import java.util.Locale
import kotlin.math.hypot

/** What a free-form question resolved to, or that it didn't. */
sealed interface BuildingQueryResult {
    data class Found(val node: MapNode, val distanceMeters: Double?) : BuildingQueryResult
    data object NotUnderstood : BuildingQueryResult
}

/**
 * Answers a spoken question ("en yakın tuvalet nerede", "asansör nerede")
 * against a building's own graph — no cloud, no language model, nothing
 * that costs money or needs a connection. This is deliberately the *local*
 * half of "ask Umay a question": a question this can't resolve should fail
 * honestly ([BuildingQueryResult.NotUnderstood]) rather than guess, the same
 * "prefer visible not-sure over a bad guess" principle as
 * [com.appvalence.umay.positioning.fingerprint.FingerprintMatcher].
 *
 * Two ways a question resolves:
 * 1. A hazard-type keyword ("asansör", "merdiven", "kapı", "rampa") matches
 *    any node tagged with that [NodeType] during the survey — this doesn't
 *    depend at all on what the surveyor happened to name it.
 * 2. Otherwise, the question's words are compared against every node's own
 *    name (word-overlap similarity), for places the surveyor did name
 *    ("Tuvalet", "203 Sınıfı").
 *
 * When more than one node matches and [from] is known, the nearest one *on
 * the same floor* wins — comparing raw coordinates across floors is
 * meaningless (independent per-floor PDR origins), the same rule routing
 * and the map canvas already follow.
 */
object BuildingQueryMatcher {
    fun match(question: String, nodes: List<MapNode>, nodeFloor: Map<String, Int>, from: Position? = null): BuildingQueryResult {
        if (nodes.isEmpty()) return BuildingQueryResult.NotUnderstood
        val normalizedQuestion = normalize(question)
        if (normalizedQuestion.isBlank()) return BuildingQueryResult.NotUnderstood

        val questionTokens = tokens(normalizedQuestion)
        val typeMatches = TYPE_KEYWORDS.entries
            .firstOrNull { (_, keywords) -> keywords.any { it in questionTokens } }
            ?.let { (type, _) -> nodes.filter { it.type == type } }
            .orEmpty()

        val candidates = typeMatches.ifEmpty {
            nodes
                .map { node -> node to similarity(questionTokens, tokens(normalize(node.name))) }
                .filter { (_, score) -> score >= MATCH_THRESHOLD }
                .sortedByDescending { (_, score) -> score }
                .map { (node, _) -> node }
        }
        if (candidates.isEmpty()) return BuildingQueryResult.NotUnderstood

        val best = if (from != null) {
            candidates.minByOrNull { node -> if (nodeFloor[node.id] == from.floor) hypot(node.x - from.x, node.y - from.y) else Double.MAX_VALUE }!!
        } else {
            candidates.first()
        }
        val distance = from?.takeIf { nodeFloor[best.id] == it.floor }?.let { hypot(best.x - it.x, best.y - it.y) }
        return BuildingQueryResult.Found(best, distance)
    }

    // Jaccard similarity over word sets, but a word "matches" another it merely shares a
    // long-enough prefix with — Turkish is agglutinative, so "tuvalete" (dative: "to the
    // toilet") and "tuvalette" (locative: "at the toilet") are both just "tuvalet" plus a
    // suffix, and a speech-to-text transcript of a spoken question has no reason to land
    // on whichever bare form happens to match a node's name exactly.
    private fun similarity(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val matched = a.count { wordA -> b.any { wordB -> sharePrefix(wordA, wordB) } }
        return matched.toDouble() / (a.size + b.size)
    }

    private fun sharePrefix(a: String, b: String): Boolean {
        if (a == b) return true
        val (shorter, longer) = if (a.length <= b.length) a to b else b to a
        return shorter.length >= MIN_SHARED_PREFIX_LENGTH && longer.startsWith(shorter)
    }

    private fun normalize(text: String): String = text.lowercase(TURKISH).replace(NON_WORD_CHARS, " ").trim()

    private fun tokens(normalizedText: String): Set<String> =
        normalizedText.split(WHITESPACE).filterTo(mutableSetOf()) { it.isNotBlank() && it !in STOP_WORDS }

    private val TURKISH = Locale.forLanguageTag("tr")
    private val NON_WORD_CHARS = Regex("[^\\p{L}0-9 ]")
    private val WHITESPACE = Regex("\\s+")

    private const val MATCH_THRESHOLD = 0.34
    private const val MIN_SHARED_PREFIX_LENGTH = 3

    private val TYPE_KEYWORDS: Map<NodeType, List<String>> = mapOf(
        NodeType.ELEVATOR to listOf("asansör", "asansor"),
        NodeType.STAIRS to listOf("merdiven", "merdivenler"),
        NodeType.DOOR to listOf("kapı", "kapi", "giriş", "giris"),
        NodeType.RAMP to listOf("rampa"),
    )

    // Question scaffolding that carries no meaning about *which place* is meant —
    // stripped so "tuvalet nerede" and "en yakın tuvalet nereye gitmeliyim" score
    // the name "Tuvalet" identically.
    private val STOP_WORDS = setOf(
        "en", "yakın", "yakin", "nerede", "nerde", "nereye", "nasıl", "nasil",
        "giderim", "gitmeliyim", "gidebilirim", "bul", "bana", "göster", "goster",
        "lütfen", "lutfen", "var", "mı", "mi", "mu", "mü", "bir", "şu", "su",
    )
}
