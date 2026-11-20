package org.jarvis.ans100.alpha.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable

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
 * Bounded episode bridge from runtime transmissions to exact synapse-level
 * R-STDP. When supplied with a journal, its active episode survives process
 * death. An emitted edge is transmission evidence, not proof of outcome causality.
 */
class AndroidJnvM250kRewardAttribution(
    private val applyReward: (rewardId: String, reward: Double, connectionIds: Set<String>) -> AndroidJnvM250kRewardResult,
    private val maxCreditedConnections: Int = DEFAULT_MAX_CREDITED_CONNECTIONS,
    private val maxTrackedConnections: Int = DEFAULT_MAX_TRACKED_CONNECTIONS,
    private val journal: AndroidJnvM250kEpisodeJournal? = null,
) : Closeable {
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

    /** Records ordered direct transmissions and durably checkpoints the bounded sketch. */
    @Synchronized
    fun recordRun(result: AndroidJnvM250kRunResult) {
        val episode = activeEpisode ?: error("begin or recover an episode before recording runtime activity")
        check(episode.pendingReward == null) { "episode reward is pending; retry finishEpisode instead of recording more activity" }
        require(result.omittedCausalLinks >= 0)
        episode.omittedLinks = saturatedAdd(episode.omittedLinks, result.omittedCausalLinks.toLong())
        for (link in result.causalLinks) {
            episode.observedLinks = saturatedAdd(episode.observedLinks, 1L)
            addCandidate(episode, link.connectionId)
            if (episode.path.size < maxTrackedConnections) episode.path += link.connectionId
            else episode.omittedLinks = saturatedAdd(episode.omittedLinks, 1L)
        }
        journal?.saveOpen(episode.id, encodeEpisode(episode))
    }

    /**
     * Persists REWARD_PENDING before touching synapses. If the process dies
     * after reward commit but before journal completion, replay is safe because
     * the JNVM reward receipt is idempotent.
     */
    @Synchronized
    fun finishEpisode(reward: Double): AndroidJnvM250kAttributionResult {
        require(reward.isFinite() && reward in -1.0..1.0)
        val episode = activeEpisode ?: error("there is no active reward episode")
        require(episode.candidates.isNotEmpty()) {
            "episode has no recorded synapse transmissions; no connections can be credited"
        }
        val pending = episode.pendingReward
        if (pending != null && pending != reward) {
            throw IllegalArgumentException("recovered episode has a different persisted reward; retry with the original value")
        }
        val credited = episode.candidates.values
            .sortedWith(compareByDescending<Candidate> { it.estimatedTransmissions }.thenBy { it.firstSeenOrder })
            .take(maxCreditedConnections)
            .mapTo(LinkedHashSet()) { it.connectionId }
        if (pending == null) {
            episode.pendingReward = reward
            journal?.prepareReward(episode.id, encodeEpisode(episode), reward)
        }
        val rewardResult = applyReward("episode-${episode.id}", reward, credited)
        journal?.complete(episode.id)
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

    private fun encodeEpisode(episode: Episode): String {
        val candidates = JSONArray()
        episode.candidates.values.forEach { candidate ->
            candidates.put(JSONObject().apply {
                put("id", candidate.connectionId)
                put("count", candidate.estimatedTransmissions)
                put("order", candidate.firstSeenOrder)
            })
        }
        val path = JSONArray()
        episode.path.forEach(path::put)
        return JSONObject().apply {
            put("episode_id", episode.id)
            put("action_id", episode.actionId)
            put("candidates", candidates)
            put("path", path)
            put("observed_links", episode.observedLinks)
            put("omitted_links", episode.omittedLinks)
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
            nextOrder = json.getLong("next_order"),
            pendingReward = if (json.isNull("pending_reward")) null else json.getDouble("pending_reward"),
        )
        require(episode.id == record.episodeId && episode.id.matches(SAFE_ID))
        require(episode.actionId.matches(SAFE_ID))
        require(episode.observedLinks >= 0L && episode.omittedLinks >= 0L && episode.nextOrder >= 0L)
        val candidates = json.getJSONArray("candidates")
        require(candidates.length() <= maxTrackedConnections)
        for (index in 0 until candidates.length()) {
            val item = candidates.getJSONObject(index)
            val id = item.getString("id")
            val count = item.getLong("count")
            val order = item.getLong("order")
            require(id.matches(CONNECTION_ID) && count > 0L && order >= 0L)
            require(episode.candidates.put(id, Candidate(id, count, order)) == null)
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
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private val CONNECTION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

        fun persistent(
            context: Context,
            applyReward: (rewardId: String, reward: Double, connectionIds: Set<String>) -> AndroidJnvM250kRewardResult,
            maxCreditedConnections: Int = DEFAULT_MAX_CREDITED_CONNECTIONS,
            maxTrackedConnections: Int = DEFAULT_MAX_TRACKED_CONNECTIONS,
        ): AndroidJnvM250kRewardAttribution = AndroidJnvM250kRewardAttribution(
            applyReward = applyReward,
            maxCreditedConnections = maxCreditedConnections,
            maxTrackedConnections = maxTrackedConnections,
            journal = AndroidJnvM250kEpisodeJournal(context),
        )
    }
}
