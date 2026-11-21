package org.jarvis.ans100.alpha.data

import java.util.Locale

/**
 * Produces conservative polarity candidates from explicit relation cues.
 *
 * This is a lexical/syntactic heuristic, not a fact checker. Its output must
 * never be converted directly into VERIFIED_TOOL evidence or a learning signal.
 */
class ANS100HypothesisClaimPolarityAnalyzer {
    enum class PolarityCandidate {
        POSSIBLE_SUPPORT,
        POSSIBLE_CONTRADICTION,
        AMBIGUOUS,
    }

    enum class Reason {
        ORDERED_RELATION_CUE_FOUND,
        ORDERED_RELATION_CUE_NEGATED,
        NO_EXPLICIT_RELATION_CUE,
        ENDPOINT_ORDER_OR_SCOPE_UNCLEAR,
    }

    data class Analysis(
        val hypothesisId: String,
        val passageRef: String,
        val pageContentSha256: String,
        val sentenceSha256: String,
        val sourceUrl: String,
        val sourceHost: String,
        val excerpt: String,
        val relationType: ANS100ConceptMemory.RelationType,
        val polarityCandidate: PolarityCandidate,
        val reason: Reason,
        val matchedCue: String?,
        val detectedNegation: String?,
        val status: String = "POLARITY_CANDIDATE_UNVERIFIED",
    )

    private data class Cue(val phrase: String, val tokens: List<String>)

    fun analyze(
        passage: ANS100HypothesisPassageExtractor.PassageCandidate,
        fromLabel: String,
        relationType: ANS100ConceptMemory.RelationType,
        toLabel: String,
    ): Analysis {
        require(passage.hypothesisId.isNotBlank())
        require(passage.passageRef.isNotBlank())
        require(passage.pageContentSha256.matches(Regex("[0-9a-f]{64}")))
        require(passage.sentenceSha256.matches(Regex("[0-9a-f]{64}")))
        val from = tokenize(fromLabel)
        val to = tokenize(toLabel)
        val words = tokenize(passage.excerpt)
        require(from.isNotEmpty() && to.isNotEmpty()) { "hypothesis endpoint labels must not be blank" }

        val fromStart = findPhrase(words, from)
        val toStart = findPhrase(words, to)
        val cues = cuesFor(relationType)
        val orderedCue = if (fromStart != null && toStart != null && fromStart + from.size <= toStart) {
            cues.firstOrNull { cue ->
                findPhraseInRange(words, cue.tokens, fromStart + from.size, toStart) != null
            }
        } else null

        val reversedOrder = fromStart != null && toStart != null && toStart + to.size <= fromStart
        val matchedCueStart = orderedCue?.let {
            findPhraseInRange(words, it.tokens, fromStart!! + from.size, toStart!!)
        }
        val negation = if (matchedCueStart != null && orderedCue != null) {
            findNegation(words, matchedCueStart, orderedCue.tokens.size)
        } else null

        val polarity = when {
            orderedCue == null -> PolarityCandidate.AMBIGUOUS
            negation != null -> PolarityCandidate.POSSIBLE_CONTRADICTION
            else -> PolarityCandidate.POSSIBLE_SUPPORT
        }
        val reason = when {
            orderedCue == null && (reversedOrder || fromStart != null && toStart != null) ->
                Reason.ENDPOINT_ORDER_OR_SCOPE_UNCLEAR
            orderedCue == null -> Reason.NO_EXPLICIT_RELATION_CUE
            negation != null -> Reason.ORDERED_RELATION_CUE_NEGATED
            else -> Reason.ORDERED_RELATION_CUE_FOUND
        }

        return Analysis(
            hypothesisId = passage.hypothesisId,
            passageRef = passage.passageRef,
            pageContentSha256 = passage.pageContentSha256,
            sentenceSha256 = passage.sentenceSha256,
            sourceUrl = passage.sourceUrl,
            sourceHost = passage.sourceHost,
            excerpt = passage.excerpt,
            relationType = relationType,
            polarityCandidate = polarity,
            reason = reason,
            matchedCue = orderedCue?.phrase,
            detectedNegation = negation,
        )
    }

    private fun cuesFor(type: ANS100ConceptMemory.RelationType): List<Cue> =
        CUE_PHRASES.getValue(type).map { phrase -> Cue(phrase, tokenize(phrase)) }

    private fun findPhrase(words: List<String>, phrase: List<String>): Int? =
        findPhraseInRange(words, phrase, 0, words.size)

    private fun findPhraseInRange(
        words: List<String>,
        phrase: List<String>,
        startInclusive: Int,
        endExclusive: Int,
    ): Int? {
        if (phrase.isEmpty()) return null
        val lastStart = endExclusive - phrase.size
        for (index in startInclusive..lastStart) {
            if (words.subList(index, index + phrase.size) == phrase) return index
        }
        return null
    }

    private fun findNegation(words: List<String>, cueStart: Int, cueLength: Int): String? {
        val cueEnd = cueStart + cueLength
        val start = (cueStart - NEGATION_LOOKBACK).coerceAtLeast(0)
        val before = words.subList(start, cueStart)
        // "not only causes" is not a negation of the relation.
        if (before.size >= 2 && before[before.size - 2] == "not" && before.last() == "only") {
            return null
        }
        return before.lastOrNull { it in NEGATIONS }
            ?: words.subList(cueEnd, (cueEnd + 1).coerceAtMost(words.size))
                .firstOrNull { it in POST_CUE_NEGATIONS }
    }

    private fun tokenize(value: String): List<String> =
        TOKEN.findAll(value.lowercase(Locale.ROOT)).map { it.value }.toList()

    companion object {
        private const val NEGATION_LOOKBACK = 3
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val NEGATIONS = setOf("not", "never", "no", "не", "ни", "никогда")
        private val POST_CUE_NEGATIONS = setOf("not", "не")
        private val CUE_PHRASES = mapOf(
            ANS100ConceptMemory.RelationType.IS_A to listOf("is a", "is an", "are a", "является"),
            ANS100ConceptMemory.RelationType.PART_OF to listOf("part of", "belongs to", "является частью", "часть"),
            ANS100ConceptMemory.RelationType.CAUSES to listOf(
                "causes", "cause", "caused", "leads to", "lead to", "results in", "result in",
                "produces", "вызывает", "вызывают", "приводит к", "приводят к",
            ),
            ANS100ConceptMemory.RelationType.ENABLES to listOf(
                "enables", "enable", "allows", "allow", "makes possible", "позволяет", "обеспечивает",
            ),
            ANS100ConceptMemory.RelationType.PREVENTS to listOf(
                "prevents", "prevent", "blocks", "block", "stops", "stop", "предотвращает",
                "предотвращают", "блокирует", "мешает",
            ),
            ANS100ConceptMemory.RelationType.HAS_PROPERTY to listOf(
                "has", "have", "характеризуется", "имеет", "обладает",
            ),
            ANS100ConceptMemory.RelationType.USED_FOR to listOf(
                "used for", "is used for", "serves for", "используется для", "применяется для",
            ),
        )
    }
}
