package org.jarvis.ans100.alpha.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Durable bounded checkpoint for direct-evidence hypothesis revisions. */
class ANS100HypothesisRevisionLedger(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE hypothesis_revision_checkpoint (
                singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                schema TEXT NOT NULL,
                payload TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
    }

    @Synchronized
    fun load(): ANS100HypothesisRevisionEngine.Snapshot? {
        val payload = readableDatabase.rawQuery(
            "SELECT schema, payload FROM hypothesis_revision_checkpoint WHERE singleton_id=1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.getString(0) != SNAPSHOT_SCHEMA) return null
            cursor.getString(1)
        }
        if (payload.length > MAX_SNAPSHOT_CHARS) return null
        return runCatching {
            decode(payload).also(::validateSnapshot)
        }.getOrNull()
    }

    @Synchronized
    fun save(snapshot: ANS100HypothesisRevisionEngine.Snapshot): Boolean {
        if (runCatching { validateSnapshot(snapshot) }.isFailure) return false
        val payload = encode(snapshot)
        if (payload.length > MAX_SNAPSHOT_CHARS) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("singleton_id", 1)
                put("schema", SNAPSHOT_SCHEMA)
                put("payload", payload)
                put("updated_at_ms", System.currentTimeMillis())
            }
            if (db.insertWithOnConflict(
                    "hypothesis_revision_checkpoint",
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) == -1L
            ) return false
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
    }

    private fun validateSnapshot(snapshot: ANS100HypothesisRevisionEngine.Snapshot) {
        ANS100HypothesisRevisionEngine(
            maxHypotheses = snapshot.hypotheses.size.coerceAtLeast(1),
        ).restoreSnapshot(snapshot)
    }

    private fun encode(snapshot: ANS100HypothesisRevisionEngine.Snapshot): String {
        val hypotheses = JSONArray()
        snapshot.hypotheses.forEach { item ->
            val evidence = JSONArray()
            item.evidence.forEach { record ->
                evidence.put(JSONObject()
                    .put("evidence_ref", record.evidenceRef)
                    .put("source", record.source.name)
                    .put("supports", record.supports))
            }
            hypotheses.put(JSONObject()
                .put("hypothesis_id", item.hypothesisId)
                .put("from_concept_id", item.fromConceptId)
                .put("relation_type", item.relationType.name)
                .put("to_concept_id", item.toConceptId)
                .put("premise_relation_ids", JSONArray(item.premiseRelationIds))
                .put("premise_evidence_refs", JSONArray(item.premiseEvidenceRefs))
                .put("evidence", evidence)
                .put("last_updated_turn_id", item.lastUpdatedTurnId))
        }
        return JSONObject()
            .put("schema", SNAPSHOT_SCHEMA)
            .put("observed_turn_ids", JSONArray(snapshot.observedTurnIds))
            .put("hypotheses", hypotheses)
            .toString()
    }

    private fun decode(payload: String): ANS100HypothesisRevisionEngine.Snapshot {
        val root = JSONObject(payload)
        require(root.getString("schema") == SNAPSHOT_SCHEMA)
        val rows = root.getJSONArray("hypotheses")
        require(rows.length() <= ANS100HypothesisRevisionEngine.MAX_HYPOTHESES_HARD_LIMIT)
        val hypotheses = (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val evidenceRows = row.getJSONArray("evidence")
            require(evidenceRows.length() <= ANS100HypothesisRevisionEngine.MAX_EVIDENCE_PER_HYPOTHESIS)
            val evidence = (0 until evidenceRows.length()).map { evidenceIndex ->
                val record = evidenceRows.getJSONObject(evidenceIndex)
                ANS100HypothesisRevisionEngine.EvidenceRecord(
                    evidenceRef = record.getString("evidence_ref"),
                    source = ANS100ConceptMemory.LearningSource.valueOf(record.getString("source")),
                    supports = record.getBoolean("supports"),
                )
            }
            ANS100HypothesisRevisionEngine.PersistedHypothesis(
                hypothesisId = row.getString("hypothesis_id"),
                fromConceptId = row.getString("from_concept_id"),
                relationType = ANS100ConceptMemory.RelationType.valueOf(row.getString("relation_type")),
                toConceptId = row.getString("to_concept_id"),
                premiseRelationIds = readStrings(row.getJSONArray("premise_relation_ids"), MAX_PREMISES),
                premiseEvidenceRefs = readStrings(row.getJSONArray("premise_evidence_refs"), MAX_PREMISES),
                evidence = evidence,
                lastUpdatedTurnId = row.getString("last_updated_turn_id"),
            )
        }
        return ANS100HypothesisRevisionEngine.Snapshot(
            observedTurnIds = readStrings(
                root.getJSONArray("observed_turn_ids"),
                ANS100HypothesisRevisionEngine.MAX_REMEMBERED_TURNS,
            ),
            hypotheses = hypotheses,
        )
    }

    private fun readStrings(array: JSONArray, maximum: Int): List<String> {
        require(array.length() <= maximum)
        return (0 until array.length()).map { array.getString(it) }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "ans100_hypothesis_revisions.db"
        const val SNAPSHOT_SCHEMA = "ANS100_HYPOTHESIS_REVISION_SNAPSHOT_V1"
        const val MAX_SNAPSHOT_CHARS = 512 * 1024
        private const val DATABASE_VERSION = 1
        private const val MAX_PREMISES = ANS100HypothesisRevisionEngine.MAX_PREMISES
    }
}
