package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidJnvM250kTopologyTest {
    @Test
    fun populationRangesCoverExactly250kWithoutGapsOrOverlap() {
        AndroidJnvM250kTopology.validate()
        assertEquals(250_000, AndroidJnvM250kTopology.POPULATION_RANGES.sumOf { it.size })
        assertEquals(
            AndroidJnvM250kProfile.NEURON_CAPACITY,
            AndroidJnvM250kTopology.POPULATION_RANGES.last().lastNeuronIdInclusive + 1,
        )
    }

    @Test
    fun populationBoundariesResolveToExpectedRoles() {
        val ranges = AndroidJnvM250kTopology.POPULATION_RANGES
        assertEquals(AndroidJnvM250kTopology.Population.PERCEPTION, AndroidJnvM250kTopology.populationFor(0))
        assertEquals(AndroidJnvM250kTopology.Population.PERCEPTION, AndroidJnvM250kTopology.populationFor(19_999))
        assertEquals(AndroidJnvM250kTopology.Population.MEMORY, AndroidJnvM250kTopology.populationFor(20_000))
        assertEquals(AndroidJnvM250kTopology.Population.ACTION_SELECTION, AndroidJnvM250kTopology.populationFor(249_999))
        assertEquals(10, ranges.size)
    }

    @Test
    fun inhibitoryPolarityIsDeterministicAndPopulationLocal() {
        assertEquals(
            AndroidJnvM250kTopology.Polarity.INHIBITORY,
            AndroidJnvM250kTopology.polarityFor(4),
        )
        assertEquals(
            AndroidJnvM250kTopology.Polarity.EXCITATORY,
            AndroidJnvM250kTopology.polarityFor(5),
        )
        assertEquals(
            AndroidJnvM250kTopology.Polarity.INHIBITORY,
            AndroidJnvM250kTopology.polarityFor(20_004),
        )
    }

    @Test
    fun allNeuronIdsHaveOnePopulationAndPolarity() {
        AndroidJnvM250kTopology.POPULATION_RANGES.forEach { range ->
            assertTrue(range.size > 0)
            assertEquals(range.population, AndroidJnvM250kTopology.populationFor(range.firstNeuronId))
            assertEquals(
                range.population,
                AndroidJnvM250kTopology.populationFor(range.lastNeuronIdInclusive),
            )
        }
    }

    @Test
    fun cognitiveSignalRoutesAllowExpectedForwardAndFeedbackPaths() {
        assertTrue(AndroidJnvM250kTopology.allowsConnection(0, 20_000))
        assertTrue(AndroidJnvM250kTopology.allowsConnection(70_000, 115_000))
        assertTrue(AndroidJnvM250kTopology.allowsConnection(115_000, 175_000))
        assertTrue(AndroidJnvM250kTopology.allowsConnection(175_000, 115_000))
        assertTrue(AndroidJnvM250kTopology.allowsConnection(215_000, 240_000))
        assertTrue(AndroidJnvM250kTopology.allowsConnection(240_000, 230_000))
    }

    @Test
    fun disallowedRoutesAndSelfConnectionsAreRejected() {
        assertEquals(false, AndroidJnvM250kTopology.allowsConnection(0, 240_000))
        assertEquals(false, AndroidJnvM250kTopology.allowsConnection(20_000, 155_000))
        assertEquals(false, AndroidJnvM250kTopology.allowsConnection(12, 12))
    }

    @Test(expected = IllegalArgumentException::class)
    fun routeValidationRejectsOutOfRangeIds() {
        AndroidJnvM250kTopology.requireAllowedConnection(-1, 20_000)
    }

    @Test
    fun sparseInitializerIsReproducibleAndKeepsBatchesBounded() {
        val first = AndroidJnvM250kSparseConnectionInitializer
            .batches(seed = 42L, batchSize = 32)
            .take(3)
            .flatten()
            .toList()
        val second = AndroidJnvM250kSparseConnectionInitializer
            .batches(seed = 42L, batchSize = 32)
            .take(3)
            .flatten()
            .toList()

        assertEquals(96, first.size)
        assertEquals(first, second)
        assertTrue(first.all { AndroidJnvM250kTopology.allowsConnection(it.sourceNeuronId, it.targetNeuronId) })
        assertTrue(first.all { it.sourceNeuronId != it.targetNeuronId })
    }

    @Test
    fun sparseInitializerUsesStableUniqueIdsAndHonorsRequestedFanout() {
        val edges = AndroidJnvM250kSparseConnectionInitializer
            .batches(seed = 7L, batchSize = 64, fanoutPerRoute = 2)
            .first()

        assertEquals(64, edges.size)
        assertEquals(64, edges.map { it.connectionId }.toSet().size)
        assertTrue(edges.all { it.weight == 0.5 && it.delayNs == 1_000_000L })
    }

    @Test(expected = IllegalArgumentException::class)
    fun sparseInitializerRejectsFanoutThatExceedsOutgoingDegreeLimit() {
        AndroidJnvM250kSparseConnectionInitializer.batches(
            seed = 1L,
            fanoutPerRoute = 5,
        )
    }

    @Test
    fun sparseInitializerCursorResumesWithoutRepeatingOrSkippingEdges() {
        val initial = AndroidJnvM250kSparseConnectionInitializer.initialCheckpoint(seed = 991L)
        val first = AndroidJnvM250kSparseConnectionInitializer.nextBatch(initial, batchSize = 13)
        val resumed = AndroidJnvM250kSparseConnectionInitializer.nextBatch(first.checkpoint, batchSize = 17)
        val uninterrupted = AndroidJnvM250kSparseConnectionInitializer.nextBatch(initial, batchSize = 30)

        assertEquals(uninterrupted.connections, first.connections + resumed.connections)
        assertEquals(uninterrupted.checkpoint, resumed.checkpoint)
        assertEquals(30, first.connections.size + resumed.connections.size)
    }

    @Test
    fun sparseInitializerCheckpointIsCanonicalAtCompletion() {
        val initial = AndroidJnvM250kSparseConnectionInitializer.initialCheckpoint(seed = 3L)
        val one = AndroidJnvM250kSparseConnectionInitializer.nextBatch(initial, batchSize = 1)
        assertEquals(false, one.checkpoint.completed)
        assertEquals(0, one.checkpoint.cursor.routeIndex)
    }
}
