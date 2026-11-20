package org.jarvis.ans100.alpha.data

/** Result of applying an episode outcome to exact synapses observed transmitting. */
data class AndroidJnvM250kAttributionResult(
    val episodeId: String,
    val actionId: String,
    val creditedConnectionIds: Set<String>,
    val signalPathConnectionIds: List<String>,
    val observedLinks: Long,
    val omittedLinks: Long,
    val rewardResult: AndroidJnvM250kRewardResult,
)

/**
 * Bounded episode-level bridge from actual runtime transmissions to exact
 * synapse-level R-STDP credit. It records emitted connection IDs, not merely
 * endpoint neurons. An emitted edge is evidence of transmission, not proof
 * that the edge alone caused the external outcome or that its target spiked.
 */
class AndroidJnvM250kRewardAttribution(
    private val applyReward: (rewardId: String, reward: Double, connectionIds: Set<String>) -> AndroidJnvM250kRewardResult,
    private val maxCreditedConnections: Int = DEFAULT_MAX_CREDITED_CONNECTIONS,
    private val maxTrackedConnections: Int = DEFAULT_MAX_TRACKED_CONNECTIONS,
) {
    private data class Candidate(
        val connectionId: String,
        var estimatedTransmissions: Long,
        val firstSeenOrder: Long,
    )
    private data class Episode(
        val id: String,
        val actionId: String,
        val candidates: LinkedHashMap<String, Candidate> = LinkedHashMap(),
        val path: ArrayList<String> = ArrayList(),
        var observedLinks: Long = 0L,
        var omittedLinks: Long = 0L,
        var nextOrder: Long = 0L,
    )
    private var activeEpisode: Episode? = null

    init {
        require(maxCreditedConnections in 1..MAX_CREDITED_CONNECTIONS)
        require(maxTrackedConnections in maxCreditedConnections..MAX_TRACKED_CONNECTIONS)
    }

    @Synchronized
    fun beginEpisode(episodeId: String, actionId: String) {
        require(episodeId.matches(SAFE_ID)) { "episodeId must be a safe stable identifier" }
        require(actionId.matches(SAFE_ID)) { "actionId must be a safe stable identifier" }
        check(activeEpisode == null) { "finish or abort the active reward episode first" }
        activeEpisode = Episode(episodeId, actionId)
    }

    /** Records ordered direct transmissions reported by one runtime run. */
    @Synchronized
    fun recordRun(result: AndroidJnvM250kRunResult) {
        val episode = activeEpisode ?: error("begin an episode before recording runtime activity")
        require(result.omittedCausalLinks >= 0)
        episode.omittedLinks = saturatedAdd(episode.omittedLinks, result.omittedCausalLinks.toLong())
        for (link in result.causalLinks) {
            episode.observedLinks = saturatedAdd(episode.observedLinks, 1L)
            addCandidate(episode, link.connectionId)
            if (episode.path.size < maxTrackedConnections) episode.path += link.connectionId
            else episode.omittedLinks = saturatedAdd(episode.omittedLinks, 1L)
        }
    }

    /** Applies outcome only to observed connection IDs; failure leaves episode retryable. */
    @Synchronized
    fun finishEpisode(reward: Double): AndroidJnvM250kAttributionResult {
        require(reward.isFinite() && reward in -1.0..1.0)
        val episode = activeEpisode ?: error("there is no active reward episode")
        require(episode.candidates.isNotEmpty()) {
            "episode has no recorded synapse transmissions; no connections can be credited"
        }
        val credited = episode.candidates.values
            .sortedWith(compareByDescending<Candidate> { it.estimatedTransmissions }.thenBy { it.firstSeenOrder })
            .take(maxCreditedConnections)
            .mapTo(LinkedHashSet()) { it.connectionId }
        val rewardResult = applyReward("episode-${episode.id}", reward, credited)
        val result = AndroidJnvM250kAttributionResult(
            episodeId = episode.id,
            actionId = episode.actionId,
            creditedConnectionIds = credited,
            signalPathConnectionIds = episode.path.toList(),
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
    @Synchronized
    fun trackedConnectionCount(): Int = activeEpisode?.candidates?.size ?: 0

    private fun addCandidate(episode: Episode, connectionId: String) {
        require(connectionId.matches(CONNECTION_ID)) { "unsafe connection ID in runtime transmission" }
        val current = episode.candidates[connectionId]
        if (current != null) {
            current.estimatedTransmissions = saturatedAdd(current.estimatedTransmissions, 1L)
            return
        }
        if (episode.candidates.size < maxTrackedConnections) {
            episode.candidates[connectionId] = Candidate(connectionId, 1L, episode.nextOrder++)
            return
        }
        val least = episode.candidates.values.minWithOrNull(
            compareBy<Candidate> { it.estimatedTransmissions }.thenBy { it.firstSeenOrder },
        ) ?: return
        episode.candidates.remove(least.connectionId)
        episode.candidates[connectionId] = Candidate(
            connectionId, saturatedAdd(least.estimatedTransmissions, 1L), episode.nextOrder++,
        )
    }

    private fun saturatedAdd(a: Long, b: Long): Long =
        if (Long.MAX_VALUE - a < b) Long.MAX_VALUE else a + b

    companion object {
        const val DEFAULT_MAX_CREDITED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        const val MAX_CREDITED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        const val DEFAULT_MAX_TRACKED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        const val MAX_TRACKED_CONNECTIONS = AndroidJnvM250kStore.MAX_REWARDED_SYNAPSES
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val CONNECTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
    }
}
