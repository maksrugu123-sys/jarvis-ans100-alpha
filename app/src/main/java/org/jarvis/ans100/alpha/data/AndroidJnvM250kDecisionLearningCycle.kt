package org.jarvis.ans100.alpha.data

/**
 * Explicit orchestration bridge from a selected ANS decision to the independent
 * 250k JNVM runtime and its durable counterfactual R-STDP episode.
 *
 * The caller supplies the real decision, selected-action input events, and
 * outcome readout neurons. This class snapshots before scheduling, records
 * every completed runtime batch, and accepts only an externally observed reward.
 * It does not invent success/failure feedback or execute external side effects.
 */
class AndroidJnvM250kDecisionLearningCycle(
    private val runtime: AndroidJnvM250kRuntime,
    private val attribution: AndroidJnvM250kRewardAttribution,
) {
    /**
     * Captures the reachable graph and pre-action states, persists the decision
     * context, then schedules exactly the captured input batch.
     */
    @Synchronized
    fun begin(
        decision: ANS100Decision,
        episodeId: String,
        externalInputs: List<AndroidNeuronEvent>,
        outcomeNeuronIds: Set<Int>,
        maxGraphDepth: Int = AndroidJnvM250kRuntime.DEFAULT_COUNTERFACTUAL_GRAPH_DEPTH,
    ): AndroidJnvM250kDecisionEpisodeContext {
        check(!attribution.hasActiveEpisode()) { "finish or recover the active decision episode first" }
        val context = runtime.captureDecisionEpisodeContext(
            decision = decision,
            externalInputs = externalInputs,
            outcomeNeuronIds = outcomeNeuronIds,
            maxGraphDepth = maxGraphDepth,
        )
        attribution.beginDecisionEpisode(episodeId, decision, context)
        try {
            runtime.scheduleInputs(context.externalInputs)
        } catch (failure: RuntimeException) {
            // scheduleInputs is one bounded atomic enqueue; on failure, retire
            // the OPEN journal entry so no reward can be assigned to it.
            attribution.abortEpisode()
            throw failure
        }
        return context
    }

    /** Runs one bounded 250k batch and immediately journals its causal telemetry. */
    @Synchronized
    fun run(
        maxEvents: Int = AndroidJnvM250kRuntime.DEFAULT_MAX_EVENTS_PER_RUN,
        maxTimestamps: Int = AndroidJnvM250kRuntime.DEFAULT_MAX_TIMESTAMPS_PER_RUN,
    ): AndroidJnvM250kRunResult {
        check(attribution.hasDecisionEpisodeContext()) { "begin or recover a decision-bound episode before running" }
        check(!attribution.hasPendingReward()) { "a reward is pending; retry finish instead of running more events" }
        val result = runtime.run(maxEvents, maxTimestamps)
        attribution.recordRun(result)
        return result
    }

    /**
     * Finishes with an externally observed outcome score in [-1, 1].
     * The pre-action snapshot is replayed automatically and its gated decision
     * is persisted before the exact-edge R-STDP callback runs.
     */
    @Synchronized
    fun finish(
        observedReward: Double,
        policy: AndroidJnvM250kCounterfactualRewardGate.Policy =
            AndroidJnvM250kCounterfactualRewardGate.Policy(),
    ): AndroidJnvM250kCounterfactualFinishResult {
        check(attribution.hasDecisionEpisodeContext()) { "there is no active decision-bound episode to finish" }
        require(observedReward.isFinite() && observedReward in -1.0..1.0)
        return attribution.finishEpisodeWithCounterfactual(observedReward, policy)
    }

    /**
     * Use after recreating this bridge with a persistent attribution instance.
     * The journal restores the decision snapshot, response windows and any
     * REWARD_PENDING gate decision; this method never creates a new episode.
     */
    @Synchronized
    fun resumeRecoveredEpisode() {
        check(attribution.hasDecisionEpisodeContext()) { "there is no recoverable decision-bound episode" }
    }
}
