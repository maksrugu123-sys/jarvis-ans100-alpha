package org.jarvis.ans100.alpha

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundTrainingCheckpointTest {
    @Test fun pendingRunStartsFromCleanState() {
        assertEquals(
            BackgroundTrainingCheckpoint.RecoveryDecision.START_FRESH,
            BackgroundTrainingCheckpoint("pending", 12, 0, -1).recoveryDecision(),
        )
    }

    @Test fun boundaryBetweenTasksCanResumeWithoutResettingLearnedState() {
        assertEquals(
            BackgroundTrainingCheckpoint.RecoveryDecision.RESUME,
            BackgroundTrainingCheckpoint("running", 12, 5, -1).recoveryDecision(),
        )
    }

    @Test fun inFlightTaskIsNeverReplayedAfterProcessDeath() {
        assertEquals(
            BackgroundTrainingCheckpoint.RecoveryDecision.INTERRUPTED,
            BackgroundTrainingCheckpoint("running", 12, 5, 5).recoveryDecision(),
        )
    }

    @Test fun fullyCompletedRunIsNotStartedAgain() {
        assertEquals(
            BackgroundTrainingCheckpoint.RecoveryDecision.COMPLETE,
            BackgroundTrainingCheckpoint("running", 12, 12, -1).recoveryDecision(),
        )
    }

    @Test fun terminalStatesDoNotAutoResume() {
        assertEquals(
            BackgroundTrainingCheckpoint.RecoveryDecision.NONE,
            BackgroundTrainingCheckpoint("failed", 12, 5, -1).recoveryDecision(),
        )
    }
}
