package org.jarvis.ans100.alpha.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

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
    private val applyWeightedReward: ((rewardId: String, reward: Double, connectionCredits: Map<String, Double>) -> AndroidJnvM250kRewardResult)? = null,
    private val maxCreditedConnections: Int = DEFAULT_MAX_CREDITED_CONNECTIONS,
    private val maxTrackedConnections: Int = DEFAULT_MAX_TRACKED_CONNECTIONS,
    private val journal: AndroidJnvM250kEpisodeJournal? = null,
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
        var contestedTargetResponses: Long = 0L,
        var nextOrder: Long = 0L,
        var pendingReward: Double? = null,
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
     * Adds new transmissions, correlates them with later target spikes, then
     * expires response windows that have elapsed. All retained state is bounded.
     */
    @Synchronized
    fun recordRun(result: AndroidJnvM250kRunResult) {
        val episode = activeEpisode ?: error("begin or recover an episode before recording runtime activity")
        check(episode.pendingReward == null) { "episode reward is pending; retry finishEpisode instead of recording more activity" }
        require(result.omittedCausalLinks >= 0)
        episode.omittedLinks = saturatedAdd(episode.omittedLinks, result.omittedCausalLinks.toLong())

        data class TimelineEvent(
            val timestampNs: Long,
            val link: AndroidJnvM250kCausalLink? = null,
            val spike: AndroidJnvM250kSpike? = null,
        )
        val timeline = ArrayList<TimelineEvent>(result.causalLinks.size + result.emittedSpikes.size)
        result.causalLinks.forEach { timeline += TimelineEvent(it.arrivalTimestampNs, link = it) }
        result.emittedSpikes.forEach { timeline += TimelineEvent(it.timestampNs, spike = it) }
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
    }

    /**
     * Persists REWARD_PENDING before touching synapses. Only connections with
     * a temporally matched target response can receive the scalar episode reward.
     */
    @Synchronized
    fun finishEpisode(reward: Double): AndroidJnvM250kAttributionResult {
        require(reward.isFinite() && reward in -1.0..1.0)
        val episode = activeEpisode ?: error("there is no active reward episode")
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
        val scores = selected.associate { candidate ->
            candidate.connectionId to
                (candidate.responseCredit / candidate.responseCount.toDouble()).coerceIn(0.0, 1.0)
        }

        if (pending == null) {
            episode.pendingReward = reward
            journal?.prepareReward(episode.id, encodeEpisode(episode), reward)
        }
        val rewardId = "episode-${episode.id}"
        val rewardResult = applyWeightedReward?.invoke(rewardId, reward, scores)
            ?: applyReward(rewardId, reward, credited)
        journal?.complete(episode.id)
        val result = AndroidJnvM250kAttributionResult(
            episodeId = episode.id,
            actionId = episode.actionId,
            creditedConnectionIds = credited,
            signalPathConnectionIds = episode.path.toList(),
            observedLinks = episode.observedLinks,
            omittedLinks = episode.omittedLinks,
            responseScores = scores,
            unansweredTransmissions = episode.unansweredTransmissions + episode.pending.size,
            contestedTargetResponses = episode.contestedTargetResponses,
            rewardResult = rewardResult,
        )
        activeEpisode = null
        return result
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
    fun trackedConnectionCount(): Int = activeEpisode?.candidates?.size ?: 0

    @Synchronized
    override fun close() {
        journal?.close()
    }

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
            put("episode_id", episode.id)
            put("action_id", episode.actionId)
            put("candidates", candidates)
            put("pending", pending)
            put("path", path)
            put("observed_links", episode.observedLinks)
            put("omitted_links", episode.omittedLinks)
            put("unanswered_transmissions", episode.unansweredTransmissions)
            put("contested_responses", episode.contestedTargetResponses)
            put("next_order", episode.nextOrder)
            if (episode.pendingReward == null) put("pending_reward", JSONObject.NULL)
            else put("pending_reward", episode.pendingReward!!)
        }.toString().also {
            require(it.length <= AndroidJnvM250kEpisodeJournal.MAX_PAYLOAD_CHARS) {
                "episode snapshot exceeds the durable journal bound"
            }
        }
    }

    private fun decodeEpisode(record: AndroidJnvM250kEpisodeJournal.Record): Episode {
        val json = JSONObject(record.payload)
        val episode = Episode(
            id = json.getString("episode_id"),
            actionId = json.getString("action_id"),
            observedLinks = json.getLong("observed_links"),
            omittedLinks = json.getLong("omitted_links"),
            unansweredTransmissions = json.getLong("unanswered_transmissions"),
            contestedTargetResponses = json.getLong("contested_responses"),
            nextOrder = json.getLong("next_order"),
            pendingReward = if (json.isNull("pending_reward")) null else json.getDouble("pending_reward"),
        )
        require(episode.id == record.episodeId && episode.id.matches(SAFE_ID))
        require(episode.actionId.matches(SAFE_ID))
        require(
            episode.observedLinks >= 0L && episode.omittedLinks >= 0L &&
                episode.unansweredTransmissions >= 0L &&
                episode.contestedTargetResponses >= 0L && episode.nextOrder >= 0L
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
        } else {
            require(episode.pendingReward == null)
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
        const val TARGET_RESPONSE_WINDOW_NS = 100_000_000L
        const val MAX_RESPONSE_CREDIT = 1_000_000.0
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val CONNECTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

        fun persistent(
            context: Context,
            applyReward: (rewardId: String, reward: Double, connectionIds: Set<String>) -> AndroidJnvM250kRewardResult,
            maxCreditedConnections: Int = DEFAULT_MAX_CREDITED_CONNECTIONS,
            applyWeightedReward: ((rewardId: String, reward: Double, connectionCredits: Map<String, Double>) -> AndroidJnvM250kRewardResult)? = null,
            maxTrackedConnections: Int = DEFAULT_MAX_TRACKED_CONNECTIONS,
        ): AndroidJnvM250kRewardAttribution = AndroidJnvM250kRewardAttribution(
            applyReward = applyReward,
            applyWeightedReward = applyWeightedReward,
            maxCreditedConnections = maxCreditedConnections,
            maxTrackedConnections = maxTrackedConnections,
            journal = AndroidJnvM250kEpisodeJournal(context),
        )
    }
}
