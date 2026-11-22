package org.jarvis.ans100.alpha.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSemanticDialogueMemoryCodecTest {
    @Test
    fun readsExistingV1MemoryWithoutLosingTasksOrPreferences() {
        val legacy = """
            {
              "schema":"JARVIS_SEMANTIC_DIALOGUE_MEMORY_JSON_V1",
              "active_tasks":[
                {
                  "text":"Добавить экспорт",
                  "status":"open",
                  "source_message_id":"u1",
                  "updated_at_ms":10,
                  "completed_by_message_id":null,
                  "provenance":"explicit_user_request"
                }
              ],
              "completed_tasks":[],
              "explicit_preferences":[
                {
                  "text":"Предпочитаю короткие ответы",
                  "source_message_id":"u2",
                  "created_at_ms":20,
                  "provenance":"explicit_user_statement"
                }
              ],
              "unresolved_questions":[]
            }
        """.trimIndent()

        val memory = LocalSemanticDialogueMemoryCodec.decode(legacy)

        assertEquals("u1", memory.activeTasks.single().sourceMessageId)
        assertEquals("u2", memory.explicitPreferences.single().sourceMessageId)
        assertTrue(memory.preferenceConflicts.isEmpty())
    }

    @Test
    fun v2RoundTripPreservesUnresolvedAndResolvedPreferenceConflicts() {
        val original = LocalSemanticDialogueMemory(
            explicitPreferences = listOf(
                LocalExplicitPreference("Предпочитаю подробные ответы", "u2", 20L),
            ),
            preferenceConflicts = listOf(
                LocalPreferenceConflict(
                    "Предпочитаю короткие ответы", "u1",
                    "Предпочитаю подробные ответы", "u2",
                ),
                LocalPreferenceConflict(
                    "Старое предпочтение", "u3",
                    "Новое предпочтение", "u4",
                    "resolved_by_explicit_correction",
                ),
            ),
        )

        val encoded = LocalSemanticDialogueMemoryCodec.encode(original)
        val decoded = LocalSemanticDialogueMemoryCodec.decode(encoded)

        assertEquals(original, decoded)
        assertTrue(encoded.contains("JARVIS_SEMANTIC_DIALOGUE_MEMORY_JSON_V2"))
    }
}
