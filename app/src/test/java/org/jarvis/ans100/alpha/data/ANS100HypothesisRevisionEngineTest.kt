package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class ANS100HypothesisRevisionEngineTest {
    private fun revisionId(): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("concept.a|CAUSES|concept.c".toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "revision:$digest"
    }

    private fun candidate() = ANS100ConceptMemory.Hypothesis(
        id = "causal-chain:ab-bc",
        fromConceptId = "concept.a",
        type = ANS100ConceptMemory.RelationType.CAUSES,
        toConceptId = "concept.c",
        premiseRelationIds = listOf("relation.a-b", "relation.b-c"),
        evidenceRefs = listOf("memory:a-b", "memory:b-c"),
        estimatedStrength = 0.64,
    )

    private fun evidence(
        ref: String,
        supports: Boolean,
        source: ANS100ConceptMemory.LearningSource =
            ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
    ) = ANS100ConceptMemory.RelationEvidence(
        fromConceptId = "concept.a",
        type = ANS100ConceptMemory.RelationType.CAUSES,
        toConceptId = "concept.c",
        supports = supports,
        source = source,
        evidenceRef = ref,
    )

    @Test
    fun generatedChainRemainsUnverifiedUntilDirectEvidenceArrives() {
        val engine = ANS100HypothesisRevisionEngine()
        val row = engine.observeTurn("turn-1", listOf(candidate()), emptyList(), true).single()
        assertEquals(ANS100HypothesisRevisionEngine.Status.CANDIDATE_UNVERIFIED, row.status)
        assertEquals(0, row.directEvidenceCount)
        assertEquals(listOf("memory:a-b", "memory:b-c"), row.premiseEvidenceRefs)
    }

    @Test
    fun premiseEdgesAreNeverCountedAsDirectSupport() {
        val engine = ANS100HypothesisRevisionEngine()
        var rejected = false
        try {
            engine.observeTurn("turn-1", listOf(candidate()), listOf(evidence("memory:a-b", true)), true)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertTrue(engine.snapshot().isEmpty())
        // Failed preflight must not consume the turn ID.
        val valid = engine.observeTurn("turn-1", listOf(candidate()), emptyList(), true)
        assertEquals(1, valid.size)
    }

    @Test
    fun directSupportAndLaterCounterEvidenceProduceConflictInsteadOfOverwriting() {
        val engine = ANS100HypothesisRevisionEngine()
        val supported = engine.observeTurn(
            "turn-support",
            listOf(candidate()),
            listOf(evidence("tool:experiment-1", true)),
            true,
        ).single()
        assertEquals(ANS100HypothesisRevisionEngine.Status.SUPPORTED_BY_DIRECT_EVIDENCE, supported.status)
        assertEquals(1.0, supported.evidenceBalance, 0.0)

        val conflicted = engine.observeTurn(
            "turn-refute",
            listOf(candidate()),
            listOf(evidence("tool:experiment-2", false)),
            true,
        ).single()
        assertEquals(ANS100HypothesisRevisionEngine.Status.CONFLICTED_DIRECT_EVIDENCE, conflicted.status)
        assertEquals(1, conflicted.supportingEvidenceCount)
        assertEquals(1, conflicted.contradictingEvidenceCount)
        assertEquals(0.5, conflicted.evidenceBalance, 0.0)
    }

    @Test
    fun repeatedEvidenceReferenceIsCountedOnlyOnceAcrossTurns() {
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn(
            "turn-1", listOf(candidate()), listOf(evidence("tool:stable-record", true)), true,
        )
        val row = engine.observeTurn(
            "turn-2", listOf(candidate()), listOf(evidence("tool:stable-record", true)), true,
        ).single()
        assertEquals(1, row.directEvidenceCount)
    }

    @Test
    fun untrustedModelGeneratedEvidenceDoesNotReviseHypothesis() {
        val engine = ANS100HypothesisRevisionEngine()
        val row = engine.observeTurn(
            "turn-1",
            listOf(candidate()),
            listOf(evidence(
                "model:guess",
                supports = true,
                source = ANS100ConceptMemory.LearningSource.MODEL_GENERATED,
            )),
            true,
        ).single()
        assertEquals(ANS100HypothesisRevisionEngine.Status.CANDIDATE_UNVERIFIED, row.status)
        assertEquals(0, row.directEvidenceCount)
    }

    @Test
    fun incompleteOrDuplicateTurnsCannotChangeRevision() {
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn("turn-1", listOf(candidate()), emptyList(), completed = false)
        val first = engine.observeTurn("turn-1", listOf(candidate()), emptyList(), completed = true).single()
        val duplicate = engine.observeTurn(
            "turn-1", listOf(candidate()), listOf(evidence("tool:late", false)), completed = true,
        ).single()
        assertEquals(first, duplicate)
        assertEquals(0, duplicate.directEvidenceCount)
    }

    @Test
    fun conflictingEvidenceReferenceCannotBeReboundAndFailureIsAtomic() {
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn(
            "turn-1", listOf(candidate()), listOf(evidence("tool:one-ref", true)), true,
        )
        var rejected = false
        try {
            engine.observeTurn(
                "turn-2", listOf(candidate()), listOf(evidence("tool:one-ref", false)), true,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(1, engine.snapshot().single().directEvidenceCount)
        val retry = engine.observeTurn(
            "turn-2", listOf(candidate()), listOf(evidence("tool:two-ref", false)), true,
        ).single()
        assertEquals(2, retry.directEvidenceCount)
    }

    @Test
    fun checkpointRestorePreservesRevisionAndProvenance() {
        val original = ANS100HypothesisRevisionEngine()
        original.observeTurn(
            "turn-1",
            listOf(candidate()),
            listOf(evidence("tool:support", true), evidence(
                "user:counter",
                false,
                ANS100ConceptMemory.LearningSource.EXPLICIT_USER,
            )),
            true,
        )
        val restored = ANS100HypothesisRevisionEngine()
        restored.restoreSnapshot(original.exportSnapshot())
        assertEquals(original.snapshot(), restored.snapshot())
        assertFalse(restored.observeTurn(
            "turn-1", listOf(candidate()), emptyList(), true,
        ).isEmpty())
    }

    @Test
    fun restoreRejectsPremiseEvidenceReusedAsDirectEvidence() {
        val engine = ANS100HypothesisRevisionEngine()
        val hypothesis = candidate()
        val invalid = ANS100HypothesisRevisionEngine.Snapshot(
            observedTurnIds = listOf("turn-1"),
            hypotheses = listOf(
                ANS100HypothesisRevisionEngine.PersistedHypothesis(
                    hypothesisId = revisionId(),
                    fromConceptId = hypothesis.fromConceptId,
                    relationType = hypothesis.type,
                    toConceptId = hypothesis.toConceptId,
                    premiseRelationIds = hypothesis.premiseRelationIds,
                    premiseEvidenceRefs = hypothesis.evidenceRefs,
                    evidence = listOf(
                        ANS100HypothesisRevisionEngine.EvidenceRecord(
                            evidenceRef = hypothesis.evidenceRefs.first(),
                            source = ANS100ConceptMemory.LearningSource.VERIFIED_TOOL,
                            supports = true,
                        ),
                    ),
                    lastUpdatedTurnId = "turn-1",
                ),
            ),
        )
        var rejected = false
        try {
            engine.restoreSnapshot(invalid)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
    }

    @Test
    fun fullEvidenceBudgetRejectsNewRecordsWithoutEvictingDeduplicationHistory() {
        val engine = ANS100HypothesisRevisionEngine()
        val initialEvidence = (0 until ANS100HypothesisRevisionEngine.MAX_EVIDENCE_PER_HYPOTHESIS).map {
            evidence("tool:record-$it", supports = true)
        }
        engine.observeTurn("turn-full", listOf(candidate()), initialEvidence, true)
        val before = engine.exportSnapshot()
        var rejected = false
        try {
            engine.observeTurn(
                "turn-overflow",
                listOf(candidate()),
                listOf(evidence("tool:overflow", supports = false)),
                true,
            )
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue(rejected)
        assertEquals(before, engine.exportSnapshot())
        val retry = engine.observeTurn(
            "turn-overflow",
            listOf(candidate()),
            listOf(evidence("tool:record-0", supports = true)),
            true,
        ).single()
        assertEquals(ANS100HypothesisRevisionEngine.MAX_EVIDENCE_PER_HYPOTHESIS, retry.directEvidenceCount)
    }

    @Test
    fun unverifiedHypothesisRequestsBothSupportingAndCounterEvidence() {
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn("turn-1", listOf(candidate()), emptyList(), true)
        val request = engine.reviewQueue().single()
        assertEquals(ANS100HypothesisRevisionEngine.ReviewAction.SEEK_BOTH_SIDES, request.action)
        assertTrue(request.excludedEvidenceRefs.containsAll(candidate().evidenceRefs))
        assertEquals(ANS100HypothesisRevisionEngine.MAX_EVIDENCE_PER_HYPOTHESIS,
            request.additionalEvidenceCapacity)
    }

    @Test
    fun supportedHypothesisRequestsCounterEvidence() {
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn(
            "turn-1", listOf(candidate()), listOf(evidence("tool:support", true)), true,
        )
        assertEquals(
            ANS100HypothesisRevisionEngine.ReviewAction.SEEK_COUNTER_EVIDENCE,
            engine.reviewQueue().single().action,
        )
    }

    @Test
    fun refutedHypothesisRequestsSupportingEvidence() {
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn(
            "turn-1", listOf(candidate()), listOf(evidence("tool:counter", false)), true,
        )
        assertEquals(
            ANS100HypothesisRevisionEngine.ReviewAction.SEEK_SUPPORTING_EVIDENCE,
            engine.reviewQueue().single().action,
        )
    }

    @Test
    fun conflictedHypothesisIsQueuedForManualAdjudication() {
        val engine = ANS100HypothesisRevisionEngine()
        engine.observeTurn(
            "turn-1",
            listOf(candidate()),
            listOf(evidence("tool:support", true), evidence("tool:counter", false)),
            true,
        )
        assertEquals(
            ANS100HypothesisRevisionEngine.ReviewAction.MANUAL_CONFLICT_REVIEW,
            engine.reviewQueue().single().action,
        )
    }
}
