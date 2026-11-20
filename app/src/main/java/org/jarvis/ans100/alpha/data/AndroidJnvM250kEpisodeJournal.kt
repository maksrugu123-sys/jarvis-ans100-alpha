package org.jarvis.ans100.alpha.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable

/**
 * Small durable journal for the bounded active reward episode.
 *
 * It deliberately uses a separate database: reward application remains
 * idempotent in the JNVM store, while this journal makes an interrupted
 * episode recoverable without holding its candidate map only in RAM.
 */
class AndroidJnvM250kEpisodeJournal(context: Context) : Closeable {
    private val helper = JournalDb(context.applicationContext)

    data class Record(
        val episodeId: String,
        val payload: String,
        val state: String,
        val reward: Double?,
    )

    @Synchronized
    fun begin(episodeId: String, payload: String) {
        requireSafeEpisodeId(episodeId)
        require(payload.length <= MAX_PAYLOAD_CHARS)
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val active = db.rawQuery(
                "SELECT COUNT(*) FROM episodes WHERE state IN ('OPEN','REWARD_PENDING')",
                null,
            ).use { it.moveToFirst() && it.getLong(0) > 0L }
            check(!active) { "an episode is already active in the durable journal" }
            // Retain only a bounded recent history of completed/aborted episodes.
            db.execSQL(
                "DELETE FROM episodes WHERE state IN ('FINISHED','ABORTED','REJECTED') " +
                    "AND episode_id NOT IN (SELECT episode_id FROM episodes " +
                    "WHERE state IN ('FINISHED','ABORTED') ORDER BY rowid DESC LIMIT 999)",
            )
            db.insertOrThrow(
                "episodes",
                null,
                ContentValues().apply {
                    put("episode_id", episodeId)
                    put("payload", payload)
                    put("state", "OPEN")
                    putNull("reward")
                },
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun saveOpen(episodeId: String, payload: String) {
        save(episodeId, payload, "OPEN", null)
    }

    /** Persist reward intent before applying it to the synapse store. */
    @Synchronized
    fun prepareReward(episodeId: String, payload: String, reward: Double) {
        require(reward.isFinite() && reward in -1.0..1.0)
        save(episodeId, payload, "REWARD_PENDING", reward)
    }

    /** Persist a terminal rejected decision without creating a reward receipt. */
    @Synchronized
    fun reject(episodeId: String, payload: String, reward: Double) {
        require(reward.isFinite() && reward in -1.0..1.0)
        requireSafeEpisodeId(episodeId)
        require(payload.length <= MAX_PAYLOAD_CHARS)
        val values = ContentValues().apply {
            put("payload", payload)
            put("state", "REJECTED")
            put("reward", reward)
        }
        val changed = helper.writableDatabase.update(
            "episodes", values, "episode_id=? AND state='OPEN'", arrayOf(episodeId),
        )
        check(changed == 1) { "episode journal rejected-decision transition was rejected" }
    }

    @Synchronized
    fun loadRecoverable(): Record? {
        val db = helper.readableDatabase
        return db.rawQuery(
            "SELECT episode_id, payload, state, reward FROM episodes " +
                "WHERE state IN ('OPEN','REWARD_PENDING') ORDER BY rowid DESC LIMIT 1",
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else Record(
                episodeId = cursor.getString(0),
                payload = cursor.getString(1),
                state = cursor.getString(2),
                reward = if (cursor.isNull(3)) null else cursor.getDouble(3),
            )
        }
    }

    @Synchronized
    fun complete(episodeId: String) {
        transition(episodeId, "REWARD_PENDING", "FINISHED")
    }

    @Synchronized
    fun abort(episodeId: String) {
        transition(episodeId, "OPEN", "ABORTED")
    }

    private fun save(episodeId: String, payload: String, state: String, reward: Double?) {
        requireSafeEpisodeId(episodeId)
        require(payload.length <= MAX_PAYLOAD_CHARS) { "episode journal payload exceeds its bound" }
        val values = ContentValues().apply {
            put("payload", payload)
            put("state", state)
            if (reward == null) putNull("reward") else put("reward", reward)
        }
        val changed = helper.writableDatabase.update(
            "episodes", values, "episode_id=? AND state IN ('OPEN','REWARD_PENDING')",
            arrayOf(episodeId),
        )
        check(changed == 1) { "episode journal record is missing or no longer active" }
    }

    private fun transition(episodeId: String, expected: String, next: String) {
        requireSafeEpisodeId(episodeId)
        val changed = helper.writableDatabase.update(
            "episodes",
            ContentValues().apply { put("state", next) },
            "episode_id=? AND state=?",
            arrayOf(episodeId, expected),
        )
        check(changed == 1) { "episode journal state transition was rejected" }
    }

    override fun close() = helper.close()

    private class JournalDb(context: Context) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE episodes (" +
                    "episode_id TEXT PRIMARY KEY, payload TEXT NOT NULL, " +
                    "state TEXT NOT NULL CHECK(state IN ('OPEN','REWARD_PENDING','FINISHED','ABORTED','REJECTED')), " +
                    "reward REAL)",
            )
            db.execSQL("CREATE INDEX episodes_state_idx ON episodes(state)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2 && newVersion >= 2) {
                db.execSQL(
                    "CREATE TABLE episodes_v2 (" +
                        "episode_id TEXT PRIMARY KEY, payload TEXT NOT NULL, " +
                        "state TEXT NOT NULL CHECK(state IN ('OPEN','REWARD_PENDING','FINISHED','ABORTED','REJECTED')), " +
                        "reward REAL)",
                )
                db.execSQL(
                    "INSERT INTO episodes_v2 (episode_id,payload,state,reward) " +
                        "SELECT episode_id,payload,state,reward FROM episodes",
                )
                db.execSQL("DROP TABLE episodes")
                db.execSQL("ALTER TABLE episodes_v2 RENAME TO episodes")
                db.execSQL("CREATE INDEX episodes_state_idx ON episodes(state)")
            }
            if (newVersion > DATABASE_VERSION) error("unsupported episode journal schema upgrade")
        }

        companion object {
            private const val DATABASE_NAME = "jarvis_ans100_episode_journal_v1.db"
            private const val DATABASE_VERSION = 2
        }
    }

    companion object {
        const val MAX_PAYLOAD_CHARS = 512 * 1024
        private val SAFE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
        private fun requireSafeEpisodeId(id: String) {
            require(id.matches(SAFE_ID)) { "episode ID must be a safe stable identifier" }
        }
    }
}
