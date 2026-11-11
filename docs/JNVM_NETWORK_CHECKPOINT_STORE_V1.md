# JNVM↔JPVR Multi-Neuron Network Checkpoint Store V1

## Status

Implemented as bounded Python reference serialization and file-backed JPVR save/load. The codec has 11 focused test methods and the store has 14; both suites are **AUTHORED, NOT RUN**. This is not production JNVM/NCC and has not been verified on Android storage.

## Scope

The existing NetworkRuntime is an in-memory bounded event loop. This contract adds an explicit checkpoint boundary that captures the entire authoritative in-memory network:
- all logical neuron IDs and complete NeuronRuntime states/configuration;
- all directed NetworkConnection routes;
- all pending external and internally propagated events, including event IDs, targets, timestamps, strength and attention;
- the queue and per-run limits;
- current network model time and whether a timestamp has been committed;
- one caller-supplied non-zero 128-bit clock_epoch_id shared by all nested neuron snapshots.

The snapshot does not add or alter neuron or plasticity equations.

## JNNW record

- Record type 0x0102, schema ID 0x4A4E4E57, schema version 1.
- Object ID is the first 16 bytes of SHA-256 over the domain-separated prefix JARVIS:JNVM:NETWORK-RUNTIME:V1 followed by strict UTF-8 network_id.
- Record version is a non-zero u64, allocated by the store.
- Payload is canonical UTF-8 JSON, strict field set, no duplicate keys or non-finite constants. Neurons are nested complete V1 one-neuron snapshots represented as canonical lowercase hex. Neurons, connections and pending events are sorted deterministically.
- The payload is stored as one JPVR BLOB logical record. BLOB page count is limited by JPVR format; the materialized page catalog defaults to 4096 pages.

The decoder rejects unknown versions/fields, non-canonical hex, duplicate IDs, invalid clock epoch, mismatched nested neuron identity, invalid connection topology, invalid pending-event ownership, event times older than the network clock, and queue overflow. A restored runtime is newly constructed; the source object is not mutated.

## JNCP CHECKPOINT descriptor

A fixed-size 72-byte JNCP V1 payload is stored inside one PageType.CHECKPOINT envelope. It records:
- network object ID and JNNW record version;
- first BLOB page number and contiguous page count;
- source record generation;
- logical payload length and CRC32C;
- fixed record/schema identity and reserved flags.

The page envelope carries the transaction ID and generation. The BLOB pages must have the same generation and transaction ID as the CHECKPOINT page.

## Atomic save

JPVRNetworkCheckpointStore(storage).save(network_id, runtime, clock_epoch_id=..., expected_record_version=None, max_pages=4096):

1. Validates and encodes the full runtime before writing.
2. On genesis only, initializes the seven required empty INDEX roots in a separate schema COMMIT, matching the existing one-neuron store.
3. Reads the current active snapshot under one shared lock and validates any previous network checkpoint before incrementing its record version.
4. Plans one BLOB record, then a CHECKPOINT descriptor page pointing to its exact BLOB page range.
5. Builds a final typed transaction in which BLOB and CHECKPOINT page share generation/transaction identity and MANIFEST publishes the CHECKPOINT page number.
6. Calls one JPVRStorage.commit() so BLOB, CHECKPOINT, MANIFEST and COMMIT become visible together or not at all.

expected_record_version is an optional compare-and-swap guard; 0 means the caller expects no previous network checkpoint. The container stores one active network ID in the MANIFEST checkpoint pointer. A different network ID cannot silently replace that slot. A later unrelated neuron checkpoint commit inherits the existing checkpoint pointer unchanged.

## Load and integrity

load(network_id, max_pages=4096) obtains one active Superblock/page catalog and validates:
- Superblock and MANIFEST checkpoint pointer equality;
- CHECKPOINT page type, envelope CRC and descriptor schema;
- network object ID;
- the exact historical source COMMIT in the active predecessor chain;
- the source COMMIT transaction ID and its own MANIFEST pointer to this CHECKPOINT page;
- BLOB page range, count, generation, transaction ID, record/schema identity, object ID, record version, payload length and CRC32C;
- nested neuron IDs, clock epoch, topology, queue limits and event references before reconstructing NetworkRuntime.

Malformed or missing state is rejected. Load never truncates or rewrites the source file.

## Explicit limits

- One active network checkpoint per JPVR container; this is not a network INDEX.
- Snapshot catalog is materialized and bounded; increasing max_pages increases RAM requirements.
- Model clock epoch continuity remains caller/scheduler-owned. The store preserves the supplied epoch and model timestamps but does not rebase clocks.
- Snapshot save is an explicit boundary. The caller must prevent network mutation during export; the reference runtime is single-threaded and has no concurrent producer protocol.
- Pending event IDs are persisted, but IDs for already-consumed events are not retained forever as durable idempotency keys.
- External R-STDP reward-baseline manager state, CQE queues/cursors, sessions, side effects and cross-timestamp transactional execution are not included.
- No Android flash/FUSE or hardware power-loss behavior, resource performance, or production NCC guarantees have been measured.

## Tests

    python3 -m unittest tools.neuron_runtime.test_network_adapter -v
    python3 -m unittest tools.neuron_runtime.test_network_store -v

The 11 codec tests and 14 store tests are **AUTHORED, NOT RUN**. No tests, import/syntax checks, builds, CI, Android I/O or device benchmarks were executed for V1.
