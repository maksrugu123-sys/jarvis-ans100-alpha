package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ANS100CandidateCompetitionTest {
    private val competition = ANS100CandidateCompetition()

    @Test
    fun strongestSupportedCandidateWinsAfterLateralInhibition() {
        val result = competition.evaluate(
            listOf(
                ANS100CandidateCompetition.Input("answer", 80, 5, 100, 100),
                ANS100CandidateCompetition.Input("clarify", 10, 70, 100, 100),
                ANS100CandidateCompetition.Input("abstain", 5, 75, 100, 100),
            ),
        )

        assertEquals("answer", result.winnerCandidateId)
        assertTrue(result.activations.getValue("answer") > result.activations.getValue("clarify"))
        assertTrue(result.winnerMargin > 0.05)
    }

    @Test
    fun exactTieDoesNotProduceAWinner() {
        val result = competition.evaluate(
            listOf(
                ANS100CandidateCompetition.Input("answer", 80, 0, 100, 100),
                ANS100CandidateCompetition.Input("clarify", 80, 0, 100, 100),
            ),
        )

        assertNull(result.winnerCandidateId)
        assertEquals(0.0, result.winnerMargin, 0.000001)
    }

    @Test
    fun conflictCanSuppressCandidateDespiteSupportSpikes() {
        val result = competition.evaluate(
            listOf(
                ANS100CandidateCompetition.Input("answer", 60, 65, 100, 100),
                ANS100CandidateCompetition.Input("clarify", 45, 5, 100, 100),
            ),
        )

        assertEquals("clarify", result.winnerCandidateId)
    }

    @Test
    fun incompleteSupportCannotWinByRawSpikeCount() {
        val result = competition.evaluate(
            listOf(
                ANS100CandidateCompetition.Input("answer", 40, 0, 100, 100),
                ANS100CandidateCompetition.Input("clarify", 1, 0, 2, 2),
            ),
        )

        assertEquals("clarify", result.winnerCandidateId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateCandidateIds() {
        competition.evaluate(
            listOf(
                ANS100CandidateCompetition.Input("answer", 1, 0, 10, 10),
                ANS100CandidateCompetition.Input("answer", 2, 0, 10, 10),
            ),
        )
    }
}
