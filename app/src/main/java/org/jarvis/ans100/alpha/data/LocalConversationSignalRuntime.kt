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
 * Ten-neuron in-process Kotlin conversation signal pass.
 * Six transparent request-shape sensors feed two aggregators, an adaptive
 * integrator and an output gate. This is not semantic understanding or learning.
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
        val neurons: Map<String, NeuronState> = initialNeurons(),
    )

    private data class Features(
        val tokenVolume: Double,
        val lexicalDiversity: Double,
        val questionMarker: Double,
        val punctuationDensity: Double,
        val sentenceDensity: Double,
        val uppercaseRatio: Double,
    )

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

    private data class NeuronResult(val state: NeuronState, val spiked: Boolean)

    private var loadedState: RuntimeState? = null

    @Synchronized
    fun analyze(prompt: String): ConversationSignalContext {
        val clean = prompt.trim()
        if (clean.isEmpty() || clean.length > MAX_PROMPT_CHARS || '\u0000' in clean) {
            throw LocalConversationSignalException(
                "Текст для ANS-100 должен содержать от 1 до $MAX_PROMPT_CHARS символов.",
            )
        }
        val current = loadedState ?: readState().also { loadedState = it }
        val features = extractFeatures(clean)
        val featureValues = mapOf(
            "token_volume" to features.tokenVolume,
            "lexical_diversity" to features.lexicalDiversity,
            "question_marker" to features.questionMarker,
            "punctuation_density" to features.punctuationDensity,
            "sentence_density" to features.sentenceDensity,
            "uppercase_ratio" to features.uppercaseRatio,
        )
        val activeInputs = FEATURE_SENSOR_MAP.filter { (_, name) ->
            featureValues.getValue(name) > 0.0
        }
        if (activeInputs.isEmpty()) {
            return current.toContext(
                processedEvents = 0,
                processedBatches = 0,
                spikeCount = 0,
                activeNeuronCount = 0,
                attentionGateActive = false,
                features = features,
            )
        }

        if (current.currentTimeNs > MAX_MODEL_TIME_NS - TIME_STEP_NS - MAX_PROPAGATION_NS) {
            throw LocalConversationSignalException("Часы ANS-100 достигли предельного значения.")
        }

        val candidateNeurons = current.neurons.toMutableMap()
        val inputTime = current.currentTimeNs + TIME_STEP_NS
        var currentTime = inputTime
        var processedEvents = activeInputs.size
        var processedBatches = activeInputs.size
        val activeNeurons = linkedSetOf<String>()
        val contentSignals = mutableListOf<Double>()
        val structureSignals = mutableListOf<Double>()

        activeInputs.forEach { (sensorId, featureName) ->
            val result = processNeuron(
                candidateNeurons.getValue(sensorId),
                inputTime,
                featureValues.getValue(featureName),
                SENSOR_PARAMS,
            )
            candidateNeurons[sensorId] = result.state
            if (result.spiked) {
                activeNeurons += sensorId
                when (sensorId) {
                    TOKEN_VOLUME_SENSOR, LEXICAL_DIVERSITY_SENSOR ->
                        contentSignals += CONNECTION_STRENGTH
                    else -> structureSignals += CONNECTION_STRENGTH
                }
            }
        }

        val sensorSpikeCount = contentSignals.size + structureSignals.size
        if (sensorSpikeCount > 0) {
            val aggregateTime = inputTime + CONNECTION_DELAY_NS
            currentTime = aggregateTime
            processedEvents += sensorSpikeCount
            if (contentSignals.isNotEmpty()) {
                processedBatches += 1
                val result = processNeuron(
                    candidateNeurons.getValue(CONTENT_AGGREGATOR),
                    aggregateTime,
                    contentSignals.sum(),
                    AGGREGATOR_PARAMS,
                )
                candidateNeurons[CONTENT_AGGREGATOR] = result.state
                if (result.spiked) activeNeurons += CONTENT_AGGREGATOR
            }
            if (structureSignals.isNotEmpty()) {
                processedBatches += 1
                val result = processNeuron(
                    candidateNeurons.getValue(STRUCTURE_AGGREGATOR),
                    aggregateTime,
                    structureSignals.sum(),
                    AGGREGATOR_PARAMS,
                )
                candidateNeurons[STRUCTURE_AGGREGATOR] = result.state
                if (result.spiked) activeNeurons += STRUCTURE_AGGREGATOR
            }
        }

        val aggregatorSpikeCount = listOf(CONTENT_AGGREGATOR, STRUCTURE_AGGREGATOR)
            .count { it in activeNeurons }
        if (aggregatorSpikeCount > 0) {
            val integrationTime = inputTime + 2L * CONNECTION_DELAY_NS
            currentTime = integrationTime
            processedEvents += aggregatorSpikeCount
            processedBatches += 1
            val result = processNeuron(
                candidateNeurons.getValue(REQUEST_INTEGRATOR),
                integrationTime,
                aggregatorSpikeCount * CONNECTION_STRENGTH,
                REQUEST_INTEGRATOR_PARAMS,
            )
            candidateNeurons[REQUEST_INTEGRATOR] = result.state
            if (result.spiked) activeNeurons += REQUEST_INTEGRATOR
        }

        val integratorSpiked = REQUEST_INTEGRATOR in activeNeurons
        var gateActive = false
        if (integratorSpiked) {
            val gateTime = inputTime + 3L * CONNECTION_DELAY_NS
            currentTime = gateTime
            processedEvents += 1
            processedBatches += 1
            val result = processNeuron(
                candidateNeurons.getValue(ATTENTION_GATE),
                gateTime,
                CONNECTION_STRENGTH,
                ATTENTION_GATE_PARAMS,
            )
            candidateNeurons[ATTENTION_GATE] = result.state
            if (result.spiked) {
                activeNeurons += ATTENTION_GATE
                gateActive = true
            }
        }

        if (processedEvents > MAX_EVENTS_PER_TURN) {
            throw LocalConversationSignalException("ANS-100 превысил лимит событий одного запроса.")
        }
        if (current.checkpointVersion == MAX_CHECKPOINT_VERSION) {
            throw LocalConversationSignalException("Счётчик checkpoint ANS-100 исчерпан.")
        }
        val next = RuntimeState(
            checkpointVersion = current.checkpointVersion + 1L,
            currentTimeNs = currentTime,
            neurons = candidateNeurons.toMap(),
        )
        validate(next)
        if (!storage.write(encode(next))) {
            throw LocalConversationSignalException(
                "Не удалось сохранить состояние ANS-100 в данных приложения.",
            )
        }
        loadedState = next
        return next.toContext(
            processedEvents = processedEvents,
            processedBatches = processedBatches,
            spikeCount = activeNeurons.size,
            activeNeuronCount = activeNeurons.size,
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
            params.potentialMin, params.potentialMax,
        )
        val decayedAdaptation = clamp(
            state.adaptation * exp(-deltaNs.toDouble() / params.tauAdaptationNs),
            0.0, params.adaptationMax,
        )
        val potential = clamp(
            decayedPotential + totalInput, params.potentialMin, params.potentialMax,
        )
        val threshold = clamp(
            params.thresholdBase + params.adaptationGain * decayedAdaptation,
            params.thresholdMin, params.thresholdMax,
        )
        val spike = timeNs >= state.refractoryUntilNs && potential >= threshold
        val next = if (spike) {
            if (state.spikeSequence == Long.MAX_VALUE ||
                timeNs > MAX_MODEL_TIME_NS - params.refractoryNs
            ) {
                throw LocalConversationSignalException("Счётчик импульсов или часы ANS-100 переполнены.")
            }
            NeuronState(
                potential = params.potentialReset,
                adaptation = clamp(
                    decayedAdaptation + params.adaptationPerSpike,
                    0.0, params.adaptationMax,
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
        if (values.firstOrNull() == LEGACY_STATE_FORMAT) return migrateLegacyState(values)
        if (values.size != STATE_FIELD_COUNT || values[0] != STATE_FORMAT) {
            throw LocalConversationSignalException("Checkpoint ANS-100 повреждён или имеет неизвестную версию.")
        }
        fun long(index: Int): Long = values[index].toLongOrNull()?.takeIf { it >= 0L }
            ?: throw LocalConversationSignalException("Checkpoint ANS-100 содержит неверное целое число.")
        fun number(index: Int): Double = values[index].toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw LocalConversationSignalException("Checkpoint ANS-100 содержит неверное число.")
        val restoredNeurons = linkedMapOf<String, NeuronState>()
        var offset = 3
        NEURON_IDS.forEach { id ->
            restoredNeurons[id] = NeuronState(
                potential = number(offset++),
                adaptation = number(offset++),
                lastUpdateNs = long(offset++),
                refractoryUntilNs = long(offset++),
                spikeSequence = long(offset++),
            )
        }
        return RuntimeState(
            checkpointVersion = long(1),
            currentTimeNs = long(2),
            neurons = restoredNeurons,
        ).also(::validate)
    }

    private fun migrateLegacyState(values: List<String>): RuntimeState {
        if (values.size != LEGACY_STATE_FIELD_COUNT) {
            throw LocalConversationSignalException("Старый checkpoint ANS-100 повреждён.")
        }
        fun long(index: Int): Long = values[index].toLongOrNull()?.takeIf { it >= 0L }
            ?: throw LocalConversationSignalException("Старый checkpoint ANS-100 содержит неверное целое число.")
        fun number(index: Int): Double = values[index].toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw LocalConversationSignalException("Старый checkpoint ANS-100 содержит неверное число.")
        val migrated = initialNeurons().toMutableMap()
        migrated[REQUEST_INTEGRATOR] = NeuronState(
            potential = number(3), adaptation = number(4), lastUpdateNs = long(5),
            refractoryUntilNs = long(6), spikeSequence = long(7),
        )
        migrated[ATTENTION_GATE] = NeuronState(
            potential = number(8), adaptation = number(9), lastUpdateNs = long(10),
            refractoryUntilNs = long(11), spikeSequence = long(12),
        )
        return RuntimeState(
            checkpointVersion = long(1),
            currentTimeNs = long(2),
            neurons = migrated,
        ).also(::validate)
    }

    private fun encode(state: RuntimeState): String {
        val fields = mutableListOf(
            STATE_FORMAT, state.checkpointVersion.toString(), state.currentTimeNs.toString(),
        )
        NEURON_IDS.forEach { id ->
            val neuron = state.neurons.getValue(id)
            fields += listOf(
                neuron.potential.toString(), neuron.adaptation.toString(),
                neuron.lastUpdateNs.toString(), neuron.refractoryUntilNs.toString(),
                neuron.spikeSequence.toString(),
            )
        }
        return fields.joinToString("|")
    }

    private fun validate(state: RuntimeState) {
        if (state.currentTimeNs > MAX_MODEL_TIME_NS || state.neurons.keys != NEURON_IDS.toSet()) {
            throw LocalConversationSignalException("Checkpoint ANS-100 нарушает числовые ограничения.")
        }
        state.neurons.forEach { (id, neuron) ->
            val params = paramsFor(id)
            if (!neuron.potential.isFinite() || !neuron.adaptation.isFinite() ||
                neuron.potential !in params.potentialMin..params.potentialMax ||
                neuron.adaptation !in 0.0..params.adaptationMax ||
                neuron.lastUpdateNs > state.currentTimeNs ||
                neuron.refractoryUntilNs > MAX_MODEL_TIME_NS
            ) {
                throw LocalConversationSignalException("Checkpoint ANS-100 нарушает числовые ограничения.")
            }
        }
    }

    private fun RuntimeState.toContext(
        processedEvents: Int,
        processedBatches: Int,
        spikeCount: Int,
        activeNeuronCount: Int,
        attentionGateActive: Boolean,
        features: Features,
    ): ConversationSignalContext {
        val integrator = neurons.getValue(REQUEST_INTEGRATOR)
        val threshold = clamp(
            REQUEST_INTEGRATOR_PARAMS.thresholdBase +
                REQUEST_INTEGRATOR_PARAMS.adaptationGain * integrator.adaptation,
            REQUEST_INTEGRATOR_PARAMS.thresholdMin,
            REQUEST_INTEGRATOR_PARAMS.thresholdMax,
        )
        return ConversationSignalContext(
            runtimeVersion = RUNTIME_VERSION,
            processedEvents = processedEvents,
            processedBatches = processedBatches,
            spikeCount = spikeCount,
            activeNeuronCount = activeNeuronCount,
            networkNeuronCount = NEURON_IDS.size,
            attentionGateActive = attentionGateActive,
            tokenVolume = features.tokenVolume,
            lexicalDiversity = features.lexicalDiversity,
            questionMarker = features.questionMarker,
            punctuationDensity = features.punctuationDensity,
            sentenceDensity = features.sentenceDensity,
            uppercaseRatio = features.uppercaseRatio,
            integratorAdaptation = integrator.adaptation,
            effectiveThreshold = threshold,
            statePersisted = checkpointVersion > 0L,
            checkpointVersion = checkpointVersion,
            modelTimeNs = currentTimeNs,
        )
    }

    private fun extractFeatures(prompt: String): Features {
        val tokens = TOKEN_PATTERN.findAll(prompt).map { it.value }.toList()
        val codePoints = prompt.codePoints().toArray()
        val letters = codePoints.filter { Character.isLetter(it) }
        val punctuationCount = codePoints.count { isPunctuation(it) }
        val sentenceCount = prompt.count { it in SENTENCE_TERMINATORS }
        return Features(
            tokenVolume = minOf(tokens.size / 12.0, 1.0),
            lexicalDiversity = if (tokens.isEmpty()) 0.0 else {
                tokens.map { it.lowercase(Locale.ROOT) }.toSet().size.toDouble() / tokens.size
            },
            questionMarker = if ('?' in prompt) 0.35 else 0.0,
            punctuationDensity = minOf(punctuationCount / 4.0, 1.0),
            sentenceDensity = minOf(sentenceCount / 4.0, 1.0),
            uppercaseRatio = if (letters.isEmpty()) 0.0 else {
                letters.count { Character.isUpperCase(it) }.toDouble() / letters.size
            },
        )
    }

    companion object {
        private const val RUNTIME_VERSION = "JARVIS_ANS100_CONVERSATION_SIGNAL_V2"
        private const val LEGACY_STATE_FORMAT = "JARVIS_ANS100_ANDROID_SIGNAL_V1"
        private const val STATE_FORMAT = "JARVIS_ANS100_ANDROID_SIGNAL_V2"
        private const val LEGACY_STATE_FIELD_COUNT = 13
        private const val STATE_FIELD_COUNT = 3 + 10 * 5
        private const val MAX_PROMPT_CHARS = 4_000
        private const val MAX_MODEL_TIME_NS = Long.MAX_VALUE
        private const val MAX_CHECKPOINT_VERSION = Long.MAX_VALUE
        private const val TIME_STEP_NS = 10_000_000L
        private const val CONNECTION_DELAY_NS = 1L
        private const val MAX_PROPAGATION_NS = 3L * CONNECTION_DELAY_NS
        private const val CONNECTION_STRENGTH = 1.0
        private const val MAX_EVENTS_PER_TURN = 64
        private const val TOKEN_VOLUME_SENSOR = "token_volume_sensor"
        private const val LEXICAL_DIVERSITY_SENSOR = "lexical_diversity_sensor"
        private const val QUESTION_MARKER_SENSOR = "question_marker_sensor"
        private const val PUNCTUATION_DENSITY_SENSOR = "punctuation_density_sensor"
        private const val SENTENCE_DENSITY_SENSOR = "sentence_density_sensor"
        private const val UPPERCASE_RATIO_SENSOR = "uppercase_ratio_sensor"
        private const val CONTENT_AGGREGATOR = "content_aggregator"
        private const val STRUCTURE_AGGREGATOR = "structure_aggregator"
        private const val REQUEST_INTEGRATOR = "request_integrator"
        private const val ATTENTION_GATE = "attention_gate"
        private val NEURON_IDS = listOf(
            ATTENTION_GATE, CONTENT_AGGREGATOR, LEXICAL_DIVERSITY_SENSOR,
            PUNCTUATION_DENSITY_SENSOR, QUESTION_MARKER_SENSOR, REQUEST_INTEGRATOR,
            SENTENCE_DENSITY_SENSOR, STRUCTURE_AGGREGATOR, TOKEN_VOLUME_SENSOR,
            UPPERCASE_RATIO_SENSOR,
        )
        private val FEATURE_SENSOR_MAP = listOf(
            TOKEN_VOLUME_SENSOR to "token_volume",
            LEXICAL_DIVERSITY_SENSOR to "lexical_diversity",
            QUESTION_MARKER_SENSOR to "question_marker",
            PUNCTUATION_DENSITY_SENSOR to "punctuation_density",
            SENTENCE_DENSITY_SENSOR to "sentence_density",
            UPPERCASE_RATIO_SENSOR to "uppercase_ratio",
        )
        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '…', '。', '！', '？')
        private fun isPunctuation(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
            Character.CONNECTOR_PUNCTUATION.toInt(),
            Character.DASH_PUNCTUATION.toInt(),
            Character.START_PUNCTUATION.toInt(),
            Character.END_PUNCTUATION.toInt(),
            Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
            Character.FINAL_QUOTE_PUNCTUATION.toInt(),
            Character.OTHER_PUNCTUATION.toInt() -> true
            else -> false
        }
        private fun initialNeurons(): Map<String, NeuronState> =
            NEURON_IDS.associateWith { NeuronState() }
        private fun paramsFor(id: String): NeuronParams = when (id) {
            TOKEN_VOLUME_SENSOR, LEXICAL_DIVERSITY_SENSOR, QUESTION_MARKER_SENSOR,
            PUNCTUATION_DENSITY_SENSOR, SENTENCE_DENSITY_SENSOR, UPPERCASE_RATIO_SENSOR -> SENSOR_PARAMS
            CONTENT_AGGREGATOR, STRUCTURE_AGGREGATOR -> AGGREGATOR_PARAMS
            REQUEST_INTEGRATOR -> REQUEST_INTEGRATOR_PARAMS
            ATTENTION_GATE -> ATTENTION_GATE_PARAMS
            else -> throw LocalConversationSignalException("Неизвестный нейрон ANS-100.")
        }
        private fun clamp(value: Double, lower: Double, upper: Double): Double =
            minOf(upper, maxOf(lower, value))
        private val TOKEN_PATTERN = Regex("[A-Za-zА-Яа-яЁё0-9]{3,}", RegexOption.IGNORE_CASE)
        private val SENSOR_PARAMS = NeuronParams(
            thresholdBase = 0.05, thresholdMin = 0.05, thresholdMax = 0.05,
            adaptationPerSpike = 0.0, tauAdaptationNs = 100_000_000L, adaptationGain = 0.0,
        )
        private val AGGREGATOR_PARAMS = NeuronParams(
            thresholdBase = 1.0, thresholdMin = 1.0, thresholdMax = 1.0,
            adaptationPerSpike = 0.0, tauAdaptationNs = 100_000_000L, adaptationGain = 0.0,
        )
        private val REQUEST_INTEGRATOR_PARAMS = NeuronParams(
            thresholdBase = 1.5, thresholdMin = 1.5, thresholdMax = 2.0,
            adaptationPerSpike = 0.2, tauAdaptationNs = 100_000_000L, adaptationGain = 0.5,
        )
        private val ATTENTION_GATE_PARAMS = NeuronParams(
            thresholdBase = 1.0, thresholdMin = 1.0, thresholdMax = 1.0,
            adaptationPerSpike = 0.0, tauAdaptationNs = 100_000_000L, adaptationGain = 0.0,
        )
    }
}
