package org.jarvis.ans100.alpha.data

import android.content.Context
import java.util.Locale
import kotlin.math.exp

interface ConversationSignalStorage {
    fun read(): String?
    fun write(value: String): Boolean
}

class SharedPreferencesConversationSignalStorage(context: Context) : ConversationSignalStorage {
    private val preferences = context.applicationContext.getSharedPreferences(
        "jarvis_android_ans100_signal_v1",
        Context.MODE_PRIVATE,
    )

    override fun read(): String? = preferences.getString("state", null)

    override fun write(value: String): Boolean =
        preferences.edit().putString("state", value).commit()
}

class LocalConversationSignalException(message: String) : IllegalStateException(message)

/**
 * In-process Kotlin port of the bounded two-neuron ANS-100 conversation signal pass.
 *
 * It mirrors the Android-facing Python reference algorithm: three transparent text
 * features feed an Adaptive LIF-DT integrator; a spike propagates once to an
 * attention-gate neuron after a 1 ns delay. Learning remains disabled.
 */
class LocalConversationSignalRuntime(
    private val storage: ConversationSignalStorage,
) {
    private data class NeuronState(
        val potential: Double = 0.0,
        val adaptation: Double = 0.0,
        val lastUpdateNs: Long = 0L,
        val refractoryUntilNs: Long = 0L,
        val spikeSequence: Long = 0L,
    )

    private data class RuntimeState(
        val checkpointVersion: Long = 0L,
        val currentTimeNs: Long = 0L,
        val integrator: NeuronState = NeuronState(),
        val attentionGate: NeuronState = NeuronState(),
    )

    private data class Features(
        val tokenVolume: Double,
        val lexicalDiversity: Double,
        val questionMarker: Double,
    )

    private data class NeuronResult(
        val state: NeuronState,
        val spiked: Boolean,
    )

    private var loadedState: RuntimeState? = null

    @Synchronized
    fun analyze(prompt: String): ConversationSignalContext {
        val clean = prompt.trim()
        if (clean.isEmpty() || clean.length > MAX_PROMPT_CHARS || '\u0000' in clean) {
            throw LocalConversationSignalException("Текст для ANS-100 должен содержать от 1 до $MAX_PROMPT_CHARS символов.")
        }
        val current = loadedState ?: readState().also { loadedState = it }
        val features = extractFeatures(clean)
        val hasTokenVolume = features.tokenVolume > 0.0
        val hasLexicalDiversity = features.lexicalDiversity > 0.0
        val hasQuestionMarker = features.questionMarker > 0.0
        val externalEventCount = listOf(
            hasTokenVolume, hasLexicalDiversity, hasQuestionMarker,
        ).count { it }

        if (externalEventCount == 0) {
            return current.toContext(
                processedEvents = 0,
                processedBatches = 0,
                spikeCount = 0,
                attentionGateActive = false,
                features = features,
            )
        }

        if (current.currentTimeNs > MAX_MODEL_TIME_NS - TIME_STEP_NS - 1L) {
            throw LocalConversationSignalException("Часы ANS-100 достигли предельного значения.")
        }
        val inputTime = current.currentTimeNs + TIME_STEP_NS
        val inputs = buildList {
            if (hasTokenVolume) add("token_volume" to features.tokenVolume)
            if (hasLexicalDiversity) add("lexical_diversity" to features.lexicalDiversity)
            if (hasQuestionMarker) add("question_marker" to features.questionMarker)
        }.sortedBy { it.first }
        var inputDelta = 0.0
        var compensation = 0.0
        inputs.forEach { item ->
            val adjusted = item.second - compensation
            val nextSum = inputDelta + adjusted
            compensation = (nextSum - inputDelta) - adjusted
            inputDelta = nextSum
        }

        val integratorResult = processNeuron(
            state = current.integrator,
            timeNs = inputTime,
            totalInput = inputDelta,
            params = INTEGRATOR,
        )
        var candidate = current.copy(
            currentTimeNs = inputTime,
            integrator = integratorResult.state,
        )
        var processedEvents = externalEventCount
        var processedBatches = 1
        var spikeCount = if (integratorResult.spiked) 1 else 0
        var gateActive = false

        if (integratorResult.spiked) {
            if (inputTime == MAX_MODEL_TIME_NS) {
                throw LocalConversationSignalException("Время распространения импульса ANS-100 переполнено.")
            }
            val gateTime = inputTime + CONNECTION_DELAY_NS
            val gateResult = processNeuron(
                state = current.attentionGate,
                timeNs = gateTime,
                totalInput = CONNECTION_STRENGTH,
                params = ATTENTION_GATE,
            )
            candidate = candidate.copy(
                currentTimeNs = gateTime,
                attentionGate = gateResult.state,
            )
            processedEvents += 1
            processedBatches += 1
            if (gateResult.spiked) {
                spikeCount += 1
                gateActive = true
            }
        }

        if (candidate.checkpointVersion >= MAX_CHECKPOINT_VERSION) {
            throw LocalConversationSignalException("Счётчик checkpoint ANS-100 исчерпан.")
        }
        val next = candidate.copy(checkpointVersion = candidate.checkpointVersion + 1L)
        if (!storage.write(encode(next))) {
            throw LocalConversationSignalException("Не удалось сохранить состояние ANS-100 в данных приложения.")
        }
        loadedState = next
        return next.toContext(
            processedEvents = processedEvents,
            processedBatches = processedBatches,
            spikeCount = spikeCount,
            attentionGateActive = gateActive,
            features = features,
        )
    }

    private fun processNeuron(
        state: NeuronState,
        timeNs: Long,
        totalInput: Double,
        params: NeuronParams,
    ): NeuronResult {
        if (timeNs < state.lastUpdateNs) {
            throw LocalConversationSignalException("Событие ANS-100 пришло раньше последнего обновления нейрона.")
        }
        val deltaNs = timeNs - state.lastUpdateNs
        val decayedPotential = clamp(
            state.potential * exp(-deltaNs.toDouble() / params.tauMembraneNs),
            params.potentialMin,
            params.potentialMax,
        )
        val decayedAdaptation = clamp(
            state.adaptation * exp(-deltaNs.toDouble() / params.tauAdaptationNs),
            0.0,
            params.adaptationMax,
        )
        val potential = clamp(
            decayedPotential + totalInput,
            params.potentialMin,
            params.potentialMax,
        )
        val threshold = clamp(
            params.thresholdBase + params.adaptationGain * decayedAdaptation,
            params.thresholdMin,
            params.thresholdMax,
        )
        val refractory = timeNs < state.refractoryUntilNs
        val spike = !refractory && potential >= threshold
        val next = if (spike) {
            if (state.spikeSequence == Long.MAX_VALUE) {
                throw LocalConversationSignalException("Счётчик импульсов ANS-100 исчерпан.")
            }
            if (timeNs > MAX_MODEL_TIME_NS - params.refractoryNs) {
                throw LocalConversationSignalException("Период восстановления ANS-100 переполняет модельные часы.")
            }
            NeuronState(
                potential = params.potentialReset,
                adaptation = clamp(
                    decayedAdaptation + params.adaptationPerSpike,
                    0.0,
                    params.adaptationMax,
                ),
                lastUpdateNs = timeNs,
                refractoryUntilNs = timeNs + params.refractoryNs,
                spikeSequence = state.spikeSequence + 1L,
            )
        } else {
            NeuronState(
                potential = potential,
                adaptation = decayedAdaptation,
                lastUpdateNs = timeNs,
                refractoryUntilNs = state.refractoryUntilNs,
                spikeSequence = state.spikeSequence,
            )
        }
        if (!next.potential.isFinite() || !next.adaptation.isFinite()) {
            throw LocalConversationSignalException("Числовое состояние ANS-100 стало некорректным.")
        }
        return NeuronResult(next, spike)
    }

    private fun readState(): RuntimeState {
        val raw = storage.read() ?: return RuntimeState()
        val values = raw.split('|')
        if (values.size != STATE_FIELD_COUNT || values[0] != STATE_FORMAT) {
            throw LocalConversationSignalException("Checkpoint ANS-100 повреждён или имеет неизвестную версию.")
        }
        fun long(index: Int): Long =
            values[index].toLongOrNull()?.takeIf { it >= 0L }
                ?: throw LocalConversationSignalException("Checkpoint ANS-100 содержит неверное целое число.")
        fun number(index: Int): Double =
            values[index].toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw LocalConversationSignalException("Checkpoint ANS-100 содержит неверное число.")
        val restored = RuntimeState(
            checkpointVersion = long(1),
            currentTimeNs = long(2),
            integrator = NeuronState(
                potential = number(3),
                adaptation = number(4),
                lastUpdateNs = long(5),
                refractoryUntilNs = long(6),
                spikeSequence = long(7),
            ),
            attentionGate = NeuronState(
                potential = number(8),
                adaptation = number(9),
                lastUpdateNs = long(10),
                refractoryUntilNs = long(11),
                spikeSequence = long(12),
            ),
        )
        validate(restored)
        return restored
    }

    private fun encode(state: RuntimeState): String = listOf(
        STATE_FORMAT,
        state.checkpointVersion.toString(),
        state.currentTimeNs.toString(),
        state.integrator.potential.toString(),
        state.integrator.adaptation.toString(),
        state.integrator.lastUpdateNs.toString(),
        state.integrator.refractoryUntilNs.toString(),
        state.integrator.spikeSequence.toString(),
        state.attentionGate.potential.toString(),
        state.attentionGate.adaptation.toString(),
        state.attentionGate.lastUpdateNs.toString(),
        state.attentionGate.refractoryUntilNs.toString(),
        state.attentionGate.spikeSequence.toString(),
    ).joinToString("|")

    private fun validate(state: RuntimeState) {
        if (state.checkpointVersion > MAX_CHECKPOINT_VERSION ||
            state.currentTimeNs > MAX_MODEL_TIME_NS ||
            state.integrator.potential !in INTEGRATOR.potentialMin..INTEGRATOR.potentialMax ||
            state.integrator.adaptation !in 0.0..INTEGRATOR.adaptationMax ||
            state.integrator.lastUpdateNs > state.currentTimeNs ||
            state.integrator.refractoryUntilNs > MAX_MODEL_TIME_NS ||
            state.attentionGate.potential !in ATTENTION_GATE.potentialMin..ATTENTION_GATE.potentialMax ||
            state.attentionGate.adaptation !in 0.0..ATTENTION_GATE.adaptationMax ||
            state.attentionGate.lastUpdateNs > state.currentTimeNs ||
            state.attentionGate.refractoryUntilNs > MAX_MODEL_TIME_NS
        ) {
            throw LocalConversationSignalException("Checkpoint ANS-100 нарушает числовые ограничения.")
        }
    }

    private fun RuntimeState.toContext(
        processedEvents: Int,
        processedBatches: Int,
        spikeCount: Int,
        attentionGateActive: Boolean,
        features: Features,
    ): ConversationSignalContext {
        val threshold = clamp(
            INTEGRATOR.thresholdBase + INTEGRATOR.adaptationGain * integrator.adaptation,
            INTEGRATOR.thresholdMin,
            INTEGRATOR.thresholdMax,
        )
        return ConversationSignalContext(
            runtimeVersion = RUNTIME_VERSION,
            processedEvents = processedEvents,
            processedBatches = processedBatches,
            spikeCount = spikeCount,
            attentionGateActive = attentionGateActive,
            tokenVolume = features.tokenVolume,
            lexicalDiversity = features.lexicalDiversity,
            questionMarker = features.questionMarker,
            integratorAdaptation = integrator.adaptation,
            effectiveThreshold = threshold,
            statePersisted = checkpointVersion > 0L,
            checkpointVersion = checkpointVersion,
            modelTimeNs = currentTimeNs,
        )
    }

    private fun extractFeatures(prompt: String): Features {
        val tokens = TOKEN_PATTERN.findAll(prompt).map { it.value }.toList()
        return Features(
            tokenVolume = minOf(tokens.size / 12.0, 1.0),
            lexicalDiversity = if (tokens.isEmpty()) 0.0 else {
                tokens.map { it.lowercase(Locale.ROOT) }.toSet().size.toDouble() / tokens.size
            },
            questionMarker = if ('?' in prompt) 0.35 else 0.0,
        )
    }

    private data class NeuronParams(
        val thresholdBase: Double,
        val thresholdMin: Double,
        val thresholdMax: Double,
        val adaptationPerSpike: Double,
        val tauAdaptationNs: Long,
        val adaptationGain: Double,
        val potentialReset: Double = 0.0,
        val potentialMin: Double = -2.0,
        val potentialMax: Double = 2.0,
        val tauMembraneNs: Long = 20_000_000L,
        val adaptationMax: Double = 1.0,
        val refractoryNs: Long = 2_000_000L,
    )

    companion object {
        private const val RUNTIME_VERSION = "JARVIS_ANS100_CONVERSATION_SIGNAL_V1"
        private const val STATE_FORMAT = "JARVIS_ANS100_ANDROID_SIGNAL_V1"
        private const val STATE_FIELD_COUNT = 13
        private const val MAX_PROMPT_CHARS = 4_000
        private const val MAX_MODEL_TIME_NS = Long.MAX_VALUE
        private const val MAX_CHECKPOINT_VERSION = Long.MAX_VALUE
        private const val TIME_STEP_NS = 10_000_000L
        private const val CONNECTION_DELAY_NS = 1L
        private const val CONNECTION_STRENGTH = 1.0
        private val TOKEN_PATTERN = Regex("[A-Za-zА-Яа-яЁё0-9]{3,}", RegexOption.IGNORE_CASE)
        private fun clamp(value: Double, lower: Double, upper: Double): Double =
            minOf(upper, maxOf(lower, value))
        private val INTEGRATOR = NeuronParams(
            thresholdBase = 1.0,
            thresholdMin = 1.0,
            thresholdMax = 1.5,
            adaptationPerSpike = 0.2,
            tauAdaptationNs = 100_000_000L,
            adaptationGain = 0.5,
        )
        private val ATTENTION_GATE = NeuronParams(
            thresholdBase = 1.0,
            thresholdMin = 1.0,
            thresholdMax = 1.0,
            adaptationPerSpike = 0.0,
            tauAdaptationNs = 100_000_000L,
            adaptationGain = 0.0,
        )
    }
}
