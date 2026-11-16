package org.jarvis.ans100.alpha.data

import org.junit.Test

class ANS100NeuralAssociationEligibilityContractTest {
    private fun fixed(index: Int) =
        "ans100-v4-evidence-general-association-$index"

    private fun grown(index: Int) =
        "ans100-v5-grown-association-${"a".repeat(32)}-106$index-1200"

    @Test
    fun eligibilityAcceptsFixedGrownAndMixedCompletePairs() {
        ANS100NeuralAssociationEligibilityContract.validateEligibilityIds(
            listOf(fixed(1), fixed(2)),
        )
        ANS100NeuralAssociationEligibilityContract.validateEligibilityIds(
            listOf(grown(2), grown(3)),
        )
        ANS100NeuralAssociationEligibilityContract.validateEligibilityIds(
            listOf(fixed(1), fixed(2), grown(3), grown(4)),
        )
    }

    @Test
    fun weightQueryAcceptsEitherAssociationNamespace() {
        ANS100NeuralAssociationEligibilityContract.validateWeightQueryIds(listOf(fixed(1)))
        ANS100NeuralAssociationEligibilityContract.validateWeightQueryIds(listOf(grown(2)))
        ANS100NeuralAssociationEligibilityContract.validateWeightQueryIds(listOf(fixed(1), grown(2)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun eligibilityRejectsAnOddNumberOfSynapses() {
        ANS100NeuralAssociationEligibilityContract.validateEligibilityIds(
            listOf(fixed(1), fixed(2), grown(3)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun eligibilityRejectsUnknownConnectionNamespaces() {
        ANS100NeuralAssociationEligibilityContract.validateEligibilityIds(
            listOf("ans100-v6-unregistered-1", "ans100-v6-unregistered-2"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun eligibilityRejectsDuplicateConnectionIds() {
        ANS100NeuralAssociationEligibilityContract.validateEligibilityIds(
            listOf(fixed(1), fixed(1)),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun weightQueryRejectsUnknownConnectionNamespaces() {
        ANS100NeuralAssociationEligibilityContract.validateWeightQueryIds(
            listOf("ans100-v6-unregistered-1"),
        )
    }
}
