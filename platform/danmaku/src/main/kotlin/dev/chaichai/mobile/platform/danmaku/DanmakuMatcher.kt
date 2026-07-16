package dev.chaichai.mobile.platform.danmaku

import kotlin.math.abs

/**
 * Chooses the best endpoint candidate for a query using title similarity, optional season/episode
 * agreement, and runtime proximity. Pure and deterministic so it is trivially testable with
 * independently authored fixtures. Returns null when nothing is a plausible match.
 */
class DanmakuMatcher(
    private val minTitleScore: Double = 0.34,
    private val runtimeToleranceTicks: Long = 90L * 10_000_000L, // 90s
) {
    fun bestMatch(query: DanmakuMatchQuery, candidates: List<DanmakuMatchCandidate>): DanmakuMatchCandidate? {
        var best: DanmakuMatchCandidate? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (candidate in candidates) {
            val score = score(query, candidate) ?: continue
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun score(query: DanmakuMatchQuery, candidate: DanmakuMatchCandidate): Double? {
        // Season/episode, when both sides supply them, must agree — a wrong episode is not a match.
        if (query.season != null && candidate.season != null && query.season != candidate.season) return null
        if (query.episode != null && candidate.episode != null && query.episode != candidate.episode) return null

        val titleScore = titleSimilarity(query.title, candidate.title)
        if (titleScore < minTitleScore) return null

        var score = titleScore
        if (query.season != null && query.season == candidate.season) score += 0.25
        if (query.episode != null && query.episode == candidate.episode) score += 0.25
        val candidateRuntime = candidate.runtimeTicks
        if (candidateRuntime != null && query.runtimeTicks > 0) {
            val delta = abs(candidateRuntime - query.runtimeTicks)
            if (delta <= runtimeToleranceTicks) {
                score += 0.5 * (1.0 - delta.toDouble() / runtimeToleranceTicks)
            }
        }
        return score
    }

    private fun titleSimilarity(a: String, b: String): Double {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val intersection = ta.count { it in tb }
        val union = (ta + tb).toSet().size
        return intersection.toDouble() / union
    }

    private fun tokens(value: String): Set<String> = value
        .lowercase()
        .map { if (it.isLetterOrDigit()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() }
        .toSet()
}
