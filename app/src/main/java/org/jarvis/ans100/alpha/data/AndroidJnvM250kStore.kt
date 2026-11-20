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
    val pendingEvents: Int,
    val currentTimeNs: Long,
    val initialized: Boolean,
)

/**
 * Independent disk-backed structural store for the 250k sparse profile.
 *
 * It is consumed by AndroidJnvM250kRuntime, while the legacy
 * AndroidSparseNeuronNetwork remains isolated on its existing 10k layouts.
 * The 250k runtime currently provides LIF-DT execution and fixed-weight signal
 * propagation; its learning tables and cognitive lifecycle are not integrated.
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
            val existingCapacity = db.rawQuery(
                "SELECT value FROM metadata WHERE key='neuron_capacity'",
                null,
            ).use { if (it.moveToFirst()) it.getString(0).toIntOrNull() else null }
            if (existingCapacity != null && existingCapacity != AndroidJnvM250kProfile.NEURON_CAPACITY) {
                throw AndroidNeuronNetworkException("250k JNVM database capacity metadata does not match this profile")
            }
            val existingVersion = db.rawQuery(
                "SELECT value FROM metadata WHERE key='profile_version'",
                null,
            ).use { if (it.moveToFirst()) it.getString(0).toIntOrNull() else null }
            if (existingVersion != null && existingVersion != AndroidJnvM250kProfile.PROFILE_VERSION) {
                throw AndroidNeuronNetworkException("unsupported 250k JNVM profile version")
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
            db.insertWithOnConflict(
                "metadata",
                null,
                ContentValues().apply {
                    put("key", "profile_version")
                    put("value", AndroidJnvM250kProfile.PROFILE_VERSION.toString())
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        val alreadyInitialized = db.rawQuery(
            "SELECT value FROM metadata WHERE key='initialized'",
            null,
        ).use { it.moveToFirst() && it.getString(0) == "1" }
        if (alreadyInitialized &&
            scalar(db, "SELECT COUNT(*) FROM neurons") == AndroidJnvM250kProfile.NEURON_CAPACITY.toLong()
        ) return stats()

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
        registerConnections(listOf(connection))
    }

    /** Persists a bounded edge batch in one transaction. */
    @Synchronized
    fun registerConnections(connections: List<AndroidNeuronConnection>) {
        persistConnectionBatch(connections, expectedCheckpoint = null, nextCheckpoint = null)
    }

    /**
     * Atomically commits graph edges and the next generator cursor. A process
     * death can therefore leave neither half of a batch committed.
     *
     * expectedCheckpoint is compared with the persisted cursor in the same
     * transaction, preventing two workers from advancing a stale checkpoint.
     */
    @Synchronized
    fun persistConnectionBatch(
        connections: List<AndroidNeuronConnection>,
        expectedCheckpoint: AndroidJnvM250kInitializationCheckpoint?,
        nextCheckpoint: AndroidJnvM250kInitializationCheckpoint?,
    ) {
        require((expectedCheckpoint == null) == (nextCheckpoint == null)) {
            "expected and next checkpoints must either both be present or both be absent"
        }
        persistConnectionBatchInternal(connections, expectedCheckpoint, nextCheckpoint)
    }

    @Synchronized
    fun loadConnectionInitializationCheckpoint(): AndroidJnvM250kInitializationCheckpoint? {
        val db = helper.readableDatabase
        val values = readInitializerMetadata(db) ?: return null
        return try {
            AndroidJnvM250kInitializationCheckpoint(
                seed = values.getValue("seed").toLong(),
                fanoutPerRoute = values.getValue("fanout").toInt(),
                delayNs = values.getValue("delay_ns").toLong(),
                weight = values.getValue("weight").toDouble(),
                cursor = AndroidJnvM250kInitializationCursor(
                    routeIndex = values.getValue("route").toInt(),
                    sourceNeuronId = values.getValue("source").toInt(),
                    slotIndex = values.getValue("slot").toInt(),
                ),
                completed = values.getValue("completed") == "1",
            )
        } catch (error: Exception) {
            throw AndroidNeuronNetworkException("250k sparse initializer checkpoint is corrupt")
        }
    }

    private fun persistConnectionBatchInternal(
        connections: List<AndroidNeuronConnection>,
        expectedCheckpoint: AndroidJnvM250kInitializationCheckpoint?,
        nextCheckpoint: AndroidJnvM250kInitializationCheckpoint?,
    ) {
        require(connections.isNotEmpty() && connections.size <= AndroidJnvM250kProfile.MAX_PAGE_SIZE) {
            "connection batch must contain 1..\u0024{AndroidJnvM250kProfile.MAX_PAGE_SIZE} edges"
        }
        require(connections.map { it.connectionId }.toSet().size == connections.size) {
            "connection IDs must be unique within a batch"
        }
        connections.forEach { connection ->
            AndroidJnvM250kProfile.requireNeuronId(connection.sourceNeuronId)
            AndroidJnvM250kProfile.requireNeuronId(connection.targetNeuronId)
            AndroidJnvM250kTopology.requireAllowedConnection(connection.sourceNeuronId, connection.targetNeuronId)
        }
        val outgoingAdds = connections.groupingBy { it.sourceNeuronId }.eachCount()
        val incomingAdds = connections.groupingBy { it.targetNeuronId }.eachCount()
        requireInitialized()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            if (nextCheckpoint != null) {
                val persisted = readInitializerMetadata(db)?.let(::checkpointFromMetadata)
                if (persisted != expectedCheckpoint) {
                    throw AndroidNeuronNetworkException("250k sparse initializer checkpoint changed; refusing stale batch")
                }
            }
            for ((sourceId, added) in outgoingAdds) {
                val existing = scalar(db, "SELECT COUNT(*) FROM connections WHERE source_id=?", arrayOf(sourceId.toString()))
                if (existing + added > AndroidJnvM250kProfile.MAX_OUTGOING_CONNECTIONS_PER_NEURON) {
                    throw AndroidNeuronBackpressureException("source neuron reached the sparse outgoing-degree limit")
                }
            }
            for ((targetId, added) in incomingAdds) {
                val existing = scalar(db, "SELECT COUNT(*) FROM connections WHERE target_id=?", arrayOf(targetId.toString()))
                if (existing + added > AndroidJnvM250kProfile.MAX_INCOMING_CONNECTIONS_PER_NEURON) {
                    throw AndroidNeuronBackpressureException("target neuron reached the sparse incoming-degree limit")
                }
            }
            for (connection in connections) {
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
            }
            if (nextCheckpoint != null) writeInitializerMetadata(db, nextCheckpoint)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun checkpointFromMetadata(values: Map<String, String>) =
        try {
            AndroidJnvM250kInitializationCheckpoint(
                seed = values.getValue("seed").toLong(),
                fanoutPerRoute = values.getValue("fanout").toInt(),
                delayNs = values.getValue("delay_ns").toLong(),
                weight = values.getValue("weight").toDouble(),
                cursor = AndroidJnvM250kInitializationCursor(
                    routeIndex = values.getValue("route").toInt(),
                    sourceNeuronId = values.getValue("source").toInt(),
                    slotIndex = values.getValue("slot").toInt(),
                ),
                completed = values.getValue("completed") == "1",
            )
        } catch (error: Exception) {
            throw AndroidNeuronNetworkException("250k sparse initializer checkpoint is corrupt")
        }

    private fun readInitializerMetadata(db: SQLiteDatabase): Map<String, String>? {
        val keys = listOf("seed", "fanout", "delay_ns", "weight", "route", "source", "slot", "completed")
        val values = LinkedHashMap<String, String>()
        var found = 0
        for (key in keys) {
            val value = db.rawQuery(
                "SELECT value FROM metadata WHERE key=?",
                arrayOf("sparse_initializer_v1_$key"),
            ).use { if (it.moveToFirst()) it.getString(0) else null }
            if (value != null) {
                found += 1
                values[key] = value
            }
        }
        if (found == 0) return null
        if (found != keys.size) {
            throw AndroidNeuronNetworkException("250k sparse initializer checkpoint is incomplete")
        }
        return values
    }

    private fun writeInitializerMetadata(
        db: SQLiteDatabase,
        checkpoint: AndroidJnvM250kInitializationCheckpoint,
    ) {
        val cursor = checkpoint.cursor
        val values = mapOf(
            "seed" to checkpoint.seed.toString(),
            "fanout" to checkpoint.fanoutPerRoute.toString(),
            "delay_ns" to checkpoint.delayNs.toString(),
            "weight" to checkpoint.weight.toString(),
            "route" to cursor.routeIndex.toString(),
            "source" to cursor.sourceNeuronId.toString(),
            "slot" to cursor.slotIndex.toString(),
            "completed" to if (checkpoint.completed) "1" else "0",
        )
        for ((key, value) in values) {
            db.insertWithOnConflict(
                "metadata",
                null,
                ContentValues().apply {
                    put("key", "sparse_initializer_v1_$key")
                    put("value", value)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
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

    /**
     * Enqueues a bounded input batch. The persistent queue is larger than a
     * single scheduler page, but no API reads the entire queue into RAM.
     */
    @Synchronized
    fun enqueueEvents(events: List<AndroidNeuronEvent>) {
        require(events.isNotEmpty() && events.size <= AndroidJnvM250kProfile.MAX_EVENTS_PER_ENQUEUE)
        require(events.map { it.eventId }.toSet().size == events.size) {
            "event IDs must be unique within an enqueue batch"
        }
        events.forEach { AndroidJnvM250kProfile.requireNeuronId(it.targetNeuronId) }
        requireInitialized()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val clock = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'").toLong()
            if (events.any { it.timeNs <= clock }) {
                throw AndroidNeuronNetworkException("queued events must be later than the committed 250k model clock")
            }
            val pending = scalar(db, "SELECT COUNT(*) FROM events")
            if (pending + events.size > AndroidJnvM250kProfile.MAX_PENDING_EVENTS) {
                throw AndroidNeuronBackpressureException("250k JNVM pending-event queue is full")
            }
            for ((timeNs, batch) in events.groupBy { it.timeNs }) {
                val persistedAtTime = scalar(
                    db,
                    "SELECT COUNT(*) FROM events WHERE time_ns=?",
                    arrayOf(timeNs.toString()),
                )
                if (persistedAtTime + batch.size > AndroidJnvM250kProfile.MAX_EVENTS_PER_TIMESTAMP) {
                    throw AndroidNeuronBackpressureException("250k JNVM timestamp group exceeds the atomic processing bound")
                }
            }
            for (event in events) {
                val values = ContentValues().apply {
                    put("event_id", event.eventId)
                    put("time_ns", event.timeNs)
                    put("target_id", event.targetNeuronId)
                    put("strength", event.strength)
                    put("core_tainted", if (event.coreTainted) 1 else 0)
                }
                if (db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    throw AndroidNeuronNetworkException("250k JNVM event ID already exists")
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /**
     * Reads only the earliest timestamp group. Oversized groups fail closed
     * instead of being split into partial timestamp commits.
     */
    @Synchronized
    fun nextTimestampEvents(): List<AndroidNeuronEvent>? {
        requireInitialized()
        val db = helper.readableDatabase
        val nextTime = db.rawQuery("SELECT MIN(time_ns) FROM events", null).use {
            if (!it.moveToFirst() || it.isNull(0)) null else it.getLong(0)
        } ?: return null
        val result = ArrayList<AndroidNeuronEvent>()
        db.rawQuery(
            "SELECT event_id, time_ns, target_id, strength, core_tainted FROM events " +
                "WHERE time_ns=? ORDER BY event_id LIMIT ?",
            arrayOf(nextTime.toString(), (AndroidJnvM250kProfile.MAX_EVENTS_PER_TIMESTAMP + 1).toString()),
        ).use { cursor ->
            while (cursor.moveToNext()) result += readEvent(cursor)
        }
        if (result.size > AndroidJnvM250kProfile.MAX_EVENTS_PER_TIMESTAMP) {
            throw AndroidNeuronBackpressureException("one 250k JNVM timestamp group exceeds the atomic processing bound")
        }
        return result
    }

    /**
     * Commits consumed events, generated events, changed states and the model
     * clock atomically. Callers must process one complete timestamp group.
     */
    @Synchronized
    fun commitTimestamp(
        timestampNs: Long,
        consumed: List<AndroidNeuronEvent>,
        generated: List<AndroidNeuronEvent>,
        changedStates: Collection<AndroidNeuronState>,
    ) {
        require(timestampNs >= 0L)
        require(consumed.isNotEmpty() && consumed.size <= AndroidJnvM250kProfile.MAX_EVENTS_PER_TIMESTAMP)
        require(generated.size <= AndroidJnvM250kProfile.MAX_GENERATED_EVENTS_PER_TIMESTAMP)
        require(consumed.all { it.timeNs == timestampNs })
        require(consumed.map { it.eventId }.toSet().size == consumed.size)
        require(generated.map { it.eventId }.toSet().size == generated.size)
        require(consumed.map { it.eventId }.toSet().intersect(generated.map { it.eventId }.toSet()).isEmpty()) {
            "a timestamp commit cannot recycle a consumed event ID"
        }
        require(changedStates.all { it.lastUpdateNs == timestampNs }) {
            "changed neuron states must be updated at the committed timestamp"
        }
        require(generated.all { it.timeNs > timestampNs }) {
            "generated events must be scheduled after the committed timestamp"
        }
        require(changedStates.map { it.neuronId }.toSet().size == changedStates.size) {
            "timestamp commit cannot contain duplicate neuron states"
        }
        consumed.forEach { AndroidJnvM250kProfile.requireNeuronId(it.targetNeuronId) }
        generated.forEach { AndroidJnvM250kProfile.requireNeuronId(it.targetNeuronId) }
        changedStates.forEach { AndroidJnvM250kProfile.requireNeuronId(it.neuronId) }
        requireInitialized()
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val clock = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'").toLong()
            if (timestampNs <= clock) throw AndroidNeuronNetworkException("250k model clock must advance monotonically")
            val persistedGroupSize = scalar(
                db,
                "SELECT COUNT(*) FROM events WHERE time_ns=?",
                arrayOf(timestampNs.toString()),
            )
            if (persistedGroupSize != consumed.size.toLong()) {
                throw AndroidNeuronNetworkException("250k timestamp commit must consume the complete persisted timestamp group")
            }
            for (event in consumed) {
                if (db.delete(
                        "events",
                        "event_id=? AND time_ns=?",
                        arrayOf(event.eventId, timestampNs.toString()),
                    ) != 1
                ) throw AndroidNeuronNetworkException("250k event disappeared before timestamp commit")
            }
            for (state in changedStates) {
                val values = ContentValues().apply {
                    put("potential", state.potential)
                    put("adaptation", state.adaptation)
                    put("last_update_ns", state.lastUpdateNs)
                    put("refractory_until_ns", state.refractoryUntilNs)
                    put("spike_sequence", state.spikeSequence)
                    put("core_tainted", if (state.coreTainted) 1 else 0)
                }
                if (db.update("neurons", values, "neuron_id=?", arrayOf(state.neuronId.toString())) != 1) {
                    throw AndroidNeuronNetworkException("250k neuron disappeared before timestamp commit")
                }
            }
            val remaining = scalar(db, "SELECT COUNT(*) FROM events")
            if (remaining + generated.size > AndroidJnvM250kProfile.MAX_PENDING_EVENTS) {
                throw AndroidNeuronBackpressureException("generated events exceed the 250k pending-event bound")
            }
            for (event in generated) {
                val values = ContentValues().apply {
                    put("event_id", event.eventId)
                    put("time_ns", event.timeNs)
                    put("target_id", event.targetNeuronId)
                    put("strength", event.strength)
                    put("core_tainted", if (event.coreTainted) 1 else 0)
                }
                if (db.insertWithOnConflict("events", null, values, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                    throw AndroidNeuronNetworkException("generated 250k event ID already exists")
                }
            }
            if (db.update(
                    "metadata",
                    ContentValues().apply { put("value", timestampNs.toString()) },
                    "key='model_time_ns'",
                    null,
                ) != 1
            ) throw AndroidNeuronNetworkException("250k model clock metadata is missing")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    fun stats(): AndroidJnvM250kStats {
        val db = helper.readableDatabase
        val count = scalar(db, "SELECT COUNT(*) FROM neurons").toInt()
        val connections = scalar(db, "SELECT COUNT(*) FROM connections").toInt()
        val pending = scalar(db, "SELECT COUNT(*) FROM events").toInt()
        val currentTime = scalar(db, "SELECT value FROM metadata WHERE key='model_time_ns'").toLong()
        val initialized = db.rawQuery(
            "SELECT value FROM metadata WHERE key='initialized'",
            null,
        ).use { it.moveToFirst() && it.getString(0) == "1" }
        return AndroidJnvM250kStats(count, connections, pending, currentTime, initialized)
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
            db.execSQL(
                "CREATE TABLE events (event_id TEXT PRIMARY KEY, time_ns INTEGER NOT NULL, target_id INTEGER NOT NULL, " +
                    "strength REAL NOT NULL, core_tainted INTEGER NOT NULL DEFAULT 0, " +
                    "FOREIGN KEY(target_id) REFERENCES neurons(neuron_id))",
            )
            db.execSQL("CREATE INDEX events_time_idx ON events(time_ns, event_id)")
            db.execSQL("CREATE TABLE metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("INSERT INTO metadata(key, value) VALUES('model_time_ns', '0')")
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

        private fun readEvent(cursor: Cursor) = AndroidNeuronEvent(
            eventId = cursor.getString(0),
            timeNs = cursor.getLong(1),
            targetNeuronId = cursor.getInt(2),
            strength = cursor.getDouble(3),
            coreTainted = cursor.getInt(4) != 0,
        )

        private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String> = emptyArray()): Long =
            db.rawQuery(sql, args).use { if (it.moveToFirst()) it.getLong(0) else 0L }
    }
}
