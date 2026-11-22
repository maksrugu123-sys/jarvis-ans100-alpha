package org.jarvis.ans100.alpha.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Durable write-ahead journal for explicit hypothesis-review decisions. */
class ANS100HypothesisManualReviewLedger(
    context: Context,
    databaseName: String = DEFAULT_DATABASE_NAME,
    private val decisionValidator: ANS100HypothesisManualReviewDecision = ANS100HypothesisManualReviewDecision(),
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    enum class State { PENDING, APPLIED, RECORDED_NO_EVIDENCE }
    data class SourceSnapshot(
        val canonicalUrl: String, val host: String, val pageContentSha256: String,
        val searchLeadRef: String, val assessment: String,
    )
    data class PassageSnapshot(
        val passageRef: String, val excerpt: String, val sentenceSha256: String,
        val polarity: String, val polarityReason: String, val sourceAssessment: String,
        val sourceUrl: String, val pageContentSha256: String,
    )
    data class ReviewSnapshot(
        val hypothesisId: String, val pageTitle: String, val pageUrl: String,
        val pageContentSha256: String, val fetchedAtEpochMs: Long,
        val sources: List<SourceSnapshot>, val passages: List<PassageSnapshot>,
        val warnings: List<String>,
    )
    data class Entry(
        val decision: ANS100HypothesisManualReviewDecision.Decision,
        val evidence: ANS100ConceptMemory.RelationEvidence?,
        val state: State,
        val snapshot: ReviewSnapshot? = null,
    )
    data class PendingBatch(val entries: List<Entry>, val rejectedEntries: Int)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE manual_hypothesis_review_decisions (
            decision_id TEXT PRIMARY KEY NOT NULL, payload TEXT NOT NULL,
            state TEXT NOT NULL, created_at_ms INTEGER NOT NULL
        )""".trimIndent())
        db.execSQL("CREATE INDEX manual_hypothesis_review_state_idx ON manual_hypothesis_review_decisions(state, created_at_ms)")
        db.execSQL("""CREATE TABLE manual_hypothesis_review_snapshots (
            decision_id TEXT PRIMARY KEY NOT NULL, snapshot_payload TEXT NOT NULL
        )""".trimIndent())
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 1) onCreate(db)
        if (oldVersion < 2) db.execSQL("""CREATE TABLE IF NOT EXISTS manual_hypothesis_review_snapshots (
            decision_id TEXT PRIMARY KEY NOT NULL, snapshot_payload TEXT NOT NULL
        )""".trimIndent())
    }

    @Synchronized
    fun append(
        decision: ANS100HypothesisManualReviewDecision.Decision,
        evidence: ANS100ConceptMemory.RelationEvidence?,
        packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet? = null,
    ): Boolean {
        if (runCatching {
            validate(decision, evidence)
            if (packet != null) decisionValidator.toRelationEvidence(packet, decision)
        }.isFailure) return false
        val payload = encode(decision, evidence)
        val snapshotPayload = packet?.let(::encodeSnapshot)
        if (payload.length > MAX_ENTRY_CHARS || (snapshotPayload?.length ?: 0) > MAX_SNAPSHOT_CHARS) return false
        val db = writableDatabase
        db.beginTransaction()
        try {
            val existing = db.rawQuery("SELECT payload FROM manual_hypothesis_review_decisions WHERE decision_id=?", arrayOf(decision.decisionId))
                .use { if (it.moveToFirst()) it.getString(0) else null }
            if (existing != null) {
                val existingSnapshot = db.rawQuery(
                    "SELECT snapshot_payload FROM manual_hypothesis_review_snapshots WHERE decision_id=?",
                    arrayOf(decision.decisionId),
                ).use { if (it.moveToFirst()) it.getString(0) else null }
                if (existing != payload || existingSnapshot != snapshotPayload) return false
                db.setTransactionSuccessful()
                return true
            }
            val count = db.rawQuery("SELECT COUNT(*) FROM manual_hypothesis_review_decisions", null)
                .use { it.moveToFirst(); it.getInt(0) }
            if (count >= MAX_ENTRIES) return false
            val state = if (evidence == null) State.RECORDED_NO_EVIDENCE else State.PENDING
            val values = ContentValues().apply {
                put("decision_id", decision.decisionId); put("payload", payload)
                put("state", state.name); put("created_at_ms", decision.reviewedAtEpochMs)
            }
            if (db.insert("manual_hypothesis_review_decisions", null, values) == -1L) return false
            if (snapshotPayload != null) {
                val snapshotValues = ContentValues().apply {
                    put("decision_id", decision.decisionId)
                    put("snapshot_payload", snapshotPayload)
                }
                if (db.insert("manual_hypothesis_review_snapshots", null, snapshotValues) == -1L) return false
            }
            db.setTransactionSuccessful()
            return true
        } finally { db.endTransaction() }
    }

    @Synchronized
    fun loadPending(limit: Int = MAX_ENTRIES): List<Entry> =
        loadPendingBatch(limit).entries

    /**
     * Reads valid pending records independently. A malformed row is retained for
     * audit and counted, but cannot block later valid decisions from being replayed.
     */
    @Synchronized
    fun loadPendingBatch(limit: Int = MAX_ENTRIES): PendingBatch {
        require(limit in 0..MAX_ENTRIES)
        if (limit == 0) return PendingBatch(emptyList(), 0)
        return readableDatabase.rawQuery(
            "SELECT payload, state, (SELECT snapshot_payload FROM manual_hypothesis_review_snapshots s WHERE s.decision_id=manual_hypothesis_review_decisions.decision_id) " +
                "FROM manual_hypothesis_review_decisions WHERE state=? ORDER BY created_at_ms, decision_id LIMIT ?",
            arrayOf(State.PENDING.name, limit.toString()),
        ).use { cursor ->
            val valid = mutableListOf<Entry>()
            var rejected = 0
            while (cursor.moveToNext()) {
                val payload = cursor.getString(0)
                val state = runCatching { State.valueOf(cursor.getString(1)) }.getOrNull()
                val entry = if (payload.length <= MAX_ENTRY_CHARS && state != null) {
                    runCatching { decode(payload, state, cursor.getStringOrNull(2)) }.getOrNull()
                } else null
                if (entry == null) rejected++ else valid += entry
            }
            PendingBatch(valid, rejected)
        }
    }

    /** Returns a bounded audit view, including completed and non-evidence decisions. */
    @Synchronized
    fun loadRecent(limit: Int = DEFAULT_HISTORY_LIMIT): List<Entry> {
        require(limit in 0..MAX_ENTRIES)
        if (limit == 0) return emptyList()
        return readableDatabase.rawQuery(
            "SELECT payload, state, (SELECT snapshot_payload FROM manual_hypothesis_review_snapshots s WHERE s.decision_id=manual_hypothesis_review_decisions.decision_id) " +
                "FROM manual_hypothesis_review_decisions ORDER BY created_at_ms DESC, decision_id DESC LIMIT ?",
            arrayOf(limit.toString()),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val payload = cursor.getString(0)
                    if (payload.length > MAX_ENTRY_CHARS) continue
                    val state = runCatching { State.valueOf(cursor.getString(1)) }.getOrNull() ?: continue
                    runCatching { decode(payload, state, cursor.getStringOrNull(2)) }.getOrNull()?.let(::add)
                }
            }
        }
    }

    @Synchronized
    fun markApplied(decisionId: String): Boolean {
        require(DECISION_ID.matches(decisionId))
        val db = writableDatabase
        val current = db.rawQuery("SELECT state FROM manual_hypothesis_review_decisions WHERE decision_id=?", arrayOf(decisionId))
            .use { if (it.moveToFirst()) it.getString(0) else null } ?: return false
        if (current == State.APPLIED.name) return true
        if (current != State.PENDING.name) return false
        val values = ContentValues().apply { put("state", State.APPLIED.name) }
        return db.update("manual_hypothesis_review_decisions", values, "decision_id=? AND state=?",
            arrayOf(decisionId, State.PENDING.name)) == 1
    }

    @Synchronized
    fun isApplied(decisionId: String): Boolean {
        if (!DECISION_ID.matches(decisionId)) return false
        return readableDatabase.rawQuery("SELECT state FROM manual_hypothesis_review_decisions WHERE decision_id=?", arrayOf(decisionId))
            .use { it.moveToFirst() && it.getString(0) == State.APPLIED.name }
    }

    private fun validate(decision: ANS100HypothesisManualReviewDecision.Decision, evidence: ANS100ConceptMemory.RelationEvidence?) {
        require(decisionValidator.isDecisionIntact(decision))
        when (decision.outcome) {
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT,
            ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_CONTRADICTION -> {
                require(evidence != null && decision.selectedPassageRefs.isNotEmpty())
                require(evidence.fromConceptId == decision.fromConceptId && evidence.type == decision.relationType && evidence.toConceptId == decision.toConceptId)
                require(evidence.source == ANS100ConceptMemory.LearningSource.EXPLICIT_USER)
                require(evidence.supports == (decision.outcome == ANS100HypothesisManualReviewDecision.Outcome.CONFIRM_SUPPORT))
                require(evidence.evidenceRef == expectedEvidenceRef(decision))
            }
            ANS100HypothesisManualReviewDecision.Outcome.INSUFFICIENT_EVIDENCE,
            ANS100HypothesisManualReviewDecision.Outcome.SOURCE_UNTRUSTED,
            ANS100HypothesisManualReviewDecision.Outcome.CONFLICT_REQUIRES_REVIEW -> require(evidence == null)
        }
    }

    private fun encode(decision: ANS100HypothesisManualReviewDecision.Decision, evidence: ANS100ConceptMemory.RelationEvidence?): String {
        val refs = JSONArray().apply { decision.selectedPassageRefs.forEach { put(it) } }
        return JSONObject()
            .put("decision_id", decision.decisionId).put("packet_fingerprint", decision.packetFingerprint)
            .put("hypothesis_id", decision.hypothesisId).put("from_concept_id", decision.fromConceptId)
            .put("relation_type", decision.relationType.name).put("to_concept_id", decision.toConceptId)
            .put("outcome", decision.outcome.name).put("selected_passage_refs", refs)
            .put("rationale", decision.rationale).put("reviewed_at_ms", decision.reviewedAtEpochMs)
            .put("decision_digest", decision.decisionDigest)
            .put("evidence", if (evidence == null) JSONObject.NULL else JSONObject()
                .put("from_concept_id", evidence.fromConceptId).put("relation_type", evidence.type.name)
                .put("to_concept_id", evidence.toConceptId).put("supports", evidence.supports)
                .put("source", evidence.source.name).put("evidence_ref", evidence.evidenceRef))
            .toString()
    }

    private fun decode(payload: String, state: State, snapshotPayload: String?): Entry {
        val root = JSONObject(payload)
        val refs = root.getJSONArray("selected_passage_refs")
        require(refs.length() <= ANS100HypothesisManualReviewDecision.MAX_SELECTED_PASSAGES)
        val decision = ANS100HypothesisManualReviewDecision.Decision(
            root.getString("decision_id"), root.getString("packet_fingerprint"), root.getString("hypothesis_id"),
            root.getString("from_concept_id"), ANS100ConceptMemory.RelationType.valueOf(root.getString("relation_type")),
            root.getString("to_concept_id"), ANS100HypothesisManualReviewDecision.Outcome.valueOf(root.getString("outcome")),
            (0 until refs.length()).map { refs.getString(it) }, root.getString("rationale"),
            root.getLong("reviewed_at_ms"), root.getString("decision_digest"),
        )
        val rawEvidence = root.opt("evidence")
        val evidence = if (rawEvidence == null || rawEvidence == JSONObject.NULL) null else {
            val row = rawEvidence as JSONObject
            ANS100ConceptMemory.RelationEvidence(
                row.getString("from_concept_id"), ANS100ConceptMemory.RelationType.valueOf(row.getString("relation_type")),
                row.getString("to_concept_id"), row.getBoolean("supports"),
                ANS100ConceptMemory.LearningSource.valueOf(row.getString("source")), row.getString("evidence_ref"),
            )
        }
        validate(decision, evidence)
        if (evidence == null) {
            require(state == State.RECORDED_NO_EVIDENCE) { "non-evidence decisions must not enter the replay queue" }
        } else {
            require(state == State.PENDING || state == State.APPLIED) { "evidence decisions require a replayable state" }
        }
        val snapshot = snapshotPayload?.takeIf { it.length <= MAX_SNAPSHOT_CHARS }?.let(::decodeSnapshot)
        if (snapshot != null) require(snapshot.hypothesisId == decision.hypothesisId)
        return Entry(decision, evidence, state, snapshot)
    }

    private fun encodeSnapshot(packet: ANS100HypothesisEvidenceReviewPacketBuilder.Packet): String {
        require(packet.passages.size <= ANS100HypothesisEvidenceReviewPacketBuilder.MAX_PASSAGES_HARD_LIMIT)
        require(packet.sources.size <= ANS100HypothesisEvidenceReviewPacketBuilder.MAX_SOURCE_ASSESSMENTS_HARD_LIMIT)
        val sources = JSONArray().apply {
            packet.sources.forEach { source ->
                put(JSONObject().put("url", source.canonicalUrl).put("host", source.host)
                    .put("digest", source.pageContentSha256).put("lead", source.searchLeadRef)
                    .put("assessment", source.assessment.name))
            }
        }
        val passages = JSONArray().apply {
            packet.passages.forEach { passage ->
                put(JSONObject().put("ref", passage.passageRef)
                    .put("excerpt", passage.excerpt.take(ANS100HypothesisPassageExtractor.MAX_EXCERPT_CHARS_HARD_LIMIT))
                    .put("digest", passage.sentenceSha256).put("polarity", passage.polarity.name)
                    .put("reason", passage.polarityReason.name).put("source_assessment", passage.sourceAssessment.name)
                    .put("source_url", passage.sourceUrl).put("page_digest", passage.pageContentSha256))
            }
        }
        val warnings = JSONArray().apply { packet.warnings.map { it.name }.sorted().forEach { put(it) } }
        return JSONObject().put("hypothesis_id", packet.hypothesisId)
            .put("page_title", packet.pageTitle.take(180)).put("page_url", packet.pageUrl.take(2048))
            .put("page_digest", packet.pageContentSha256).put("fetched_at_ms", packet.fetchedAtEpochMs)
            .put("sources", sources).put("passages", passages).put("warnings", warnings).toString()
    }

    private fun decodeSnapshot(payload: String): ReviewSnapshot {
        val root = JSONObject(payload)
        val sourceArray = root.getJSONArray("sources")
        val passageArray = root.getJSONArray("passages")
        val warningArray = root.getJSONArray("warnings")
        require(sourceArray.length() <= ANS100HypothesisEvidenceReviewPacketBuilder.MAX_SOURCE_ASSESSMENTS_HARD_LIMIT)
        require(passageArray.length() <= ANS100HypothesisEvidenceReviewPacketBuilder.MAX_PASSAGES_HARD_LIMIT)
        require(warningArray.length() <= ANS100HypothesisEvidenceReviewPacketBuilder.Warning.values().size)
        val sources = (0 until sourceArray.length()).map { i ->
            val item = sourceArray.getJSONObject(i)
            SourceSnapshot(item.getString("url"), item.getString("host"), item.getString("digest"),
                item.getString("lead"), item.getString("assessment"))
        }
        val passages = (0 until passageArray.length()).map { i ->
            val item = passageArray.getJSONObject(i)
            PassageSnapshot(item.getString("ref"), item.getString("excerpt"), item.getString("digest"),
                item.getString("polarity"), item.getString("reason"), item.getString("source_assessment"),
                item.getString("source_url"), item.getString("page_digest"))
        }
        val warnings = (0 until warningArray.length()).map { warningArray.getString(it) }
        val pageDigest = root.getString("page_digest")
        require(pageDigest.matches(SHA256))
        require(root.getString("page_url").startsWith("https://"))
        require(sources.all {
            it.canonicalUrl.startsWith("https://") && it.canonicalUrl.length <= 2048 &&
                it.host.length <= 253 && it.pageContentSha256.matches(SHA256) &&
                ANS100HypothesisSourceAudit.Assessment.values().any { value -> value.name == it.assessment }
        })
        require(passages.all {
            it.passageRef.isNotBlank() && it.passageRef.length <= ANS100ConceptMemory.MAX_EVIDENCE_REF_CHARS &&
                it.excerpt.length <= ANS100HypothesisPassageExtractor.MAX_EXCERPT_CHARS_HARD_LIMIT &&
                it.sentenceSha256.matches(SHA256) && it.sourceUrl.startsWith("https://") &&
                it.sourceUrl.length <= 2048 && it.pageContentSha256.matches(SHA256) &&
                ANS100HypothesisClaimPolarityAnalyzer.PolarityCandidate.values().any { value -> value.name == it.polarity } &&
                ANS100HypothesisClaimPolarityAnalyzer.Reason.values().any { value -> value.name == it.polarityReason } &&
                ANS100HypothesisSourceAudit.Assessment.values().any { value -> value.name == it.sourceAssessment }
        })
        require(warnings.all { value -> ANS100HypothesisEvidenceReviewPacketBuilder.Warning.values().any { it.name == value } })
        return ReviewSnapshot(root.getString("hypothesis_id"), root.getString("page_title"),
            root.getString("page_url"), pageDigest, root.getLong("fetched_at_ms"),
            sources, passages, warnings)
    }

    private fun android.database.Cursor.getStringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private fun expectedEvidenceRef(d: ANS100HypothesisManualReviewDecision.Decision) =
        "manual-review:" + sha256(d.decisionId + "|" + d.packetFingerprint + "|" + d.outcome.name)
    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val DEFAULT_DATABASE_NAME = "ans100_manual_hypothesis_reviews.db"
        const val MAX_ENTRIES = 512
        const val MAX_ENTRY_CHARS = 16 * 1024
        const val MAX_SNAPSHOT_CHARS = 48 * 1024
        const val DEFAULT_HISTORY_LIMIT = 100
        private const val DATABASE_VERSION = 2
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val DECISION_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$")
    }
}
