package org.jarvis.ans100.alpha.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Small durable checkpoint for the neural hypothesis lifecycle.
 *
 * It stores only bounded concept identifiers/labels and turn identifiers.
 * Raw user messages, generated answers and evaluator labels are never stored.
 */
class ANS100NeuralHypothesisLedger(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE hypothesis_checkpoint (
                singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),
                schema TEXT NOT NULL,
                payload TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // This database is introduced at V1; future versions must add migrations.
        if (oldVersion < 1) onCreate(db)
    }

    @Synchronized
    fun load(): ANS100NeuralHypothesisEngine.Snapshot? {
        val payload = readableDatabase.rawQuery(
            "SELECT schema, payload FROM hypothesis_checkpoint WHERE singleton_id=1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            if (cursor.getString(0) != SNAPSHOT_SCHEMA) return null
            cursor.getString(1)
        }
        if (payload.length > MAX_SNAPSHOT_CHARS) return null
        return runCatching { decode(payload) }.getOrNull()
    }

    @Synchronized
    fun save(snapshot: ANS100NeuralHypothesisEngine.Snapshot): Boolean {
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
                    "hypothesis_checkpoint",
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

    private fun encode(snapshot: ANS100NeuralHypothesisEngine.Snapshot): String {
        val hypotheses = JSONArray()
        snapshot.hypotheses.forEach { item ->
            hypotheses.put(JSONObject()
                .put("hypothesis_id", item.hypothesisId)
                .put("first_concept_id", item.firstConceptId)
                .put("first_label", item.firstLabel)
                .put("second_concept_id", item.secondConceptId)
                .put("second_label", item.secondLabel)
                .put("supporting_turn_ids", JSONArray(item.supportingTurnIds))
                .put("non_reproducing_turn_ids", JSONArray(item.nonReproducingTurnIds))
                .put("shared_target_count", item.sharedTargetCount)
                .put("heuristic_evidence", item.heuristicEvidence))
        }
        return JSONObject()
            .put("schema", SNAPSHOT_SCHEMA)
            .put("hypotheses", hypotheses)
            .put("observed_turn_ids", JSONArray(snapshot.observedTurnIds))
            .toString()
    }

    private fun decode(payload: String): ANS100NeuralHypothesisEngine.Snapshot {
        val root = JSONObject(payload)
        require(root.getString("schema") == SNAPSHOT_SCHEMA)
        val rows = root.getJSONArray("hypotheses")
        require(rows.length() <= ANS100NeuralHypothesisEngine.MAX_HYPOTHESES_HARD_LIMIT)
        val hypotheses = ArrayList<ANS100NeuralHypothesisEngine.PersistedHypothesis>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            hypotheses += ANS100NeuralHypothesisEngine.PersistedHypothesis(
                hypothesisId = row.getString("hypothesis_id"),
                firstConceptId = row.getString("first_concept_id"),
                firstLabel = row.getString("first_label"),
                secondConceptId = row.getString("second_concept_id"),
                secondLabel = row.getString("second_label"),
                supportingTurnIds = readStrings(row.getJSONArray("supporting_turn_ids")),
                nonReproducingTurnIds = readStrings(row.getJSONArray("non_reproducing_turn_ids")),
                sharedTargetCount = row.getInt("shared_target_count"),
                heuristicEvidence = row.optBoolean("heuristic_evidence", false),
            )
        }
        return ANS100NeuralHypothesisEngine.Snapshot(
            hypotheses = hypotheses,
            observedTurnIds = readStrings(root.getJSONArray("observed_turn_ids")),
        )
    }

    private fun readStrings(array: JSONArray): List<String> {
        require(array.length() <= MAX_TURN_IDS_PER_COLLECTION)
        return (0 until array.length()).map { array.getString(it) }
    }

    companion object {
        const val DEFAULT_DATABASE_NAME = "ans100_neural_hypotheses.db"
        const val SNAPSHOT_SCHEMA = "ANS100_NEURAL_HYPOTHESIS_SNAPSHOT_V1"
        const val MAX_SNAPSHOT_CHARS = 512 * 1024
        private const val DATABASE_VERSION = 1
        private const val MAX_TURN_IDS_PER_COLLECTION = 512
    }
}
