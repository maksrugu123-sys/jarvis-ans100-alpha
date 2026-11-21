package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kHypothesisReadoutTest {
    private fun encoding(): AndroidJnvM250kEvidenceEncoder.Encoding {
        val turnId = "jnvm-readout-test"
        val evidence = TypedEvidenceBundle(
            turnId = turnId,
            records = listOf(
                TypedEvidenceRecord(
                    recordId = "feature-a:0",
                    turnId = turnId,
                    feature = EvidenceFeature.USER_MESSAGE_TEXT,
                    source = EvidenceSourceKind.USER_MESSAGE,
                    status = EvidenceStatus.USER_PROVIDED,
                    value = EvidenceValue.Text("alpha beta"),
                    activation = 1.0,
                    provenanceRef = "turn:$turnId:field-a",
                ),
                TypedEvidenceRecord(
                    recordId = "feature-b:0",
                    turnId = turnId,
                    feature = EvidenceFeature.CORE_INTENT_HINT,
                    source = EvidenceSourceKind.CORE_UNDERSTANDING,
                    status = EvidenceStatus.HEURISTIC,
                    value = EvidenceValue.Category("explain"),
                    activation = 0.8,
                    provenanceRef = "turn:$turnId:field-b",
                ),
            ),
        )
        return AndroidJnvM250kEvidenceEncoder().encode(evidence, inputTimeNs = 10L)
    }

    private fun readout(
        ambiguous: Set<Int> = emptySet(),
        omitted: Int = 0,
    ): AndroidJnvM250kHypothesisReadout.Result {
        val encoding = encoding()
        val sourceIds = encoding.conceptRecordIds.map { encoding.recordToNeuronId.getValue(it) }.sorted()
        val target = 70_123
        val spikes = sourceIds.mapIndexed { index, neuronId ->
            AndroidJnvM250kSpike(neuronId, 1L, 10L)
        } + AndroidJnvM250kSpike(target, 1L, 20L)
        val links = sourceIds.mapIndexed { index, neuronId ->
            AndroidJnvM250kCausalLink(
                connectionId = "edge-$index",
                sourceNeuronId = neuronId,
                targetNeuronId = target,
                timestampNs = 10L,
                arrivalTimestampNs = 20L,
            )
        }
        return AndroidJnvM250kHypothesisReadout().decode(
            encoding = encoding,
            emittedSpikes = spikes,
            causalLinks = links,
            omittedCausalLinks = omitted,
            ambiguousTargetSpikeNeuronIds = ambiguous,
        )
    }

    @Test
    fun requiresTwoRealTransmissionsAndMatchingTargetSpike() {
        val result = readout()
        assertEquals(2, result.activeConceptIds.size)
        assertEquals(1, result.candidates.size)
        assertEquals(listOf(70_123), result.candidates.single().convergentNeuronIds)
        assertTrue(result.pairScanComplete)
        assertEquals(2, result.candidates.single().activatedConnectionIds.size)
        assertFalse(result.candidates.single().heuristicEvidence)
    }

    @Test
    fun ambiguousTargetSpikeCannotCreateCandidateOrNegativeVerification() {
        val result = readout(ambiguous = setOf(70_123))
        assertTrue(result.candidates.isEmpty())
        assertFalse(result.pairScanComplete)
    }

    @Test
    fun omittedTransmissionsMakePairScanIncomplete() {
        val result = readout(omitted = 1)
        assertEquals(1, result.candidates.size)
        assertFalse(result.pairScanComplete)
    }

    @Test
    fun jnvmHypothesesTrackIndependentReproductionAndNonReproduction() {
        val first = readout()
        val engine = ANS100NeuralHypothesisEngine()
        val generated = engine.observe250kTurn(
            turnId = "turn-one",
            activeConceptIds = first.activeConceptIds,
            candidates = first.candidates,
            completed = true,
            pairScanComplete = first.pairScanComplete,
        )
        assertEquals(ANS100NeuralHypothesisEngine.Status.GENERATED, generated.single().status)
        assertFalse(generated.single().heuristicEvidence)
        assertFalse(engine.exportSnapshot().hypotheses.single().heuristicEvidence)

        val second = engine.observe250kTurn(
            turnId = "turn-two",
            activeConceptIds = first.activeConceptIds,
            candidates = emptyList(),
            completed = true,
            pairScanComplete = true,
        )
        assertEquals(
            ANS100NeuralHypothesisEngine.Status.MIXED_REPRODUCTION_SIGNAL,
            second.single().status,
        )
    }

    @Test
    fun repeatedLexicalAssociationProducesConservativeFeaturePrediction() {
        val first = readout()
        val engine = ANS100NeuralHypothesisEngine()
        repeat(2) { index ->
            engine.observe250kTurn(
                turnId = "support-turn-$index",
                activeConceptIds = first.activeConceptIds,
                candidates = first.candidates,
                completed = true,
                pairScanComplete = true,
            )
        }
        val source = first.candidates.single().firstConceptId
        val restored = ANS100NeuralHypothesisEngine()
        restored.restoreSnapshot(engine.exportSnapshot())
        val predictions = restored.predict250k(setOf(source))
        assertEquals(1, predictions.size)
        assertEquals(first.candidates.single().secondConceptId, predictions.single().predictedConceptId)
        assertTrue(predictions.single().confidence >= ANS100NeuralHypothesisEngine.MIN_PREDICTION_CONFIDENCE)
    }

    @Test
    fun predictionPulseTargetsPredictionPopulationAndDoesNotBecomeObservedConcept() {
        val encoder = AndroidJnvM250kEvidenceEncoder()
        val base = encoder.encode(
            TypedEvidenceBundle(
                turnId = "prediction-turn",
                records = listOf(
                    TypedEvidenceRecord(
                        recordId = "message",
                        turnId = "prediction-turn",
                        feature = EvidenceFeature.USER_MESSAGE_TEXT,
                        source = EvidenceSourceKind.USER_MESSAGE,
                        status = EvidenceStatus.USER_PROVIDED,
                        value = EvidenceValue.Text("alpha"),
                        activation = 1.0,
                        provenanceRef = "turn:prediction-turn:user",
                    ),
                ),
            ),
            inputTimeNs = 50L,
        )
        val prediction = ANS100NeuralHypothesisEngine.Prediction(
            sourceConceptId = "lex-token:aaaaaaaaaaaaaaaaaaaaaaaa",
            predictedConceptId = "lex-token:bbbbbbbbbbbbbbbbbbbbbbbb",
            predictedLabel = "beta",
            supportingTurnCount = 2,
            verificationTurnCount = 2,
            confidence = 0.75,
            heuristicEvidence = false,
        )
        val encoded = encoder.appendPredictions(base, listOf(prediction), predictionTimeNs = 50L)
        val predictedEvent = encoded.events.last()
        val predictionRange = AndroidJnvM250kTopology.POPULATION_RANGES.single {
            it.population == AndroidJnvM250kTopology.Population.PREDICTION
        }
        assertTrue(predictedEvent.targetNeuronId in predictionRange)
        assertTrue(predictedEvent.targetNeuronId >= predictionRange.firstNeuronId + predictionRange.size / 2)
        assertEquals(1.0, predictedEvent.strength, 0.0)
        assertTrue(predictedEvent.coreTainted)
        assertEquals(1, encoded.predictionEventCount)
        assertEquals(base.conceptRecordIds, encoded.conceptRecordIds)
        assertEquals(base.recordToNeuronId, encoded.recordToNeuronId)
    }

    @Test
    fun laterRecurrentSourceSpikeCannotMasqueradeAsDirectLexicalInput() {
        val encoding = encoding()
        val sourceIds = encoding.conceptRecordIds.map { encoding.recordToNeuronId.getValue(it) }.sorted()
        val target = 70_123
        val spikes = sourceIds.map { AndroidJnvM250kSpike(it, 1L, 100L) } +
            AndroidJnvM250kSpike(target, 1L, 110L)
        val links = sourceIds.mapIndexed { index, neuronId ->
            AndroidJnvM250kCausalLink(
                connectionId = "late-edge-$index",
                sourceNeuronId = neuronId,
                targetNeuronId = target,
                timestampNs = 100L,
                arrivalTimestampNs = 110L,
            )
        }
        val result = AndroidJnvM250kHypothesisReadout().decode(
            encoding = encoding,
            emittedSpikes = spikes,
            causalLinks = links,
            omittedCausalLinks = 0,
            ambiguousTargetSpikeNeuronIds = emptySet(),
        )
        assertTrue(result.activeConceptIds.isEmpty())
        assertTrue(result.candidates.isEmpty())
    }

    @Test
    fun nonReproducingTurnLowersAssociationBelowPredictionThreshold() {
        val first = readout()
        val engine = ANS100NeuralHypothesisEngine()
        repeat(2) { index ->
            engine.observe250kTurn(
                turnId = "positive-$index",
                activeConceptIds = first.activeConceptIds,
                candidates = first.candidates,
                completed = true,
                pairScanComplete = true,
            )
        }
        engine.observe250kTurn(
            turnId = "negative-one",
            activeConceptIds = first.activeConceptIds,
            candidates = emptyList(),
            completed = true,
            pairScanComplete = true,
        )
        val source = first.candidates.single().firstConceptId
        assertTrue(engine.predict250k(setOf(source)).isEmpty())
    }
}
