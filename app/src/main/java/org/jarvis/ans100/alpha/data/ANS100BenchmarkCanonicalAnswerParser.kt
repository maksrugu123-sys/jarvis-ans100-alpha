package org.jarvis.ans100.alpha.data

/**
 * Strict parser for benchmark model output. Explanations are rejected rather than
 * heuristically extracting a label, so verbose or uncertain generations cannot
 * receive accidental credit.
 */
object ANS100BenchmarkCanonicalAnswerParser {
    private val answerPattern = Regex("^(YES|NO|UNKNOWN|CONFLICT)[.!]?$")

    fun parse(rawOutput: String): String? {
        val candidate = rawOutput.trim().uppercase()
        return answerPattern.matchEntire(candidate)?.groupValues?.get(1)
    }
}
