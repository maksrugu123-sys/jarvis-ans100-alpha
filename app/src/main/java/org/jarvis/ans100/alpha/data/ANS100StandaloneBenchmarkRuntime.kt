package org.jarvis.ans100.alpha.data

import android.content.Context
import java.io.Closeable
import java.util.UUID

/**
 * Isolated local ANS-100 benchmark runtime with its own SQLite topology.
 * It never reads or writes the production dialogue network.
 */
class ANS100StandaloneBenchmarkRuntime(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var lastNetworkInitializationNs = 0L
    private var activeStructuredInput = false
    private var network = openNetwork(activeStructuredInput)
    private val inputAdapter = ANS100BenchmarkNeuralInputAdapter()
    private val structuredInputAdapter = ANS100StructuredBenchmarkInputAdapter()
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
        val networkInitializationDurationNs: Long,
        val learnedSynapseCount: Int,
        val trainingApplied: Boolean,
        val completed: Boolean,
        val memoryBaselinePssMb: Double? = null,
        val peakMemoryPssMb: Double? = null,
        val peakMemoryDeltaMb: Double? = null,
        val memorySampleIntervalMs: Long? = null,
        val coldStart: Boolean = false,
        val inputEncoding: String = "lexical_hash",
        val parsedFactCount: Int? = null,
    )

    /**
     * Supply a canonical label only for development/training examples.
     * Held-out evaluation examples must call this with trainingLabel=null.
     */
    @Synchronized
    fun runTask(taskId: String, inputText: String, trainingLabel: String? = null, coldStart: Boolean = false): TaskResult =
        runMeasuredTask(taskId, inputText, trainingLabel, coldStart, structuredInput = false)

    /** Runs the same isolated network/readout using the role-structured B1 encoder. */
    @Synchronized
    fun runStructuredTask(
        taskId: String,
        inputText: String,
        trainingLabel: String? = null,
        coldStart: Boolean = false,
    ): TaskResult = runMeasuredTask(taskId, inputText, trainingLabel, coldStart, structuredInput = true)

    private fun runMeasuredTask(
        taskId: String,
        inputText: String,
        trainingLabel: String?,
        coldStart: Boolean,
        structuredInput: Boolean,
    ): TaskResult {
        val reopenNetwork = coldStart || activeStructuredInput != structuredInput
        if (reopenNetwork) network.close()
        activeStructuredInput = structuredInput
        val measured = ProcessMemoryPeakSampler.measure {
            if (reopenNetwork) network = openNetwork(activeStructuredInput)
            runTaskInternal(taskId, inputText, trainingLabel, structuredInput)
        }
        return measured.value.copy(
            memoryBaselinePssMb = measured.baselinePssMb,
            peakMemoryPssMb = measured.peakPssMb,
            peakMemoryDeltaMb = measured.peakDeltaMb,
            memorySampleIntervalMs = measured.sampleIntervalMs,
            coldStart = coldStart,
        )
    }

    private fun runTaskInternal(
        taskId: String,
        inputText: String,
        trainingLabel: String?,
        structuredInput: Boolean,
    ): TaskResult {
        require(taskId.matches(Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}$")))
        val label = trainingLabel?.let {
            require(taskId.startsWith("development:")) {
                "supervised ANS-100 benchmark labels are accepted only for development tasks"
            }
            ANS100CanonicalReadoutContract.Answer.valueOf(it.trim().uppercase())
        }
        val startedAtNs = System.nanoTime()
        val currentTime = network.stats().currentTimeNs
        if (currentTime > Long.MAX_VALUE - MINIMUM_TURN_STEP_NS) {
            throw AndroidNeuronNetworkException("benchmark model clock reached its time limit")
        }
        val timestampNs = maxOf(System.nanoTime(), currentTime + MINIMUM_TURN_STEP_NS)
        val turnId = "benchmark-${taskId.take(40)}-${UUID.randomUUID()}"
        val encodedBatch: AndroidHybridEvidenceInputAdapter.ActivationBatch
        val encodedFeatureCount: Int
        val activatedFeatureCount: Int
        val slotCollisionCount: Int
        val parsedFactCount: Int?
        if (structuredInput) {
            val encoded = structuredInputAdapter.createBatch(turnId, inputText, timestampNs)
            encodedBatch = encoded.batch
            encodedFeatureCount = encoded.encodedFeatureCount
            activatedFeatureCount = encoded.activatedFeatureCount
            slotCollisionCount = encoded.slotCollisionCount
            parsedFactCount = encoded.parsedFactCount
        } else {
            val encoded = inputAdapter.createBatch(turnId, inputText, timestampNs)
            encodedBatch = encoded.batch
            encodedFeatureCount = encoded.encodedTokenCount
            activatedFeatureCount = encoded.activatedTokenCount
            slotCollisionCount = encoded.slotCollisionCount
            parsedFactCount = null
        }
        if (encodedBatch.events.isEmpty()) {
            throw AndroidNeuronNetworkException("benchmark input produced no neural input pulses")
        }
        if (!network.beginHybridTurn(encodedBatch)) {
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

        val readout = if (completed) decoder.decode(spikes, tainted)
            else decoder.decode(emptySet(), tainted)
        return TaskResult(
            taskId = taskId,
            answer = readout.answer?.wireValue,
            readoutStatus = readout.status.name,
            classSpikeCounts = readout.spikeCounts.mapKeys { it.key.wireValue },
            encodedTokenCount = encodedFeatureCount,
            activatedTokenCount = activatedFeatureCount,
            slotCollisionCount = slotCollisionCount,
            cleanSourceSpikeCount = cleanSourceSpikes.size,
            processedEvents = processed,
            inferenceDurationNs = (System.nanoTime() - startedAtNs).coerceAtLeast(0L),
            networkInitializationDurationNs = lastNetworkInitializationNs,
            learnedSynapseCount = network.canonicalReadoutSynapseCount(),
            trainingApplied = trainingApplied,
            completed = completed,
            inputEncoding = if (structuredInput) "role_structured_v1" else "lexical_hash",
            parsedFactCount = parsedFactCount,
        )
    }

    /** Close and recreate the isolated database before a fresh benchmark run. */
    @Synchronized
    fun resetLearnedState() = resetDatabase(structuredInput = false)

    /** Clears only the structured-input experiment; lexical baseline weights remain isolated. */
    @Synchronized
    fun resetStructuredLearnedState() = resetDatabase(structuredInput = true)

    private fun resetDatabase(structuredInput: Boolean) {
        network.close()
        activeStructuredInput = structuredInput
        if (!appContext.deleteDatabase(databaseName(structuredInput))) {
            throw AndroidNeuronNetworkException("unable to reset isolated ANS-100 benchmark database")
        }
        network = openNetwork(structuredInput)
    }

    private fun databaseName(structuredInput: Boolean): String =
        if (structuredInput) STRUCTURED_BENCHMARK_DATABASE_NAME
        else AndroidHybridTopologyV2.BENCHMARK_DATABASE_NAME

    private fun openNetwork(structuredInput: Boolean): AndroidSparseNeuronNetwork {
        val startedAtNs = System.nanoTime()
        return try {
            AndroidHybridTopologyV2.open(
                appContext,
                databaseName(structuredInput),
            )
        } finally {
            lastNetworkInitializationNs = (System.nanoTime() - startedAtNs).coerceAtLeast(0L)
        }
    }

    override fun close() = network.close()

    companion object {
        const val STRUCTURED_BENCHMARK_DATABASE_NAME = "jarvis_ans100_structured_benchmark_v1.db"
        private const val MINIMUM_TURN_STEP_NS = 1_000_000L
        private const val MAX_RUN_PASSES = 32
    }
}
