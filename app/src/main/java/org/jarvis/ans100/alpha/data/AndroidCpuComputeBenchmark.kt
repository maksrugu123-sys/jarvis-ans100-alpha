package org.jarvis.ans100.alpha.data

import android.content.Context
import android.os.Build
import java.io.Closeable

enum class AndroidComputeMode {
    CPU,
    GPU,
    CPU_GPU,
}

data class AndroidComputeModeAvailability(
    val mode: AndroidComputeMode,
    val available: Boolean,
    val reason: String? = null,
)

data class AndroidComputeBenchmarkMeasurement(
    val mode: AndroidComputeMode,
    val samplesNs: List<Long>,
    val medianLatencyNs: Long,
    val operations: Int,
    val operationsPerSecond: Double,
)

data class AndroidComputeBenchmarkReport(
    val baseline: AndroidComputeBenchmarkMeasurement,
    val speedupRatio: Double,
    val fasterThanCpuPercent: Double,
    val deviceModel: String,
    val androidApiLevel: Int,
    val availability: List<AndroidComputeModeAvailability>,
)

/**
 * Runs a bounded CPU baseline against the real isolated Android ANS-100 runtime.
 *
 * Each warm-up/sample starts from a reset benchmark database. Reset and topology
 * initialization are setup, not timed inference. The timed region includes the
 * actual encoder, SQLite-backed neural event loop, readout and hypothesis update.
 *
 * This is intentionally a full-runtime CPU-only benchmark. The isolated LIF-DT
 * kernel benchmark is a separate measurement and must not be presented as
 * full-runtime GPU or hybrid acceleration. This class never falls back.
 */
class AndroidCpuComputeBenchmark(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private var closed = false

    @Synchronized
    fun run(
        inputText: String,
        warmupRuns: Int = DEFAULT_WARMUP_RUNS,
        measuredRuns: Int = DEFAULT_MEASURED_RUNS,
    ): AndroidComputeBenchmarkReport {
        check(!closed) { "benchmark adapter is closed" }
        require(inputText.isNotBlank() && inputText.length <= MAX_INPUT_CHARS) {
            "inputText must contain 1..$MAX_INPUT_CHARS characters"
        }
        require(warmupRuns in 0..MAX_WARMUP_RUNS) { "warmupRuns must be in 0..$MAX_WARMUP_RUNS" }
        require(measuredRuns in MIN_MEASURED_RUNS..MAX_MEASURED_RUNS) {
            "measuredRuns must be in $MIN_MEASURED_RUNS..$MAX_MEASURED_RUNS"
        }

        val runtime = ANS100StandaloneBenchmarkRuntime(appContext)
        try {
            var expected: CpuRunSignature? = null
            fun executeOnce(): Pair<CpuRunSignature, Long> {
                runtime.resetLearnedState()
                val result = runtime.runTask(
                    taskId = BENCHMARK_TASK_ID,
                    inputText = inputText,
                    trainingLabel = null,
                    coldStart = false,
                )
                check(result.completed) { "ANS-100 CPU benchmark did not drain its event queue" }
                check(result.processedEvents > 0) { "ANS-100 CPU benchmark processed no neural events" }
                check(result.inferenceDurationNs > 0L) { "ANS-100 CPU benchmark returned an invalid inference duration" }
                val signature = CpuRunSignature(
                    answer = result.answer,
                    readoutStatus = result.readoutStatus,
                    classSpikeCounts = result.classSpikeCounts.toSortedMap(),
                    encodedTokenCount = result.encodedTokenCount,
                    activatedTokenCount = result.activatedTokenCount,
                    slotCollisionCount = result.slotCollisionCount,
                    cleanSourceSpikeCount = result.cleanSourceSpikeCount,
                    processedEvents = result.processedEvents,
                    learnedSynapseCount = result.learnedSynapseCount,
                    neuralAssociationCandidateCount = result.neuralAssociationCandidateCount,
                    neuralHypothesisCount = result.neuralHypothesisCount,
                    reproducedNeuralHypothesisCount = result.reproducedNeuralHypothesisCount,
                    completed = result.completed,
                )
                val previous = expected
                if (previous == null) expected = signature
                else check(previous == signature) {
                    "ANS-100 CPU benchmark output changed between identical fresh-state runs"
                }
                return signature to result.inferenceDurationNs
            }

            repeat(warmupRuns) { executeOnce() }
            val samples = ArrayList<Long>(measuredRuns)
            var finalSignature: CpuRunSignature? = null
            repeat(measuredRuns) {
                val (signature, elapsed) = executeOnce()
                finalSignature = signature
                samples += elapsed
            }

            val operations = requireNotNull(finalSignature).processedEvents
            val median = medianOfSortedSamples(samples.sorted())
            val measurement = AndroidComputeBenchmarkMeasurement(
                mode = AndroidComputeMode.CPU,
                samplesNs = samples.toList(),
                medianLatencyNs = median,
                operations = operations,
                operationsPerSecond = operations.toDouble() * NANOS_PER_SECOND / median.toDouble(),
            )
            return AndroidComputeBenchmarkReport(
                baseline = measurement,
                speedupRatio = 1.0,
                fasterThanCpuPercent = 0.0,
                deviceModel = Build.MODEL ?: "unknown",
                androidApiLevel = Build.VERSION.SDK_INT,
                availability = listOf(
                    AndroidComputeModeAvailability(AndroidComputeMode.CPU, true),
                    AndroidComputeModeAvailability(
                        AndroidComputeMode.GPU,
                        false,
                        "The standalone full-ANS benchmark runtime has no GPU execution path; see the separate LIF-DT kernel benchmark.",
                    ),
                    AndroidComputeModeAvailability(
                        AndroidComputeMode.CPU_GPU,
                        false,
                        "The standalone full-ANS benchmark runtime has no hybrid execution path; see the separate LIF-DT kernel benchmark.",
                    ),
                ),
            )
        } finally {
            runtime.close()
        }
    }

    @Synchronized
    override fun close() {
        closed = true
    }

    private data class CpuRunSignature(
        val answer: String?,
        val readoutStatus: String,
        val classSpikeCounts: Map<String, Int>,
        val encodedTokenCount: Int,
        val activatedTokenCount: Int,
        val slotCollisionCount: Int,
        val cleanSourceSpikeCount: Int,
        val processedEvents: Int,
        val learnedSynapseCount: Int,
        val neuralAssociationCandidateCount: Int,
        val neuralHypothesisCount: Int,
        val reproducedNeuralHypothesisCount: Int,
        val completed: Boolean,
    )

    companion object {
        const val DEFAULT_WARMUP_RUNS = 1
        const val DEFAULT_MEASURED_RUNS = 5
        const val MAX_WARMUP_RUNS = 3
        const val MIN_MEASURED_RUNS = 3
        const val MAX_MEASURED_RUNS = 9
        const val MAX_INPUT_CHARS = 2_048
        private const val BENCHMARK_TASK_ID = "compute-benchmark-cpu"
        private const val NANOS_PER_SECOND = 1_000_000_000.0

        internal fun medianOfSortedSamples(sortedSamples: List<Long>): Long {
            require(sortedSamples.isNotEmpty() && sortedSamples.all { it > 0L })
            require(sortedSamples.zipWithNext().all { (left, right) -> left <= right })
            val middle = sortedSamples.size / 2
            return if (sortedSamples.size % 2 == 1) {
                sortedSamples[middle]
            } else {
                val lower = sortedSamples[middle - 1]
                val upper = sortedSamples[middle]
                lower + (upper - lower) / 2L
            }
        }
    }
}
