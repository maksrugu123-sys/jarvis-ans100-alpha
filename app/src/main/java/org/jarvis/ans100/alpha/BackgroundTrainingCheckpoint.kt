package org.jarvis.ans100.alpha

/**
 * Pure recovery rules for the service-owned structured training checkpoint.
 * A task with a durable in-flight marker is never replayed automatically.
 */
data class BackgroundTrainingCheckpoint(
    val state: String,
    val totalTasks: Int,
    val completedTasks: Int,
    val inFlightIndex: Int,
) {
    enum class RecoveryDecision { NONE, START_FRESH, RESUME, INTERRUPTED, COMPLETE }

    init {
        require(state in setOf("idle", "pending", "running", "completed", "interrupted", "failed"))
        require(totalTasks >= 0)
        require(completedTasks in 0..totalTasks)
        require(inFlightIndex == -1 || inFlightIndex in 0 until totalTasks)
    }

    fun recoveryDecision(): RecoveryDecision = when (state) {
        "pending" -> RecoveryDecision.START_FRESH
        "running" -> when {
            inFlightIndex >= 0 -> RecoveryDecision.INTERRUPTED
            completedTasks == totalTasks -> RecoveryDecision.COMPLETE
            else -> RecoveryDecision.RESUME
        }
        else -> RecoveryDecision.NONE
    }
}
