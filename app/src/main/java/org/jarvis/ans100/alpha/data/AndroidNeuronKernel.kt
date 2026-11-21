package org.jarvis.ans100.alpha.data

import kotlin.math.exp

/**
 * Pure deterministic LIF-DT candidate calculation.
 *
 * No database, cache, event queue, clock mutation, or side effects live here.
 * This is the CPU reference implementation and the parity contract for future
 * GPU kernels. The caller remains responsible for atomic persistence.
 */
object AndroidNeuronKernel {
    data class Input(
        val state: AndroidNeuronState,
        val timestampNs: Long,
        val input: Double,
    )

    data class Result(
        val state: AndroidNeuronState,
        val spiked: Boolean,
    )

    fun process(
        state: AndroidNeuronState,
        timestampNs: Long,
        input: Double,
    ): Result = processOne(Input(state, timestampNs, input))

    fun processBatch(inputs: List<Input>): List<Result> = inputs.map(::processOne)

    fun validateCandidate(input: Input, result: Result) {
        val previous = input.state
        val candidate = result.state
        if (candidate.neuronId != previous.neuronId ||
            candidate.lastUpdateNs != input.timestampNs
        ) {
            throw AndroidNeuronNetworkException("compute backend returned a mismatched neuron candidate")
        }
        if (result.spiked) {
            if (previous.spikeSequence == Long.MAX_VALUE ||
                input.timestampNs > Long.MAX_VALUE - REFRACTORY_NS ||
                candidate.spikeSequence != previous.spikeSequence + 1L ||
                candidate.potential != RESET_POTENTIAL ||
                candidate.refractoryUntilNs != input.timestampNs + REFRACTORY_NS
            ) {
                throw AndroidNeuronNetworkException("compute backend returned an invalid spike transition")
            }
        } else if (candidate.spikeSequence != previous.spikeSequence ||
            candidate.refractoryUntilNs != previous.refractoryUntilNs
        ) {
            throw AndroidNeuronNetworkException("compute backend changed spike metadata without a spike")
        }
    }

    private fun processOne(input: Input): Result {
        val state = input.state
        val timestampNs = input.timestampNs
        val inputStrength = input.input
        require(inputStrength.isFinite()) { "neuron input must be finite" }
        if (timestampNs < state.lastUpdateNs) {
            throw AndroidNeuronNetworkException("neuron event time moved backwards")
        }

        val elapsed = timestampNs - state.lastUpdateNs
        val decayedPotential = state.potential * exp(-elapsed.toDouble() / TAU_MEMBRANE_NS)
        val decayedAdaptation = state.adaptation * exp(-elapsed.toDouble() / TAU_ADAPTATION_NS)
        val potential = (decayedPotential + inputStrength).coerceIn(POTENTIAL_MIN, POTENTIAL_MAX)
        val threshold = (BASE_THRESHOLD + ADAPTATION_GAIN * decayedAdaptation)
            .coerceIn(BASE_THRESHOLD, MAX_THRESHOLD)
        val spiked = timestampNs >= state.refractoryUntilNs && potential >= threshold

        if (!spiked) {
            return Result(
                state = AndroidNeuronState(
                    neuronId = state.neuronId,
                    potential = potential,
                    adaptation = decayedAdaptation.coerceIn(0.0, 1.0),
                    lastUpdateNs = timestampNs,
                    refractoryUntilNs = state.refractoryUntilNs,
                    spikeSequence = state.spikeSequence,
                ),
                spiked = false,
            )
        }

        if (state.spikeSequence == Long.MAX_VALUE ||
            timestampNs > Long.MAX_VALUE - REFRACTORY_NS
        ) {
            throw AndroidNeuronNetworkException("neuron spike counter or refractory clock would overflow")
        }

        return Result(
            state = AndroidNeuronState(
                neuronId = state.neuronId,
                potential = RESET_POTENTIAL,
                adaptation = (decayedAdaptation + ADAPTATION_PER_SPIKE).coerceIn(0.0, 1.0),
                lastUpdateNs = timestampNs,
                refractoryUntilNs = timestampNs + REFRACTORY_NS,
                spikeSequence = state.spikeSequence + 1L,
            ),
            spiked = true,
        )
    }

    private const val POTENTIAL_MIN = -2.0
    private const val POTENTIAL_MAX = 2.0
    private const val RESET_POTENTIAL = 0.0
    private const val BASE_THRESHOLD = 1.0
    private const val MAX_THRESHOLD = 2.0
    private const val ADAPTATION_GAIN = 0.5
    private const val ADAPTATION_PER_SPIKE = 0.15
    private const val TAU_MEMBRANE_NS = 20_000_000L
    private const val TAU_ADAPTATION_NS = 100_000_000L
    private const val REFRACTORY_NS = 2_000_000L
}


/**
 * Batch boundary for CPU/GPU implementations. A backend receives the exact
 * timestamp-aggregated candidates and must return results in identical order.
 */
interface AndroidNeuronComputeBackend {
    val backendName: String
    fun processBatch(inputs: List<AndroidNeuronKernel.Input>): List<AndroidNeuronKernel.Result>
}

object CpuAndroidNeuronComputeBackend : AndroidNeuronComputeBackend {
    override val backendName: String = "cpu-reference"

    override fun processBatch(
        inputs: List<AndroidNeuronKernel.Input>,
    ): List<AndroidNeuronKernel.Result> = AndroidNeuronKernel.processBatch(inputs)
}
