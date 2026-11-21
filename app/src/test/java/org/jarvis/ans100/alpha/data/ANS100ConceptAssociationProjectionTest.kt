package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100ConceptAssociationProjectionTest {
    @Test
    fun decoderProjectionExactlyMatchesPersistedTopologyEdges() {
        val conceptInputIds = listOf(
            ANS100ConceptMemory.NEURAL_INPUT_START,
            ANS100ConceptMemory.NEURAL_INPUT_START + 1,
            ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS - 1,
        )
        val edges = AndroidHybridTopologyV2.connections()
        conceptInputIds.forEach { sourceId ->
            val projected = AndroidHybridTopologyV2.conceptAssociationProjection(sourceId)
            val persisted = edges.filter {
                it.sourceNeuronId == sourceId &&
                    it.targetNeuronId in 1_200 until 2_200
            }
            assertEquals(3, projected.size)
            assertEquals(projected.keys, persisted.map { it.targetNeuronId }.toSet())
            assertEquals(projected.values.toSet(), persisted.map { it.connectionId }.toSet())
            assertTrue(projected.keys.all { it in 1_200 until 2_200 })
        }
    }

    @Test
    fun projectionTargetsAreUniqueAndStable() {
        for (sourceId in ANS100ConceptMemory.NEURAL_INPUT_START until
            ANS100ConceptMemory.NEURAL_INPUT_START + ANS100ConceptMemory.MAX_NEURAL_INPUT_SLOTS
        ) {
            val first = AndroidHybridTopologyV2.conceptAssociationProjection(sourceId)
            val second = AndroidHybridTopologyV2.conceptAssociationProjection(sourceId)
            assertEquals(first, second)
            assertEquals(3, first.size)
            assertEquals(3, first.keys.size)
        }
    }
}
