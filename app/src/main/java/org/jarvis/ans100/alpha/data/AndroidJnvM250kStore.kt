package org.jarvis.ans100.alpha.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Closeable

data class AndroidJnvM250kStats(
    val neuronCount: Int,
    val connectionCount: Int,
    val initialized: Boolean,
)

/**
 * Independent disk-backed structural store for the 250k sparse profile.
 *
 * It is intentionally not wired into AndroidSparseNeuronNetwork yet: legacy
 * dialogue/hybrid ID layouts and their learning tables remain isolated until
 * the scalable event scheduler and topology contract are integrated.
 */
class AndroidJnvM250kStore(
    context: Context,
    databaseName: String = AndroidJnvM250kProfile.DATABASE_NAME,
) : Closeable {
    private val helper: StoreDb

    init {
        AndroidJnvM250kProfile.requireDatabaseName(databaseName)
        require(databaseName != AndroidSparseNeuronNetwork.DEFAULT_DATABASE_NAME) {
            "250k profile must never share the legacy JNVM database"
        }
        helper = StoreDb(context.applicationContext, databaseName)
    }

    /**
     * Creates the complete neuron ID range in bounded transactions. A partial
     * initialization can safely resume because neuron IDs are inserted with
     * conflict-ignore semantics and profile metadata is checked on every call.
     */
    @Synchronized
    fun initialize(): AndroidJnvM250kStats {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val existingProfile = db.rawQuery(
                "SELECT value FROM metadata WHERE key='profile_id'",
                null,
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (existingProfile != null && existingProfile != AndroidJnvM250kProfile.PROFILE_ID) {
                throw AndroidNeuronNetworkException("250k JNVM database belongs to a different profile")
            }
            val values = ContentValues().apply {
                put("key", "profile_id")
                put("value", AndroidJnvM250kProfile.PROFILE_ID)
            }
            db.insertWithOnConflict("metadata", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            val capacity = ContentValues().apply {
                put("key", "neuron_capacity")
                put("value", AndroidJnvM250kProfile.NEURON_CAPACITY.toString())
            }
            db.insertWithOnConflict("metadata", null, capacity, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        var firstId = 0
        while (firstId < AndroidJnvM250kProfile.NEURON_CAPACITY) {
            val endExclusive = minOf(
                firstId + AndroidJnvM250kProfile.INITIALIZATION_BATCH_SIZE,
                AndroidJnvM250kProfile.NEURON_CAPACITY,
            )
            db.beginTransaction()
            try {
                val row = ContentValues()
                for (neuronId in firstId until endExclusive) {
                    row.clear()
                    row.put("neuron_id", neuronId)
                    row.put("potential", 0.0)
                    row.put("adaptation", 0.0)
                    row.put("last_update_ns", 0L)
                    row.put("refractory_until_ns", 0L)
                    row.put("spike_sequence", 0L)
                    row.put("core_tainted", 0)
                    db.insertWithOnConflict("neurons", null, row, SQLiteDatabase.CONFLICT_IGNORE)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            firstId = endExclusive
        }

        db.beginTransaction()
        try {
            val count = scalar(db, "SELECT COUNT(*) FROM neurons")
            if (count != AndroidJnvM250kProfile.NEURON_CAPACITY.toLong()) {
                throw AndroidNeuronNetworkException("250k JNVM initialization ended with an incomplete neuron range")
            }
            db.insertWithOnConflict(
                "metadata",
                null,
                ContentValues().apply {
                    put("key", "initialized")
                    put("value", "1")
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return stats()
    }

    @Synchronized
    fun registerConnection(connection: AndroidNeuronConnection) {
        AndroidJnvM250kProfile.requireNeuronId(connection.sourceNeuronId)
        AndroidJnvM250kProfile.requireNeuronId(connection.targetNeuronId)
        requireInitialized()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val outgoing = scalar(
                db,
                "SELECT COUNT(*) FROM connections WHERE source_id=?",
                arrayOf(connection.sourceNeuronId.toString()),
            )
            val incoming = scalar(
                db,
                "SELECT COUNT(*) FROM connections WHERE target_id=?",
                arrayOf(connection.targetNeuronId.toString()),
            )
            if (outgoing >= AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON) {
                throw AndroidNeuronBackpressureException("source neuron reached the sparse outgoing-degree limit")
            }
            if (incoming >= AndroidJnvM250kProfile.MAX_INCOMING_CONNECTIONS_PER_NEURON) {
                throw AndroidNeuronBackpressureException("target neuron reached the sparse incoming-degree limit")
            }
            val values = ContentValues().apply {
                put("connection_id", connection.connectionId)
                put("source_id", connection.sourceNeuronId)
                put("target_id", connection.targetNeuronId)
                put("delay_ns", connection.delayNs)
                put("weight", connection.weight)
            }
            if (db.insertWithOnConflict("connections", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                throw AndroidNeuronNetworkException("connection ID already exists in the 250k profile")
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun updateNeuron(state: AndroidNeuronState) {
        AndroidJnvM250kProfile.requireNeuronId(state.neuronId)
        requireInitialized()
        val values = ContentValues().apply {
            put("potential", state.potential)
            put("adaptation", state.adaptation)
            put("last_update_ns", state.lastUpdateNs)
            put("refractory_until_ns", state.refractoryUntilNs)
            put("spike_sequence", state.spikeSequence)
            put("core_tainted", if (state.coreTainted) 1 else 0)
        }
        if (helper.writableDatabase.update(
                "neurons",
                values,
                "neuron_id=?",
                arrayOf(state.neuronId.toString()),
            ) != 1
        ) throw AndroidNeuronNetworkException("250k profile neuron state update did not affect exactly one row")
    }

    /** Returns a bounded page ordered by neuron ID; no full-table materialization. */
    @Synchronized
    fun loadNeuronPage(afterNeuronId: Int = -1, limit: Int = AndroidJnvM250kProfile.DEFAULT_PAGE_SIZE): List<AndroidNeuronState> {
        AndroidJnvM250kProfile.requirePageSize(limit)
        require(afterNeuronId in -1 until AndroidJnvM250kProfile.NEURON_CAPACITY)
        requireInitialized()
        val result = ArrayList<AndroidNeuronState>(limit)
        helper.readableDatabase.rawQuery(
            "SELECT neuron_id, potential, adaptation, last_update_ns, refractory_until_ns, spike_sequence, core_tainted " +
                "FROM neurons WHERE neuron_id>? ORDER BY neuron_id LIMIT ?",
            arrayOf(afterNeuronId.toString(), limit.toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += readState(cursor)
        }
        return result
    }

    /** Returns one neuron's outgoing edges using bounded keyset pagination. */
    @Synchronized
    fun outgoingPage(
        sourceNeuronId: Int,
        afterConnectionId: String? = null,
        limit: Int = AndroidJnvM250kProfile.DEFAULT_PAGE_SIZE,
    ): List<AndroidNeuronConnection> {
        AndroidJnvM250kProfile.requireNeuronId(sourceNeuronId)
        AndroidJnvM250kProfile.requirePageSize(limit)
        requireInitialized()
        val sql = if (afterConnectionId == null) {
            "SELECT connection_id, source_id, target_id, delay_ns, weight FROM connections " +
                "WHERE source_id=? ORDER BY connection_id LIMIT ?"
        } else {
            "SELECT connection_id, source_id, target_id, delay_ns, weight FROM connections " +
                "WHERE source_id=? AND connection_id>? ORDER BY connection_id LIMIT ?"
        }
        val args = if (afterConnectionId == null) {
            arrayOf(sourceNeuronId.toString(), limit.toString())
        } else {
            arrayOf(sourceNeuronId.toString(), afterConnectionId, limit.toString())
        }
        val result = ArrayList<AndroidNeuronConnection>(limit)
        helper.readableDatabase.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) result += readConnection(cursor)
        }
        return result
    }

    @Synchronized
    fun stats(): AndroidJnvM250kStats {
        val db = helper.readableDatabase
        val count = scalar(db, "SELECT COUNT(*) FROM neurons").toInt()
        val connections = scalar(db, "SELECT COUNT(*) FROM connections").toInt()
        val initialized = db.rawQuery(
            "SELECT value FROM metadata WHERE key='initialized'",
            null,
        ).use { it.moveToFirst() && it.getString(0) == "1" }
        return AndroidJnvM250kStats(count, connections, initialized)
    }

    private fun requireInitialized() {
        if (!stats().initialized) {
            throw AndroidNeuronNetworkException("250k JNVM profile must be initialized before use")
        }
    }

    override fun close() = helper.close()

    private class StoreDb(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE neurons (neuron_id INTEGER PRIMARY KEY, potential REAL NOT NULL, adaptation REAL NOT NULL, " +
                    "last_update_ns INTEGER NOT NULL, refractory_until_ns INTEGER NOT NULL, spike_sequence INTEGER NOT NULL, " +
                    "core_tainted INTEGER NOT NULL DEFAULT 0)",
            )
            db.execSQL(
                "CREATE TABLE connections (connection_id TEXT PRIMARY KEY, source_id INTEGER NOT NULL, target_id INTEGER NOT NULL, " +
                    "delay_ns INTEGER NOT NULL, weight REAL NOT NULL, " +
                    "FOREIGN KEY(source_id) REFERENCES neurons(neuron_id), FOREIGN KEY(target_id) REFERENCES neurons(neuron_id))",
            )
            db.execSQL("CREATE INDEX connections_source_idx ON connections(source_id, connection_id)")
            db.execSQL("CREATE INDEX connections_target_idx ON connections(target_id)")
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            throw AndroidNeuronNetworkException("unsupported 250k JNVM schema migration: $oldVersion -> $newVersion")
        }

        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            db.setForeignKeyConstraintsEnabled(true)
        }

        companion object {
            private const val DATABASE_VERSION = 1
        }
    }

    companion object {
        private fun readState(cursor: Cursor) = AndroidNeuronState(
            neuronId = cursor.getInt(0),
            potential = cursor.getDouble(1),
            adaptation = cursor.getDouble(2),
            lastUpdateNs = cursor.getLong(3),
            refractoryUntilNs = cursor.getLong(4),
            spikeSequence = cursor.getLong(5),
            coreTainted = cursor.getInt(6) != 0,
        )

        private fun readConnection(cursor: Cursor) = AndroidNeuronConnection(
            connectionId = cursor.getString(0),
            sourceNeuronId = cursor.getInt(1),
            targetNeuronId = cursor.getInt(2),
            delayNs = cursor.getLong(3),
            weight = cursor.getDouble(4),
        )

        private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String> = emptyArray()): Long =
            db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }
}
