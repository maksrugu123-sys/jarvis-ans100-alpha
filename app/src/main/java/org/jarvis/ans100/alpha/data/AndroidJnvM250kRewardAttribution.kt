package org.jarvis.ans100.alpha.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

/** Result of a durable counterfactual-gated episode decision. */
data class AndroidJnvM250kCounterfactualFinishResult(
    val episodeId: String,
    val rewardId: String,
    val decision: AndroidJnvM250kCounterfactualRewardGate.Decision,
    val rewardResult: AndroidJnvM250kRewardResult?,
    val replayedPersistedDecision: Boolean,
)

/** Outcome attribution supported by observed target-neuron responses. */
data class AndroidJnvM250kAttributionResult(
    val episodeId: String,
    val actionId: String,
    val creditedConnectionIds: Set<String>,
    val signalPathConnectionIds: List<String>,
    val observedLinks: Long,
    val omittedLinks: Long,
    val responseScores: Map<String, Double>,
    val unansweredTransmissions: Long,
    val contestedTargetResponses: Long,
    val ambiguousTargetResponses: Long,
    val observationCoverage: Double,
    val rewardResult: AndroidJnvM250kRewardResult,
)

/**
 * Bounded episode bridge from sparse transmissions to exact synapse R-STDP.
 *
 * A transmission is only a candidate. It becomes reward-eligible only when
 * its target emits a later spike within the response window. When multiple
 * candidate inputs reach the same target before that spike, the response is
 * divided evenly among them. This is temporal response evidence, not a true
 * counterfactual proof that any one edge caused the outcome.
 *
 * Supplying a durable journal preserves the active episode and pending
 * response windows across process death.
 */
class AndroidJnvM250kRewardAttribution(
    private val applyReward: (rewardId: String, reward: Double, connectionIds: Set<String>) -> AndroidJnvM250kRewardResult,
    private val maxCreditedConnections: Int = DEFAULT_MAX_CREDITED_CONNECTIONS,
    private val maxTrackedConnections: Int = DEFAULT_MAX_TRACKED_CONNECTIONS,
    private val journal: AndroidJnvM250kEpisodeJournal? = null,
    private val applyWeightedReward: ((rewardId: String, reward: Double, connectionCredits: Map<String, Double>) -> AndroidJnvM250kRewardResult)? = null,
) : Closeable {
    private data class Candidate(
        val connectionId: String,
        val targetNeuronId: Int,
        var estimatedTransmissions: Long,
        var responseCount: Long,
        var responseCredit: Double,
        val firstSeenOrder: Long,
    )

    private data class PendingTransmission(
        val connectionId: String,
        val targetNeuronId: Int,
        val arrivalTimestampNs: Long,
    )

    private data class Episode(
        val id: String,
        val actionId: String,
        val candidates: LinkedHashMap<String, Candidate> = LinkedHashMap(),
        val pending: ArrayList<PendingTransmission> = ArrayList(),
        val path: ArrayList<String> = ArrayList(),
        var observedLinks: Long = 0L,
        var omittedLinks: Long = 0L,
        var unansweredTransmissions: Long = 0L,
        var unobservedTransmissions: Long = 0L,
        var runtimeOmittedTransmissions: Long = 0L,
        var contestedTargetResponses: Long = 0L,
        var ambiguousTargetResponses: Long = 0L,
        var nextOrder: Long = 0L,
        var pendingReward: Double? = null,
        var legacyUnweightedReward: Boolean = false,
        var counterfactualDecisionJson: String? = null,
        var counterfactualContextJson: String? = null,
        var recordedRuntimeBatches: Long = 0L,
        var lastRunPendingEvents: Int = 0,
        var lastRunBudgetExhausted: Boolean = false,
    )

    private var activeEpisode: Episode? = journal?.loadRecoverable()?.let(::decodeEpisode)

    init {
        require(maxCreditedConnections in 1..MAX_CREDITED_CONNECTIONS)
        require(maxTrackedConnections in maxCreditedConnections..MAX_TRACKED_CONNECTIONS)
    }

    @Synchronized
    fun beginEpisode(episodeId: String, actionId: String) {
        require(episodeId.matches(SAFE_ID)) { "episodeId must be a safe stable identifier" }
        require(actionId.matches(SAFE_ID)) { "actionId must be a safe stable identifier" }
        check(activeEpisode == null) { "finish or abort the active reward episode first" }
        val episode = Episode(episodeId, actionId)
        journal?.begin(episodeId, encodeEpisode(episode))
        activeEpisode = episode
    }

    /**
     * Opens an episode directly from a selected ANS decision and its durable
     * pre-action JNVM snapshot. The snapshot is committed before any runtime input.
     */
    @Synchronized
    fun beginDecisionEpisode(
        episodeId: String,
        decision: ANS100Decision,
        context: AndroidJnvM250kDecisionEpisodeContext,
    ) {
        require(episodeId.matches(SAFE_ID)) { "episodeId must be a safe stable identifier" }
        check(journal != null) { "decision-bound learning requires a persistent episode journal" }
        check(applyWeightedReward != null) { "decision-bound learning requires the weighted exact-edge reward callback" }
        context.validateAgainst(decision)
        check(activeEpisode == null) { "finish or abort the active reward episode first" }
        val actionId = "decision-" + context.selectedCandidateId
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .take(52)
        val episode = Episode(episodeId, actionId).apply {
            counterfactualContextJson = context.toJson().toString()
        }
        journal?.begin(episodeId, encodeEpisode(episode))
        activeEpisode = episode
    }

    /**
     * Adds new transmissions, correlates them with later target spikes, then
     * expires response windows that have elapsed. All retained state is bounded.
     */
    @Synchronized
    fun recordRun(result: AndroidJnvM250kRunResult) {
        val currentEpisode = activeEpisode ?: error("begin or recover an episode before recording runtime activity")
        check(currentEpisode.pendingReward == null) { "episode reward is pending; retry finishEpisode instead of recording more activity" }
        val episode = copyEpisode(currentEpisode)
        require(result.omittedCausalLinks >= 0)
        require(result.processedEvents >= 0 && result.pendingEvents >= 0)
        require(result.currentTimeNs >= 0L)
        episode.lastRunPendingEvents = result.pendingEvents
        episode.lastRunBudgetExhausted = result.budgetExhausted
        if (result.processedEvents > 0) {
            episode.recordedRuntimeBatches = saturatedAdd(episode.recordedRuntimeBatches, 1L)
        }
        require(result.emittedSpikes.all {
            it.spikeSequence >= 0L && it.timestampNs >= 0L &&
                it.neuronId in 0 until AndroidJnvM250kProfile.NEURON_CAPACITY
        })
        episode.omittedLinks = saturatedAdd(episode.omittedLinks, result.omittedCausalLinks.toLong())
        episode.runtimeOmittedTransmissions = saturatedAdd(
            episode.runtimeOmittedTransmissions,
            result.omittedCausalLinks.toLong(),
        )

        data class TimelineEvent(
            val timestampNs: Long,
            val link: AndroidJnvM250kCausalLink? = null,
            val spike: AndroidJnvM250kSpike? = null,
            val ambiguous: Boolean = false,
        )
        val emittedSpikeIds = result.emittedSpikes.mapTo(HashSet()) { it.neuronId }
        require(result.ambiguousTargetSpikeNeuronIds.all {
            it in 0 until AndroidJnvM250kProfile.NEURON_CAPACITY && it in emittedSpikeIds
        })
        val timeline = ArrayList<TimelineEvent>(result.causalLinks.size + result.emittedSpikes.size)
        result.causalLinks.forEach { timeline += TimelineEvent(it.arrivalTimestampNs, link = it) }
        result.emittedSpikes.forEach { spike ->
            timeline += TimelineEvent(
                timestampNs = spike.timestampNs,
                spike = spike,
                ambiguous = spike.neuronId in result.ambiguousTargetSpikeNeuronIds,
            )
        }
        // At an equal timestamp, process arriving synaptic input before the target spike.
        timeline.sortWith(compareBy<TimelineEvent> { it.timestampNs }.thenBy { if (it.link != null) 0 else 1 })

        for (event in timeline) {
            val link = event.link
            if (link != null) {
                episode.observedLinks = saturatedAdd(episode.observedLinks, 1L)
                val candidate = addCandidate(episode, link)
                if (episode.path.size < maxTrackedConnections) episode.path += link.connectionId
                else episode.omittedLinks = saturatedAdd(episode.omittedLinks, 1L)

                if (candidate != null && episode.pending.none { it.connectionId == link.connectionId }) {
                    if (episode.pending.size < maxTrackedConnections) {
                        episode.pending += PendingTransmission(
                            connectionId = link.connectionId,
                            targetNeuronId = link.targetNeuronId,
                            arrivalTimestampNs = link.arrivalTimestampNs,
                        )
                    } else {
                        episode.omittedLinks = saturatedAdd(episode.omittedLinks, 1L)
                    }
                }
                continue
            }

            val spike = event.spike ?: continue
            val matching = episode.pending.filter {
                it.targetNeuronId == spike.neuronId &&
                    it.arrivalTimestampNs <= spike.timestampNs &&
                    spike.timestampNs - it.arrivalTimestampNs <= TARGET_RESPONSE_WINDOW_NS
            }
            if (matching.isEmpty()) continue
            if (event.ambiguous) {
                episode.ambiguousTargetResponses = saturatedAdd(episode.ambiguousTargetResponses, 1L)
                episode.unobservedTransmissions = saturatedAdd(
                    episode.unobservedTransmissions,
                    matching.size.toLong(),
                )
                val ambiguousIds = matching.mapTo(HashSet()) { it.connectionId }
                episode.pending.removeAll { it.connectionId in ambiguousIds }
                continue
            }
            val share = 1.0 / matching.size.toDouble()
            for (transmission in matching) {
                val candidate = episode.candidates[transmission.connectionId] ?: continue
                candidate.responseCount = saturatedAdd(candidate.responseCount, 1L)
                candidate.responseCredit = (candidate.responseCredit + share)
                    .coerceAtMost(MAX_RESPONSE_CREDIT)
            }
            if (matching.size > 1) {
                episode.contestedTargetResponses = saturatedAdd(episode.contestedTargetResponses, 1L)
            }
            val matchedIds = matching.mapTo(HashSet()) { it.connectionId }
            episode.pending.removeAll { it.connectionId in matchedIds }
        }

        val observedClock = maxOf(
            result.currentTimeNs,
            result.emittedSpikes.maxOfOrNull { it.timestampNs } ?: 0L,
        )
        val expired = episode.pending.filter {
            observedClock > it.arrivalTimestampNs &&
                observedClock - it.arrivalTimestampNs > TARGET_RESPONSE_WINDOW_NS
        }
        if (expired.isNotEmpty()) {
            episode.unansweredTransmissions = saturatedAdd(
                episode.unansweredTransmissions,
                expired.size.toLong(),
            )
            val expiredIds = expired.mapTo(HashSet()) { it.connectionId }
            episode.pending.removeAll { it.connectionId in expiredIds }
        }
        journal?.saveOpen(episode.id, encodeEpisode(episode))
        activeEpisode = episode
    }

    /**
     * Persists REWARD_PENDING before touching synapses. Only connections with
     * a temporally matched target response can receive the scalar episode reward.
     */
    @Synchronized
    fun finishEpisode(reward: Double): AndroidJnvM250kAttributionResult {
        require(reward.isFinite() && reward in -1.0..1.0)
        val currentEpisode = activeEpisode ?: error("there is no active reward episode")
        val episode = copyEpisode(currentEpisode)
        check(episode.counterfactualDecisionJson == null) {
            "this episode has a persisted counterfactual decision; retry finishEpisodeWithCounterfactual"
        }
        val pending = episode.pendingReward
        if (pending != null && pending != reward) {
            throw IllegalArgumentException("recovered episode has a different persisted reward; retry with the original value")
        }
        val responders = episode.candidates.values.filter { it.responseCount > 0L }
        require(responders.isNotEmpty()) {
            "no target-neuron response was observed; transmissions alone cannot receive reward"
        }
        val selected = responders.sortedWith(
            compareByDescending<Candidate> { it.responseCredit }
                .thenByDescending { it.responseCount }
                .thenByDescending { it.estimatedTransmissions }
                .thenBy { it.firstSeenOrder },
        ).take(maxCreditedConnections)
        val credited = selected.mapTo(LinkedHashSet()) { it.connectionId }
        val knownTransmissionTotal = saturatedAdd(episode.observedLinks, episode.runtimeOmittedTransmissions)
        val assessedTransmissions = (episode.observedLinks - episode.unobservedTransmissions).coerceAtLeast(0L)
        val coverage = if (knownTransmissionTotal == 0L) 0.0 else
            (assessedTransmissions.toDouble() / knownTransmissionTotal.toDouble()).coerceIn(0.0, 1.0)
        require(coverage > 0.0) { "no assessed transmission evidence remains after ambiguity and bounded-trace losses" }
        val scores = selected.associate { candidate ->
            candidate.connectionId to (
                (candidate.responseCredit / candidate.responseCount.toDouble()).coerceIn(0.0, 1.0) * coverage
            ).coerceIn(0.0, 1.0)
        }

        if (pending == null) {
            episode.pendingReward = reward
            journal?.prepareReward(episode.id, encodeEpisode(episode), reward)
        }
        // Publish the pending state only after its durable write succeeds.
        activeEpisode = episode
        val rewardId = "episode-${episode.id}"
        val rewardResult = if (episode.legacyUnweightedReward) {
            applyReward(rewardId, reward, credited)
        } else {
            applyWeightedReward?.invoke(rewardId, reward, scores)
                ?: applyReward(rewardId, reward, credited)
        }
        journal?.complete(episode.id)
        val result = AndroidJnvM250kAttributionResult(
            episodeId = episode.id,
            actionId = episode.actionId,
            creditedConnectionIds = credited,
            signalPathConnectionIds = episode.path.toList(),
            observedLinks = episode.observedLinks,
            omittedLinks = episode.omittedLinks,
            responseScores = scores,
            unansweredTransmissions = saturatedAdd(episode.unansweredTransmissions, episode.pending.size.toLong()),
            contestedTargetResponses = episode.contestedTargetResponses,
            ambiguousTargetResponses = episode.ambiguousTargetResponses,
            observationCoverage = coverage,
            rewardResult = rewardResult,
        )
        activeEpisode = null
        return result
    }

    /**
     * Durable opt-in path for counterfactual-gated R-STDP.
     *
     * The exact gate decision and accepted per-edge multipliers are stored
     * before reward application. A recovered pending episode always replays
     * that stored map; a newly supplied report/policy cannot alter it.
     */
    /**
     * Runs the counterfactual replay from the exact pre-action snapshot stored
     * with beginDecisionEpisode, then durably applies only the gated edge credits.
     *
     * On a recovered REWARD_PENDING episode, the stored decision is replayed
     * without recomputing the report.
     */
    @Synchronized
    fun finishEpisodeWithCounterfactual(
        reward: Double,
        policy: AndroidJnvM250kCounterfactualRewardGate.Policy =
            AndroidJnvM250kCounterfactualRewardGate.Policy(),
    ): AndroidJnvM250kCounterfactualFinishResult {
        require(reward.isFinite() && reward in -1.0..1.0)
        check(journal != null) { "counterfactual episode learning requires a persistent episode journal" }
        check(applyWeightedReward != null) { "counterfactual episode learning requires the weighted exact-edge reward callback" }
        val episode = activeEpisode ?: error("there is no active reward episode")
        if (episode.counterfactualDecisionJson != null) {
            // The durable decision, not a newly computed report, is authoritative on retry.
            return finishEpisodeWithCounterfactual(
                reward = reward,
                report = AndroidJnvM250kCounterfactualEvaluator.Report(
                    candidates = emptyList(),
                    baselineProcessedEvents = 0,
                    baselineProcessedTimestamps = 0,
                    baselineComplete = true,
                ),
                policy = policy,
            )
        }
        val contextJson = episode.counterfactualContextJson
            ?: error("episode has no persisted pre-action decision snapshot")
        val context = AndroidJnvM250kDecisionEpisodeContext.fromJson(JSONObject(contextJson))
        requireCompletedDecisionRuntime(episode)
        val scores = responseScoresFor(episode)
        val candidateIds = scores.entries
            .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
            .take(AndroidJnvM250kCounterfactualEvaluator.DEFAULT_MAX_CANDIDATES)
            .mapTo(LinkedHashSet()) { it.key }
        require(candidateIds.isNotEmpty()) { "no observed target-response candidates are available for replay" }
        val report = AndroidJnvM250kCounterfactualEvaluator.evaluate(
            initialStates = context.initialStates,
            connections = context.connections,
            externalInputs = context.externalInputs,
            candidateConnectionIds = candidateIds,
            outcomeNeuronIds = context.outcomeNeuronIds,
        )
        return finishEpisodeWithCounterfactual(reward, report, policy)
    }

    @Synchronized
    fun finishEpisodeWithCounterfactual(
        reward: Double,
        report: AndroidJnvM250kCounterfactualEvaluator.Report,
        policy: AndroidJnvM250kCounterfactualRewardGate.Policy =
            AndroidJnvM250kCounterfactualRewardGate.Policy(),
    ): AndroidJnvM250kCounterfactualFinishResult {
        require(reward.isFinite() && reward in -1.0..1.0)
        check(journal != null) { "counterfactual episode learning requires a persistent episode journal" }
        val weightedCallback = applyWeightedReward
            ?: error("counterfactual-gated reward requires applyWeightedReward; unweighted fallback is unsafe")
        val current = activeEpisode ?: error("there is no active reward episode")
        val episode = copyEpisode(current)
        val rewardId = "counterfactual-episode-${episode.id}"
        require(rewardId.length <= 160) { "episode ID is too long for a stable counterfactual reward ID" }

        val existing = episode.counterfactualDecisionJson
        val replayed = existing != null
        val decision: AndroidJnvM250kCounterfactualRewardGate.Decision
        if (existing != null) {
            require(episode.pendingReward == reward) {
                "recovered counterfactual episode has a different persisted reward; retry with the original value"
            }
            val persisted = JSONObject(existing)
            require(persisted.getString("reward_id") == rewardId && persisted.getDouble("reward") == reward) {
                "persisted counterfactual receipt identity or reward does not match this retry"
            }
            decision = decodeCounterfactualDecision(persisted)
            require(decision.mayApplyReward) { "persisted counterfactual decision is not reward-pending" }
        } else {
            check(episode.pendingReward == null) {
                "episode already has a different reward path pending; retry it with its original API"
            }
            if (episode.counterfactualContextJson != null) requireCompletedDecisionRuntime(episode)
            val allScores = responseScoresFor(episode)
            val evaluatedIds = report.candidates.mapTo(LinkedHashSet()) { it.connectionId }
            val scores = allScores.filterKeys { it in evaluatedIds }
            episode.counterfactualContextJson?.let { contextJson ->
                val context = AndroidJnvM250kDecisionEpisodeContext.fromJson(JSONObject(contextJson))
                val expectedIds = allScores.entries
                    .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
                    .take(AndroidJnvM250kCounterfactualEvaluator.DEFAULT_MAX_CANDIDATES)
                    .mapTo(LinkedHashSet()) { it.key }
                require(report == AndroidJnvM250kCounterfactualEvaluator.evaluate(
                    initialStates = context.initialStates,
                    connections = context.connections,
                    externalInputs = context.externalInputs,
                    candidateConnectionIds = expectedIds,
                    outcomeNeuronIds = context.outcomeNeuronIds,
                )) { "counterfactual report does not match the persisted pre-action snapshot" }
            }
            decision = AndroidJnvM250kCounterfactualRewardGate.evaluate(
                reward = reward,
                responseScores = scores,
                report = report,
                policy = policy,
            )
            episode.counterfactualDecisionJson =
                encodeCounterfactualDecision(rewardId, reward, decision, report, policy).toString()
            if (decision.mayApplyReward) {
                episode.pendingReward = reward
                // Persist exact accepted IDs and multipliers before calling R-STDP.
                journal?.prepareReward(episode.id, encodeEpisode(episode), reward)
                activeEpisode = episode
            } else {
                // Terminal rejection is durable and never invokes a reward callback.
                journal?.reject(episode.id, encodeEpisode(episode), reward)
                activeEpisode = null
                return AndroidJnvM250kCounterfactualFinishResult(
                    episodeId = episode.id,
                    rewardId = rewardId,
                    decision = decision,
                    rewardResult = null,
                    replayedPersistedDecision = false,
                )
            }
        }

        val credits = decision.acceptedCredits.toSortedMap()
        check(credits.isNotEmpty())
        val result = weightedCallback(rewardId, reward, credits)
        journal?.complete(episode.id)
        activeEpisode = null
        return AndroidJnvM250kCounterfactualFinishResult(
            episodeId = episode.id,
            rewardId = rewardId,
            decision = decision,
            rewardResult = result,
            replayedPersistedDecision = replayed,
        )
    }

    private fun requireCompletedDecisionRuntime(episode: Episode) {
        require(episode.recordedRuntimeBatches > 0L) {
            "decision episode has no completed 250k runtime batch"
        }
        require(episode.lastRunPendingEvents == 0 && !episode.lastRunBudgetExhausted) {
            "decision episode still has pending events or an exhausted runtime budget"
        }
    }

    private fun responseScoresFor(episode: Episode): Map<String, Double> {
        val responders = episode.candidates.values.filter { it.responseCount > 0L }
        require(responders.isNotEmpty()) {
            "no target-neuron response was observed; transmissions alone cannot receive counterfactual reward"
        }
        val selected = responders.sortedWith(
            compareByDescending<Candidate> { it.responseCredit }
                .thenByDescending { it.responseCount }
                .thenByDescending { it.estimatedTransmissions }
                .thenBy { it.firstSeenOrder },
        ).take(maxCreditedConnections)
        val known = saturatedAdd(episode.observedLinks, episode.runtimeOmittedTransmissions)
        val assessed = (episode.observedLinks - episode.unobservedTransmissions).coerceAtLeast(0L)
        val coverage = if (known == 0L) 0.0 else
            (assessed.toDouble() / known.toDouble()).coerceIn(0.0, 1.0)
        require(coverage > 0.0) { "no assessed transmission evidence remains" }
        return selected.associate { candidate ->
            candidate.connectionId to (
                (candidate.responseCredit / candidate.responseCount.toDouble()).coerceIn(0.0, 1.0) *
                    coverage
            ).coerceIn(0.0, 1.0)
        }
    }

    private fun encodeCounterfactualDecision(
        rewardId: String,
        reward: Double,
        decision: AndroidJnvM250kCounterfactualRewardGate.Decision,
        report: AndroidJnvM250kCounterfactualEvaluator.Report,
        policy: AndroidJnvM250kCounterfactualRewardGate.Policy,
    ): JSONObject {
        val credits = JSONObject()
        decision.acceptedCredits.toSortedMap().forEach { (id, value) -> credits.put(id, value) }
        val rejected = JSONObject()
        decision.rejected.toSortedMap().forEach { (id, reason) -> rejected.put(id, reason.name) }
        val effects = JSONArray()
        report.candidates.sortedBy { it.connectionId }.forEach { effect ->
            effects.put(JSONObject().apply {
                put("id", effect.connectionId)
                put("baseline_spikes", effect.baselineOutcomeSpikes)
                put("counterfactual_spikes", effect.counterfactualOutcomeSpikes)
                put("delta", effect.outcomeSpikeDelta)
                put("sensitivity", effect.sensitivityScore)
                put("baseline_complete", effect.baselineComplete)
                put("counterfactual_complete", effect.counterfactualComplete)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("reward_id", rewardId)
            put("reward", reward)
            put("baseline_accepted", decision.baselineAccepted)
            put("reward_accepted", decision.rewardAccepted)
            put("accepted_credits", credits)
            put("rejected", rejected)
            put("policy_min_sensitivity", policy.minimumSensitivity)
            put("policy_min_combined_confidence", policy.minimumCombinedConfidence)
            put("policy_min_abs_delta", policy.minimumAbsoluteOutcomeDelta)
            put("report_baseline_complete", report.baselineComplete)
            put("report_baseline_events", report.baselineProcessedEvents)
            put("report_baseline_timestamps", report.baselineProcessedTimestamps)
            put("report_candidates", effects)
        }.also {
            require(it.toString().length <= AndroidJnvM250kEpisodeJournal.MAX_PAYLOAD_CHARS / 2) {
                "counterfactual decision exceeds its durable journal bound"
            }
        }
    }

    private fun decodeCounterfactualDecision(
        json: JSONObject,
    ): AndroidJnvM250kCounterfactualRewardGate.Decision {
        require(json.getInt("version") == 1)
        val creditsJson = json.getJSONObject("accepted_credits")
        val credits = LinkedHashMap<String, Double>()
        val creditKeys = creditsJson.keys()
        while (creditKeys.hasNext()) {
            val id = creditKeys.next()
            val value = creditsJson.getDouble(id)
            require(id.matches(CONNECTION_ID) && value.isFinite() && value in 0.0..1.0)
            credits[id] = value
        }
        val rejectedJson = json.getJSONObject("rejected")
        val rejected = LinkedHashMap<String, AndroidJnvM250kCounterfactualRewardGate.Rejection>()
        val rejectedKeys = rejectedJson.keys()
        while (rejectedKeys.hasNext()) {
            val id = rejectedKeys.next()
            require(id.matches(CONNECTION_ID))
            rejected[id] = AndroidJnvM250kCounterfactualRewardGate.Rejection.valueOf(
                rejectedJson.getString(id),
            )
        }
        val decision = AndroidJnvM250kCounterfactualRewardGate.Decision(
            acceptedCredits = credits.toSortedMap(),
            rejected = rejected.toSortedMap(),
            baselineAccepted = json.getBoolean("baseline_accepted"),
            rewardAccepted = json.getBoolean("reward_accepted"),
        )
        require(decision.mayApplyReward && credits.isNotEmpty())
        require(credits.keys.intersect(rejected.keys).isEmpty())
        return decision
    }

    @Synchronized
    fun abortEpisode() {
        val episode = activeEpisode ?: error("there is no active reward episode")
        check(episode.pendingReward == null) { "cannot abort an episode after reward application has been prepared" }
        journal?.abort(episode.id)
        activeEpisode = null
    }

    @Synchronized
    fun hasActiveEpisode(): Boolean = activeEpisode != null

    @Synchronized
    fun hasDecisionEpisodeContext(): Boolean = activeEpisode?.counterfactualContextJson != null

    @Synchronized
    fun trackedConnectionCount(): Int = activeEpisode?.candidates?.size ?: 0

    @Synchronized
    override fun close() {
        journal?.close()
    }

    private fun copyEpisode(source: Episode): Episode = source.copy(
        candidates = LinkedHashMap(source.candidates.mapValues { (_, candidate) -> candidate.copy() }),
        pending = ArrayList(source.pending),
        path = ArrayList(source.path),
    )

    /** Bounded Space-Saving sketch; returns null when an ID is not retained. */
    private fun addCandidate(episode: Episode, link: AndroidJnvM250kCausalLink): Candidate? {
        require(link.connectionId.matches(CONNECTION_ID)) { "unsafe connection ID in runtime transmission" }
        val current = episode.candidates[link.connectionId]
        if (current != null) {
            require(current.targetNeuronId == link.targetNeuronId) {
                "a stable connection ID cannot change its target neuron"
            }
            current.estimatedTransmissions = saturatedAdd(current.estimatedTransmissions, 1L)
            return current
        }
        if (episode.candidates.size < maxTrackedConnections) {
            return Candidate(
                connectionId = link.connectionId,
                targetNeuronId = link.targetNeuronId,
                estimatedTransmissions = 1L,
                responseCount = 0L,
                responseCredit = 0.0,
                firstSeenOrder = episode.nextOrder++,
            ).also { episode.candidates[link.connectionId] = it }
        }
        val least = episode.candidates.values.minWithOrNull(
            compareBy<Candidate> { it.estimatedTransmissions }.thenBy { it.firstSeenOrder },
        ) ?: return null
        episode.candidates.remove(least.connectionId)
        val evictedPendingCount = episode.pending.count { it.connectionId == least.connectionId }
        if (evictedPendingCount > 0) {
            episode.unobservedTransmissions = saturatedAdd(
                episode.unobservedTransmissions,
                evictedPendingCount.toLong(),
            )
        }
        episode.pending.removeAll { it.connectionId == least.connectionId }
        return Candidate(
            connectionId = link.connectionId,
            targetNeuronId = link.targetNeuronId,
            estimatedTransmissions = saturatedAdd(least.estimatedTransmissions, 1L),
            responseCount = 0L,
            responseCredit = 0.0,
            firstSeenOrder = episode.nextOrder++,
        ).also { episode.candidates[link.connectionId] = it }
    }

    private fun encodeEpisode(episode: Episode): String {
        val candidates = JSONArray()
        episode.candidates.values.forEach { candidate ->
            candidates.put(JSONObject().apply {
                put("id", candidate.connectionId)
                put("target", candidate.targetNeuronId)
                put("count", candidate.estimatedTransmissions)
                put("responses", candidate.responseCount)
                put("response_credit", candidate.responseCredit)
                put("order", candidate.firstSeenOrder)
            })
        }
        val pending = JSONArray()
        episode.pending.forEach { transmission ->
            pending.put(JSONObject().apply {
                put("id", transmission.connectionId)
                put("target", transmission.targetNeuronId)
                put("arrival_ns", transmission.arrivalTimestampNs)
            })
        }
        val path = JSONArray()
        episode.path.forEach { path.put(it) }
        return JSONObject().apply {
            put("snapshot_version", SNAPSHOT_VERSION)
            put("episode_id", episode.id)
            put("action_id", episode.actionId)
            put("legacy_unweighted_reward", episode.legacyUnweightedReward)
            put("candidates", candidates)
            put("pending", pending)
            put("path", path)
            put("observed_links", episode.observedLinks)
            put("omitted_links", episode.omittedLinks)
            put("unanswered_transmissions", episode.unansweredTransmissions)
            put("unobserved_transmissions", episode.unobservedTransmissions)
            put("runtime_omitted_transmissions", episode.runtimeOmittedTransmissions)
            put("contested_responses", episode.contestedTargetResponses)
            put("ambiguous_target_responses", episode.ambiguousTargetResponses)
            put("next_order", episode.nextOrder)
            put("recorded_runtime_batches", episode.recordedRuntimeBatches)
            put("last_run_pending_events", episode.lastRunPendingEvents)
            put("last_run_budget_exhausted", episode.lastRunBudgetExhausted)
            if (episode.pendingReward == null) put("pending_reward", JSONObject.NULL)
            else put("pending_reward", episode.pendingReward!!)
            if (episode.counterfactualDecisionJson == null) put("counterfactual_decision", JSONObject.NULL)
            else put("counterfactual_decision", JSONObject(episode.counterfactualDecisionJson!!))
            if (episode.counterfactualContextJson == null) put("counterfactual_context", JSONObject.NULL)
            else put("counterfactual_context", JSONObject(episode.counterfactualContextJson!!))
        }.toString().also {
            require(it.length <= AndroidJnvM250kEpisodeJournal.MAX_PAYLOAD_CHARS) {
                "episode snapshot exceeds the durable journal bound"
            }
        }
    }

    private fun decodeEpisode(record: AndroidJnvM250kEpisodeJournal.Record): Episode {
        val json = JSONObject(record.payload)
        if (!json.has("snapshot_version")) return decodeLegacyEpisode(record, json)
        val snapshotVersion = json.getInt("snapshot_version")
        require(snapshotVersion in MIN_SUPPORTED_SNAPSHOT_VERSION..SNAPSHOT_VERSION) {
            "unsupported episode snapshot version"
        }
        val episode = Episode(
            id = json.getString("episode_id"),
            actionId = json.getString("action_id"),
            observedLinks = json.getLong("observed_links"),
            omittedLinks = json.getLong("omitted_links"),
            unansweredTransmissions = json.getLong("unanswered_transmissions"),
            unobservedTransmissions = json.getLong("unobserved_transmissions"),
            runtimeOmittedTransmissions = if (snapshotVersion >= 3) json.getLong("runtime_omitted_transmissions") else 0L,
            contestedTargetResponses = json.getLong("contested_responses"),
            ambiguousTargetResponses = if (snapshotVersion >= 4) json.getLong("ambiguous_target_responses") else 0L,
            nextOrder = json.getLong("next_order"),
            pendingReward = if (json.isNull("pending_reward")) null else json.getDouble("pending_reward"),
            legacyUnweightedReward = json.optBoolean("legacy_unweighted_reward", false),
            counterfactualDecisionJson = if (
                snapshotVersion >= 5 && !json.isNull("counterfactual_decision")
            ) json.getJSONObject("counterfactual_decision").toString() else null,
            counterfactualContextJson = if (
                snapshotVersion >= 6 && !json.isNull("counterfactual_context")
            ) json.getJSONObject("counterfactual_context").toString() else null,
            recordedRuntimeBatches = if (snapshotVersion >= 7) json.getLong("recorded_runtime_batches") else 0L,
            lastRunPendingEvents = if (snapshotVersion >= 7) json.getInt("last_run_pending_events") else 0,
            lastRunBudgetExhausted = if (snapshotVersion >= 7) json.getBoolean("last_run_budget_exhausted") else false,
        )
        require(episode.id == record.episodeId && episode.id.matches(SAFE_ID))
        require(episode.actionId.matches(SAFE_ID))
        episode.counterfactualContextJson?.let { contextJson ->
            val context = AndroidJnvM250kDecisionEpisodeContext.fromJson(JSONObject(contextJson))
            require(episode.actionId == "decision-" + context.selectedCandidateId
                .replace(Regex("[^A-Za-z0-9._-]"), "-").take(52))
        }
        require(
            episode.observedLinks >= 0L && episode.omittedLinks >= 0L &&
                episode.unansweredTransmissions >= 0L && episode.unobservedTransmissions >= 0L &&
                episode.runtimeOmittedTransmissions >= 0L && episode.contestedTargetResponses >= 0L &&
                episode.ambiguousTargetResponses >= 0L && episode.nextOrder >= 0L &&
                episode.recordedRuntimeBatches >= 0L && episode.lastRunPendingEvents >= 0
        )
        val candidates = json.getJSONArray("candidates")
        require(candidates.length() <= maxTrackedConnections)
        for (index in 0 until candidates.length()) {
            val item = candidates.getJSONObject(index)
            val id = item.getString("id")
            val target = item.getInt("target")
            val count = item.getLong("count")
            val responses = item.getLong("responses")
            val credit = item.getDouble("response_credit")
            val order = item.getLong("order")
            require(
                id.matches(CONNECTION_ID) && count > 0L && responses >= 0L &&
                    credit.isFinite() && credit >= 0.0 && credit <= MAX_RESPONSE_CREDIT &&
                    order >= 0L
            )
            AndroidJnvM250kProfile.requireNeuronId(target)
            require(episode.candidates.put(
                id, Candidate(id, target, count, responses, credit, order),
            ) == null)
        }
        val pending = json.getJSONArray("pending")
        require(pending.length() <= maxTrackedConnections)
        for (index in 0 until pending.length()) {
            val item = pending.getJSONObject(index)
            val id = item.getString("id")
            val target = item.getInt("target")
            val arrival = item.getLong("arrival_ns")
            require(id.matches(CONNECTION_ID) && target in 0 until AndroidJnvM250kProfile.NEURON_CAPACITY)
            require(arrival >= 0L && episode.candidates.containsKey(id))
            require(episode.candidates.getValue(id).targetNeuronId == target)
            episode.pending += PendingTransmission(id, target, arrival)
        }
        val path = json.getJSONArray("path")
        require(path.length() <= maxTrackedConnections)
        for (index in 0 until path.length()) {
            val id = path.getString(index)
            require(id.matches(CONNECTION_ID))
            episode.path += id
        }
        if (record.state == "REWARD_PENDING") {
            require(record.reward != null && episode.pendingReward == record.reward)
            if (episode.counterfactualDecisionJson != null) {
                val decisionJson = JSONObject(episode.counterfactualDecisionJson!!)
                require(decisionJson.getString("reward_id") == "counterfactual-episode-${episode.id}")
                require(decisionJson.getDouble("reward") == record.reward)
                require(decodeCounterfactualDecision(decisionJson).mayApplyReward)
            }
        } else {
            require(episode.pendingReward == null)
        }
        return episode
    }

    /**
     * Older open episodes did not retain target IDs or response evidence, so
     * they are restored without reward-eligible candidates. A REWARD_PENDING
     * legacy episode must replay its original unweighted receipt unchanged.
     */
    private fun decodeLegacyEpisode(
        record: AndroidJnvM250kEpisodeJournal.Record,
        json: JSONObject,
    ): Episode {
        val episode = Episode(
            id = json.getString("episode_id"),
            actionId = json.getString("action_id"),
            observedLinks = json.getLong("observed_links"),
            omittedLinks = json.getLong("omitted_links"),
            nextOrder = json.getLong("next_order"),
            pendingReward = if (json.isNull("pending_reward")) null else json.getDouble("pending_reward"),
            legacyUnweightedReward = record.state == "REWARD_PENDING",
        )
        require(episode.id == record.episodeId && episode.id.matches(SAFE_ID))
        require(episode.actionId.matches(SAFE_ID))
        require(episode.observedLinks >= 0L && episode.omittedLinks >= 0L && episode.nextOrder >= 0L)
        val candidates = json.getJSONArray("candidates")
        if (record.state == "REWARD_PENDING") {
            require(record.reward != null && episode.pendingReward == record.reward)
            require(candidates.length() in 1..maxTrackedConnections)
            for (index in 0 until candidates.length()) {
                val item = candidates.getJSONObject(index)
                val id = item.getString("id")
                val count = item.getLong("count")
                val order = item.getLong("order")
                require(id.matches(CONNECTION_ID) && count > 0L && order >= 0L)
                require(episode.candidates.put(
                    id,
                    Candidate(id, 0, count, 1L, count.toDouble().coerceAtMost(MAX_RESPONSE_CREDIT), order),
                ) == null)
            }
        } else {
            require(record.state == "OPEN" && episode.pendingReward == null)
        }
        val path = json.getJSONArray("path")
        require(path.length() <= maxTrackedConnections)
        for (index in 0 until path.length()) {
            val id = path.getString(index)
            require(id.matches(CONNECTION_ID))
            episode.path += id
        }
        return episode
    }

    private fun saturatedAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    companion object {
        const val DEFAULT_MAX_CREDITED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        const val MAX_CREDITED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        const val DEFAULT_MAX_TRACKED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        const val MAX_TRACKED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        private const val MIN_SUPPORTED_SNAPSHOT_VERSION = 2
        private const val SNAPSHOT_VERSION = 7
        const val TARGET_RESPONSE_WINDOW_NS = 100_000_000L
        const val MAX_RESPONSE_CREDIT = 1_000_000.0
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val CONNECTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

        fun persistent(
            context: Context,
            applyReward: (rewardId: String, reward: Double, connectionIds: Set<String>) -> AndroidJnvM250kRewardResult,
            maxCreditedConnections: Int = DEFAULT_MAX_CREDITED_CONNECTIONS,
            maxTrackedConnections: Int = DEFAULT_MAX_TRACKED_CONNECTIONS,
            applyWeightedReward: ((rewardId: String, reward: Double, connectionCredits: Map<String, Double>) -> AndroidJnvM250kRewardResult)? = null,
        ): AndroidJnvM250kRewardAttribution = AndroidJnvM250kRewardAttribution(
            applyReward = applyReward,
            applyWeightedReward = applyWeightedReward,
            maxCreditedConnections = maxCreditedConnections,
            maxTrackedConnections = maxTrackedConnections,
            journal = AndroidJnvM250kEpisodeJournal(context),
        )
    }
}
