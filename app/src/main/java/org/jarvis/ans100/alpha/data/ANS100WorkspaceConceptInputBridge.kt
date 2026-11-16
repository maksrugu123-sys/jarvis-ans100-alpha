package org.jarvis.ans100.alpha.data

import java.security.MessageDigest
import java.util.Locale

/**
 * Connects only explicitly approved workspace entries in the dedicated
 * "concept"/"понятие" category to the experimental concept-input path.
 *
 * Ordinary notes are deliberately excluded. Labels are matched lexically;
 * this bridge does not infer synonyms, relations, or semantic equivalence.
 */
class ANS100WorkspaceConceptInputBridge {
    data class Result(
        val batch: AndroidHybridEvidenceInputAdapter.ActivationBatch,
        val eligibleConceptCount: Int,
        val activeConceptCount: Int,
        val skippedConceptCount: Int,
    )

    fun merge(
        evidenceBatch: AndroidHybridEvidenceInputAdapter.ActivationBatch,
        rawUserMessage: String,
        workspaceMemories: List<LocalWorkspaceMemory>,
    ): Result {
        require(evidenceBatch.turnId.isNotBlank())
        require(evidenceBatch.timestampNs > 0L)
        require(workspaceMemories.size <= LocalWorkspaceMemoryCache.MAX_CACHED_RECORDS)

        // Keep the existing evidence-only route for inputs outside the lexical
        // encoder's documented bound instead of silently truncating the message.
        if (rawUserMessage.length > ANS100AssociativeFeatureEncoder.MAX_INPUT_CHARS) {
            return Result(evidenceBatch, 0, 0, 0)
        }

        val eligible = workspaceMemories.asSequence()
            .filter { it.userApproved && it.category.trim().lowercase(Locale.ROOT) in CONCEPT_CATEGORIES }
            .filter { it.content.isNotBlank() && it.content.trim().length <= ANS100ConceptMemory.MAX_LABEL_CHARS }
            .filter { '\n' !in it.content && '\r' !in it.content }
            .sortedBy { it.id }
            .take(ANS100ConceptMemory.MAX_CONCEPTS)
            .toList()

        val memory = ANS100ConceptMemory()
        var learned = 0
        eligible.forEach { record ->
            val concept = ANS100ConceptMemory.Concept(
                id = conceptId(record.id),
                label = record.content.trim(),
            )
            if (memory.learnConcept(concept, ANS100ConceptMemory.LearningSource.EXPLICIT_USER)) {
                learned++
            }
        }

        val frame = ANS100NeuralInputAdapter().encode(evidenceBatch.turnId, rawUserMessage)
        val activations = ANS100ConceptActivationAdapter().activate(frame, memory)
        val conceptBatch = ANS100ConceptNeuralInputAdapter().createBatch(
            activations = activations,
            memory = memory,
            timestampNs = evidenceBatch.timestampNs,
        )
        val merged = ANS100ConceptNeuralInputAdapter().merge(evidenceBatch, conceptBatch)
        return Result(
            batch = merged,
            eligibleConceptCount = eligible.size,
            activeConceptCount = activations.activations.size,
            skippedConceptCount = workspaceMemories.count { it.userApproved } - learned,
        )
    }

    private fun conceptId(memoryId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(memoryId.toByteArray(Charsets.UTF_8))
            .take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return "memory:$digest"
    }

    companion object {
        private val CONCEPT_CATEGORIES = setOf("concept", "понятие")
    }
}
