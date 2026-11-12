# JARVIS Task & Session JPVR Checkpoint Store V1

**Status:** implemented as a bounded Python reference adapter/store; focused tests authored, not run. This is durable snapshot persistence through the JPVR reference storage layer, not a production scheduler or production NCC service.

## Record contract

- Record type: `0x0104`; schema tag `JTSL`; schema version 1.
- Object identity is the first 16 bytes of SHA-256 over a domain-separated UTF-8 manager ID.
- The payload is the canonical `TaskSessionManager.encode_snapshot()` document. Restore reuses the manager's strict V1 decoder.
- Payloads are stored as one DATA record when they fit the JPVR small-record bound, otherwise as a contiguous BLOB record.
- The fixed-size `JTSC` V1 CHECKPOINT descriptor binds manager object ID, record version, storage kind, page range, generation, payload length and CRC32C.

## Atomic publication and recovery

The record and CHECKPOINT descriptor are planned and published in one typed JPVR transaction. A successful save is visible only through the new Superblock/MANIFEST checkpoint pointer. Load checks pointer agreement, descriptor schema, source COMMIT ancestry, transaction identity, record page metadata, payload length/CRC, and then strict snapshot restoration.

`expected_record_version` provides compare-and-swap. A stale caller is rejected. The JPVR storage commit also protects publication against a stale Superblock snapshot. The store is single-writer at the application level; this does not establish a multi-process lock protocol.

## Important deployment boundary

**Use a dedicated JPVR container for TaskSessionManager V1.** MANIFEST has one active CHECKPOINT pointer. This store must not share a container with the standalone NetworkRuntime, RewardBaseline, or composite Runtime checkpoint stores: a save would replace the active pointer. A future unified JARVIS checkpoint must use a new composite descriptor that references all components and publishes them together.

The checkpoint persists task/session catalog, statuses, revisions, idempotency map (reconstructed from task records), and lifecycle event journal. Reopening the JPVR file restores this snapshot. It does not persist an external tool's side effects, scheduler leases, authorization grants, CQE inbox/outbox, or an in-flight external operation. Recovery therefore restores the last committed registry state; it cannot claim exactly-once execution of external actions.

## Bounds and verification

The TaskSessionManager's configured limits and 4 MiB snapshot cap remain in force. JPVR additionally enforces its logical record and BLOB page bounds. The descriptor and payload are versioned; unknown descriptor/schema versions fail closed.

Tests in `tools.neuron_runtime.test_task_session_store` cover descriptor round-trip/version rejection, save/load, file reopen, CAS conflict, identity isolation, empty-container CAS, corrupted record rejection, and DATA/BLOB selection. Tests are authored, not run. No Python execution, import/syntax check, fault injection, Android/Termux I/O test, benchmark, build, CI, GitHub Actions, APK or AAB was run.
