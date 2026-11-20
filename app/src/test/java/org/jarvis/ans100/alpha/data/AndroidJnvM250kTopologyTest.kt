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

    @Test
    fun routeValidationRejectsOutOfRangeIds() {
        assertThrows(IllegalArgumentException::class.java) {
            AndroidJnvM250kTopology.requireAllowedConnection(-1, 20_000)
        }
    }
}
