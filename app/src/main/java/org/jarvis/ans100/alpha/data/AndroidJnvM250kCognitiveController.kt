package org.jarvis.ans100.alpha.data

/**
 * Coordinates one real decision-bound JNVM episode.
 *
 * This controller deliberately does not invent perception, choose an action,
 * or infer an external-world outcome. The caller supplies the selected ANS
 * decision, its concrete input events, and the outcome readout neurons. The
 * controller captures the pre-action graph, executes the runtime, records
 * observed transmissions/responses, and delegates bounded replay plus durable
 * exact-edge reward to the episode attribution journal.
 *
 * A completed neural readout is not proof of a successful real-world action.
 * The caller must convert an independently observed outcome into a bounded
 * reward and call finish only after the runtime queue has drained.
 */
class AndroidJnvM250kCognitiveController(
    private val runtime: AndroidJnvM250kRuntime,
    private val attribution: AndroidJnvM250kRewardAttribution,
) {
    enum class Phase {
        IDLE,
        EXECUTING,
        AWAITING_OUTCOME,
        FINISHED,
        REJECTED,
    }

    data class Progress(
        val phase: Phase,
        val run: AndroidJnvM250kRunResult,
        val batchesRecorded: Int,
    )

    data class DecisionStart(
        val decision: ANS100Decision,
        val encoding: AndroidJnvM250kEvidenceEncoder.Encoding?,
        val context: AndroidJnvM250kDecisionEpisodeContext?,
    )

    private val evidenceEncoder = AndroidJnvM250kEvidenceEncoder()
    private val hybridDecisionController = ANS100HybridPopulationController()

    private var phase = Phase.IDLE
    private var decisionContext: AndroidJnvM250kDecisionEpisodeContext? = null
    private var inputBatch: List<AndroidNeuronEvent> = emptyList()
    private var inputsScheduled = false
    private var batchesRecorded = 0
    private var finishResult: AndroidJnvM250kCounterfactualFinishResult? = null

    /**
     * Joins the existing typed-evidence + hybrid candidate competition path
     * to the 250k JNVM runtime. The 10k hybrid network must already have
     * completed its bounded pass; its actual telemetry/readout IDs are inputs.
     *
     * Clarification/abstention decisions are returned without starting a
     * reward episode. Only a selected answer candidate enters the JNVM loop.
     */
    @Synchronized
    fun decideAndBeginEpisode(
        episodeId: String,
        evidence: TypedEvidenceBundle,
        understanding: LocalUnderstanding,
        hybridTelemetry: AndroidHybridRunTelemetry,
        emittedSpikeNeuronIds: Set<Int>,
        processedEventCount: Int,
        pendingEvents: Int,
        budgetExhausted: Boolean,
        inputTimeNs: Long,
        outcomeNeuronIds: Set<Int>,
        maxGraphDepth: Int = AndroidJnvM250kRuntime.DEFAULT_COUNTERFACTUAL_GRAPH_DEPTH,
    ): DecisionStart {
        require(evidence.turnId.isNotBlank())
        val decision = hybridDecisionController.decide(
            evidence = evidence,
            understanding = understanding,
            telemetry = hybridTelemetry,
            emittedSpikeNeuronIds = emittedSpikeNeuronIds,
            processedEventCount = processedEventCount,
            pendingEvents = pendingEvents,
            budgetExhausted = budgetExhausted,
        )
        decision.validateAgainst(evidence)
        if (decision.status != ANS100DecisionStatus.SELECTED) {
            return DecisionStart(decision, encoding = null, context = null)
        }
        val encoding = evidenceEncoder.encode(evidence, inputTimeNs)
        val context = beginEpisode(
            episodeId = episodeId,
            decision = decision,
            externalInputs = encoding.events,
            outcomeNeuronIds = outcomeNeuronIds,
            maxGraphDepth = maxGraphDepth,
        )
        return DecisionStart(decision, encoding, context)
    }

    @Synchronized
    fun beginEpisode(
        episodeId: String,
        decision: ANS100Decision,
        externalInputs: List<AndroidNeuronEvent>,
        outcomeNeuronIds: Set<Int>,
        maxGraphDepth: Int = AndroidJnvM250kRuntime.DEFAULT_COUNTERFACTUAL_GRAPH_DEPTH,
    ): AndroidJnvM250kDecisionEpisodeContext {
        check(phase == Phase.IDLE || phase == Phase.FINISHED || phase == Phase.REJECTED) {
            "the previous cognitive episode has not reached a terminal state"
        }
        require(decision.status == ANS100DecisionStatus.SELECTED) {
            "only a selected ANS decision can start an executable episode"
        }
        require(externalInputs.isNotEmpty()) { "a real external input batch is required" }

        // Capture before scheduling any input: this is the baseline for replay.
        val context = runtime.captureDecisionEpisodeContext(
            decision = decision,
            externalInputs = externalInputs,
            outcomeNeuronIds = outcomeNeuronIds,
            maxGraphDepth = maxGraphDepth,
        )
        attribution.beginDecisionEpisode(episodeId, decision, context)

        decisionContext = context
        inputBatch = externalInputs.toList()
        inputsScheduled = false
        batchesRecorded = 0
        finishResult = null
        phase = Phase.EXECUTING
        return context
    }

    /**
     * Executes one bounded runtime batch. Call again while pendingEvents > 0.
     * The first call schedules the exact input batch captured at episode start.
     */
    @Synchronized
    fun runNextBatch(
        maxEvents: Int = AndroidJnvM250kRuntime.DEFAULT_MAX_EVENTS_PER_RUN,
        maxTimestamps: Int = AndroidJnvM250kRuntime.DEFAULT_MAX_TIMESTAMPS_PER_RUN,
    ): Progress {
        check(phase == Phase.EXECUTING) { "there is no episode awaiting runtime execution" }
        if (!inputsScheduled) {
            runtime.scheduleInputs(inputBatch)
            inputsScheduled = true
        }
        val result = runtime.run(maxEvents, maxTimestamps)
        attribution.recordRun(result)
        if (result.processedEvents > 0) batchesRecorded++
        check(result.processedEvents > 0 || result.pendingEvents > 0) {
            "runtime made no progress; refusing to mark an empty episode as completed"
        }
        if (result.pendingEvents == 0 && result.processedEvents > 0) {
            phase = Phase.AWAITING_OUTCOME
        }
        return Progress(phase, result, batchesRecorded)
    }

    /**
     * Applies an independently assessed scalar outcome only after all queued
     * neural events have been processed. Replay and exact-edge reward decisions
     * are persisted by the existing SQLite episode journal before synapse writes.
     */
    @Synchronized
    fun finishEpisode(
        observedOutcomeReward: Double,
        policy: AndroidJnvM250kCounterfactualRewardGate.Policy =
            AndroidJnvM250kCounterfactualRewardGate.Policy(),
    ): AndroidJnvM250kCounterfactualFinishResult {
        finishResult?.let { return it }
        check(phase == Phase.AWAITING_OUTCOME) {
            "finish requires a drained runtime queue and at least one processed event"
        }
        require(observedOutcomeReward.isFinite() && observedOutcomeReward in -1.0..1.0)
        val result = attribution.finishEpisodeWithCounterfactual(observedOutcomeReward, policy)
        finishResult = result
        phase = if (result.decision.mayApplyReward) Phase.FINISHED else Phase.REJECTED
        return result
    }

    @Synchronized
    fun currentPhase(): Phase = phase

    @Synchronized
    fun capturedDecisionContext(): AndroidJnvM250kDecisionEpisodeContext? = decisionContext
}
