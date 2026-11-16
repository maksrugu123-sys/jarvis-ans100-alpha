package org.jarvis.ans100.alpha.data

import android.content.Context
import java.io.Closeable
import java.util.UUID

/**
 * Isolated local ANS-100 benchmark runtime with its own SQLite topology.
 * It never reads or writes the production dialogue network.
 */
class ANS100StandaloneBenchmarkRuntime(context: Context) : Closeable {
    private val network = AndroidHybridTopologyV2.open(
        context.applicationContext,
        AndroidHybridTopologyV2.BENCHMARK_DATABASE_NAME,
    )
    private val inputAdapter = ANS100BenchmarkNeuralInputAdapter()
    private val decoder = ANS100CanonicalReadoutDecoder()

    data class TaskResult(
        val taskId: String,
        val answer: String?,
        val readoutStatus: String,
        val classSpikeCounts: Map<String, Int>,
        val encodedTokenCount: Int,
        val activatedTokenCount: Int,
        val slotCollisionCount: Int,
        val cleanSourceSpikeCount: Int,
        val processedEvents: Int,
        val inferenceDurationNs: Long,
        val learnedSynapseCount: Int,
        val trainingApplied: Boolean,
        val completed: Boolean,
    )

    /**
     * Supply a canonical label only for development/training examples.
     * Held-out evaluation examples must call this with trainingLabel=null.
     */
    @Synchronized
    fun runTask(taskId: String, inputText: String, trainingLabel: String? = null): TaskResult {
        require(taskId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}$")))
        val label = trainingLabel?.let {
            ANS100CanonicalReadoutContract.Answer.valueOf(it.trim().uppercase())
        }
        val startedAtNs = System.nanoTime()
        val currentTime = network.stats().currentTimeNs
        if (currentTime > Long.MAX_VALUE - MINIMUM_TURN_STEP_NS) {
            throw AndroidNeuronNetworkException("benchmark model clock reached its time limit")
        }
        val timestampNs = maxOf(System.nanoTime(), currentTime + MINIMUM_TURN_STEP_NS)
        val turnId = "benchmark-${taskId.take(40)}-${UUID.randomUUID()}"
        val encoded = inputAdapter.createBatch(turnId, inputText, timestampNs)
        if (encoded.batch.events.isEmpty()) {
            throw AndroidNeuronNetworkException("benchmark input produced no lexical concept pulses")
        }
        if (!network.beginHybridTurn(encoded.batch)) {
            throw AndroidNeuronNetworkException("benchmark network refused the isolated input turn")
        }

        val spikes = LinkedHashSet<Int>()
        val tainted = LinkedHashSet<Int>()
        var processed = 0
        var pending: Int
        var passes = 0
        do {
            val pass = network.run()
            processed += pass.processedEvents
            spikes.addAll(pass.emittedSpikes.map { it.first })
            tainted.addAll(pass.coreTaintedNeuronIds)
            pending = pass.pendingEvents
            passes++
        } while (pending > 0 && passes < MAX_RUN_PASSES)

        val completed = pending == 0
        val cleanSourceSpikes = spikes
            .asSequence()
            .filterNot(tainted::contains)
            .filter(ANS100CanonicalReadoutContract::isEligibleSourceNeuron)
            .distinct()
            .sorted()
            .take(ANS100CanonicalReadoutContract.MAX_SOURCE_NEURONS_PER_TURN)
            .toList()

        var trainingApplied = false
        if (label != null && completed && cleanSourceSpikes.isNotEmpty()) {
            val eligible = network.recordCanonicalReadoutEligibility(turnId, cleanSourceSpikes)
            if (eligible > 0) {
                val changed = network.applyCanonicalReadoutBenchmarkFeedback(
                    feedbackId = "benchmark-feedback-${UUID.randomUUID()}",
                    turnId = turnId,
                    answer = label,
                )
                trainingApplied = changed > 0
            }
        }

        val readout = decoder.decode(spikes, tainted)
        return TaskResult(
            taskId = taskId,
            answer = readout.answer?.wireValue,
            readoutStatus = readout.status.name,
            classSpikeCounts = readout.spikeCounts.mapKeys { it.key.wireValue },
            encodedTokenCount = encoded.encodedTokenCount,
            activatedTokenCount = encoded.activatedTokenCount,
            slotCollisionCount = encoded.slotCollisionCount,
            cleanSourceSpikeCount = cleanSourceSpikes.size,
            processedEvents = processed,
            inferenceDurationNs = (System.nanoTime() - startedAtNs).coerceAtLeast(0L),
            learnedSynapseCount = network.canonicalReadoutSynapseCount(),
            trainingApplied = trainingApplied,
            completed = completed,
        )
    }

    override fun close() = network.close()

    companion object {
        private const val MINIMUM_TURN_STEP_NS = 1_000_000L
        private const val MAX_RUN_PASSES = 32
    }
}
