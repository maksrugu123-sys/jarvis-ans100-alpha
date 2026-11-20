package org.jarvis.ans100.alpha.data

/**
 * Result of converting one completed action episode into bounded R-STDP credit.
 *
 * Credited IDs are candidates selected from synapses that actually transmitted
 * during the episode. This is activity-based attribution, not a claim that the
 * selected neurons were the sole causes of the external outcome.
 */
data class AndroidJnvM250kAttributionResult(
    val episodeId: String,
    val actionId: String,
    val creditedNeuronIds: Set<Int>,
    val observedLinks: Long,
    val omittedLinks: Long,
    val rewardResult: AndroidJnvM250kRewardResult,
)

/**
 * Bounded episode-level bridge between observed sparse signal transmissions
 * and the existing idempotent R-STDP reward API.
 *
 * The caller must:
 * 1. begin an episode when an action/decision is committed;
 * 2. record every runtime result belonging to that episode;
 * 3. finish it once the external outcome is known.
 *
 * This coordinator is deliberately in-memory in this first version. It never
 * fabricates a causal path from population labels or mere neuron co-activity.
 * Only endpoints of recorded transmissions become candidate credited IDs.
 * The downstream store currently rewards all eligible synapses adjacent to
 * those IDs, not only the exact transmitted connection IDs.
 */
class AndroidJnvM250kRewardAttribution(
    private val applyReward: (rewardId: String, reward: Double, neuronIds: Set<Int>) -> AndroidJnvM250kRewardResult,
    private val maxCreditedNeurons: Int = DEFAULT_MAX_CREDITED_NEURONS,
) {
    private data class Candidate(
        val neuronId: Int,
        var transmissions: Long,
        val firstSeenOrder: Long,
    )

    private data class Episode(
        val id: String,
        val actionId: String,
        val candidates: LinkedHashMap<Int, Candidate> = LinkedHashMap(),
        var observedLinks: Long = 0L,
        var omittedLinks: Long = 0L,
        var nextOrder: Long = 0L,
    )

    private var activeEpisode: Episode? = null

    init {
        require(maxCreditedNeurons in 1..MAX_CREDITED_NEURONS) {
            "credited neuron bound must be 1..$MAX_CREDITED_NEURONS"
        }
    }

    @Synchronized
    fun beginEpisode(episodeId: String, actionId: String) {
        require(episodeId.matches(SAFE_ID)) { "episodeId must be a safe stable identifier" }
        require(actionId.matches(SAFE_ID)) { "actionId must be a safe stable identifier" }
        check(activeEpisode == null) { "finish or abort the active reward episode first" }
        activeEpisode = Episode(episodeId, actionId)
    }

    /**
     * Records direct transmissions emitted by this runtime run. The runtime
     * caps links per run; omitted counts are carried into the episode summary.
     */
    @Synchronized
    fun recordRun(result: AndroidJnvM250kRunResult) {
        val episode = activeEpisode ?: error("begin an episode before recording runtime activity")
        require(result.omittedCausalLinks >= 0)
        episode.omittedLinks = saturatedAdd(episode.omittedLinks, result.omittedCausalLinks.toLong())
        for (link in result.causalLinks) {
            episode.observedLinks = saturatedAdd(episode.observedLinks, 1L)
            addCandidate(episode, link.sourceNeuronId)
            addCandidate(episode, link.targetNeuronId)
        }
    }

    /**
     * Applies one outcome reward to the most frequently observed endpoints,
     * with first-seen order as a deterministic tie-breaker. On failure the
     * episode remains open so the caller can correct/retry the operation.
     */
    @Synchronized
    fun finishEpisode(reward: Double): AndroidJnvM250kAttributionResult {
        require(reward.isFinite() && reward in -1.0..1.0) {
            "episode reward must be finite and within [-1, 1]"
        }
        val episode = activeEpisode ?: error("there is no active reward episode")
        require(episode.candidates.isNotEmpty()) {
            "episode has no recorded synapse transmissions; no neurons can be credited"
        }

        val credited = episode.candidates.values
            .sortedWith(compareByDescending<Candidate> { it.transmissions }.thenBy { it.firstSeenOrder })
            .take(maxCreditedNeurons)
            .mapTo(LinkedHashSet()) { it.neuronId }

        val rewardResult = applyReward("episode-${episode.id}", reward, credited)
        val result = AndroidJnvM250kAttributionResult(
            episodeId = episode.id,
            actionId = episode.actionId,
            creditedNeuronIds = credited,
            observedLinks = episode.observedLinks,
            omittedLinks = episode.omittedLinks,
            rewardResult = rewardResult,
        )
        activeEpisode = null
        return result
    }

    @Synchronized
    fun abortEpisode() {
        check(activeEpisode != null) { "there is no active reward episode" }
        activeEpisode = null
    }

    @Synchronized
    fun hasActiveEpisode(): Boolean = activeEpisode != null

    private fun addCandidate(episode: Episode, neuronId: Int) {
        AndroidJnvM250kProfile.requireNeuronId(neuronId)
        val existing = episode.candidates[neuronId]
        if (existing != null) {
            existing.transmissions = saturatedAdd(existing.transmissions, 1L)
        } else {
            episode.candidates[neuronId] = Candidate(neuronId, 1L, episode.nextOrder++)
        }
    }

    private fun saturatedAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right

    companion object {
        const val DEFAULT_MAX_CREDITED_NEURONS = 32
        const val MAX_CREDITED_NEURONS = 32
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
    }
}
