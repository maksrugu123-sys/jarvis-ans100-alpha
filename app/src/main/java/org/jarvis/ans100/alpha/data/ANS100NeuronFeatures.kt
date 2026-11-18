package org.jarvis.ans100.alpha.data

import kotlin.math.abs

/**
 * Optional sparse cognitive sidecar. It does not enlarge AndroidNeuronState
 * or change the baseline ALIF-DT equations until an explicit adapter is wired.
 */
enum class ANS100NeuronRole {
    DETECTOR, MEMORY, PREDICTOR, CONTEXT, CONFIDENCE, INHIBITORY, COORDINATOR
}

enum class ANS100KnowledgeStatus {
    SUPPORTED, REFUTED, CONFLICTING, UNRESOLVED
}

data class ANS100NeuronFeatureState(
    val sensitivity: Double = 1.0,
    val memoryTrace: Double = 0.0,
    val predictionError: Double = 0.0,
    val confidence: Double = 0.0,
    val uncertainty: Double = 1.0,
    val plasticity: Double = 0.5,
    val inhibition: Double = 0.0,
    val contextMatch: Double = 0.0,
    val observations: Long = 0L,
) {
    fun validate() {
        require(sensitivity.isFinite() && sensitivity in 0.5..2.0)
        listOf(memoryTrace, predictionError, confidence, uncertainty, plasticity,
            inhibition, contextMatch).forEach { require(it.isFinite() && it in 0.0..1.0) }
        require(observations >= 0L)
    }
}

data class ANS100NeuronFeatureInput(
    val activity: Double,
    val predictedActivity: Double,
    val contextSimilarity: Double = 0.0,
    val inhibitoryDrive: Double = 0.0,
    val reward: Double = 0.0,
) {
    fun validate() {
        listOf(activity, predictedActivity, contextSimilarity, inhibitoryDrive)
            .forEach { require(it.isFinite() && it in 0.0..1.0) }
        require(reward.isFinite() && reward in -1.0..1.0)
    }
}

data class ANS100NeuronFeatureConfig(
    val memoryDecay: Double = 0.96,
    val confidenceRate: Double = 0.12,
    val uncertaintyRate: Double = 0.20,
    val inhibitionDecay: Double = 0.75,
    val sensitivityRate: Double = 0.04,
    val plasticitySurpriseGain: Double = 0.55,
    val plasticityStabilityGain: Double = 0.25,
    val targetActivity: Double = 0.20,
) {
    fun validate() {
        listOf(memoryDecay, confidenceRate, uncertaintyRate, inhibitionDecay,
            sensitivityRate, plasticitySurpriseGain, plasticityStabilityGain,
            targetActivity).forEach { require(it.isFinite() && it in 0.0..1.0) }
    }
}

data class ANS100SynapticMemoryState(
    val coactivationTrace: Double = 0.0,
    val predictiveTrace: Double = 0.0,
    val rewardTrace: Double = 0.0,
) {
    fun validate() {
        listOf(coactivationTrace, predictiveTrace, rewardTrace)
            .forEach { require(it.isFinite() && it in 0.0..1.0) }
    }
}

object ANS100NeuronFeatures {
    private fun clamp(value: Double) = value.coerceIn(0.0, 1.0)

    /**
     * Confidence is an internal ranking signal, not a calibrated probability.
     * Sensitivity remains advisory until the ALIF adapter is explicitly enabled.
     */
    fun update(
        state: ANS100NeuronFeatureState,
        input: ANS100NeuronFeatureInput,
        config: ANS100NeuronFeatureConfig = ANS100NeuronFeatureConfig(),
    ): ANS100NeuronFeatureState {
        state.validate()
        input.validate()
        config.validate()
        require(state.observations < Long.MAX_VALUE) { "feature observation counter exhausted" }

        val surprise = abs(input.activity - input.predictedActivity)
        val contextGap = 1.0 - input.contextSimilarity
        val uncertaintyTarget = clamp(0.75 * surprise + 0.25 * contextGap)
        val uncertainty = clamp((1.0 - config.uncertaintyRate) * state.uncertainty +
            config.uncertaintyRate * uncertaintyTarget)
        val confidence = clamp((1.0 - config.confidenceRate) * state.confidence +
            config.confidenceRate * (1.0 - uncertainty))
        val rewardedActivity = clamp(input.activity + maxOf(0.0, input.reward) * 0.25)
        val memory = clamp(config.memoryDecay * state.memoryTrace +
            (1.0 - config.memoryDecay) * rewardedActivity)
        val inhibition = clamp(config.inhibitionDecay * state.inhibition +
            (1.0 - config.inhibitionDecay) * input.inhibitoryDrive)
        val sensitivity = (state.sensitivity + config.sensitivityRate *
            (input.activity - config.targetActivity)).coerceIn(0.5, 2.0)
        val plasticity = clamp(0.5 + config.plasticitySurpriseGain * surprise -
            config.plasticityStabilityGain * confidence + 0.15 * maxOf(0.0, input.reward))

        return ANS100NeuronFeatureState(
            sensitivity = sensitivity,
            memoryTrace = memory,
            predictionError = surprise,
            confidence = confidence,
            uncertainty = uncertainty,
            plasticity = plasticity,
            inhibition = inhibition,
            contextMatch = input.contextSimilarity,
            observations = state.observations + 1L,
        )
    }

    /** Sparse synaptic trace update; canonical STDP/R-STDP weight is untouched. */
    fun updateSynapticMemory(
        state: ANS100SynapticMemoryState,
        coactivation: Double,
        predictionSuccess: Double,
        reward: Double,
        decay: Double = 0.98,
        learningRate: Double = 0.05,
    ): ANS100SynapticMemoryState {
        state.validate()
        listOf(coactivation, predictionSuccess, decay, learningRate)
            .forEach { require(it.isFinite() && it in 0.0..1.0) }
        require(reward.isFinite() && reward in -1.0..1.0)
        return ANS100SynapticMemoryState(
            coactivationTrace = clamp(decay * state.coactivationTrace + learningRate * coactivation),
            predictiveTrace = clamp(decay * state.predictiveTrace + learningRate * predictionSuccess),
            rewardTrace = clamp(decay * state.rewardTrace + learningRate * maxOf(0.0, reward)),
        )
    }

    /** Four-valued evidence logic: absence of evidence is UNRESOLVED, not REFUTED. */
    fun resolveEvidence(
        support: Double,
        refute: Double,
        uncertainty: Double,
        evidenceThreshold: Double = 0.60,
        conflictThreshold: Double = 0.45,
    ): ANS100KnowledgeStatus {
        listOf(support, refute, uncertainty, evidenceThreshold, conflictThreshold)
            .forEach { require(it.isFinite() && it in 0.0..1.0) }
        if (support >= evidenceThreshold && refute >= conflictThreshold) {
            return ANS100KnowledgeStatus.CONFLICTING
        }
        if (support >= evidenceThreshold && uncertainty < 0.80) return ANS100KnowledgeStatus.SUPPORTED
        if (refute >= evidenceThreshold && uncertainty < 0.80) return ANS100KnowledgeStatus.REFUTED
        return ANS100KnowledgeStatus.UNRESOLVED
    }

    /** Inhibition suppresses a signal without deleting its evidence or memory. */
    fun effectiveExcitatoryGain(activity: Double, inhibition: Double): Double {
        require(activity.isFinite() && activity in 0.0..1.0)
        require(inhibition.isFinite() && inhibition in 0.0..1.0)
        return activity * (1.0 - inhibition)
    }
}
