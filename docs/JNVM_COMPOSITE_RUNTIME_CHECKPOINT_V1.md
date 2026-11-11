# JNVM Composite Runtime Checkpoint V1

**Status:** bounded Python reference store implemented; 12 focused tests are AUTHORED, NOT RUN. Not Android-device-verified or production NCC.

## Purpose

Standalone NetworkRuntime (JNNW/JNCP) and RewardBaselineManager (JRBM/JRCP) snapshots previously competed for the one active MANIFEST checkpoint pointer. JRCM combines both component locators under one descriptor and publishes both logical records in one JPVR COMMIT.

## JRCM descriptor

- CHECKPOINT magic `JRCM`, version 1, flags 0.
- 16-byte header: magic, version, flags and non-zero u64 runtime_version.
- Then existing 72-byte JNCP and 72-byte JRCP component descriptors (total descriptor size 160 bytes).
- Both components must share the source generation. Each descriptor stores object ID, record version, DATA/BLOB locator, payload length and CRC32C.
- Embedded JNCP/JRCP V1 flags: 0=BLOB; 1=one DATA page. Legacy locator field names remain for API compatibility.

## Atomic save

`JPVRRuntimeCheckpointStore(storage).save(network_id, network, reward_baseline_manager, expected_runtime_version=None)` validates/encodes both components first, initializes indexes on genesis if needed, resolves prior JRCM identity/version, allocates a runtime CAS version and independent component record versions, then builds one typed transaction. Payloads up to 3976 bytes use DATA; larger payloads use BLOB. One CHECKPOINT descriptor references both records. Both records and descriptor share transaction ID/generation and are published by one `JPVRStorage.commit()`.

## Load and validation

`load(network_id, manager_id)` validates active Superblock/MANIFEST; follows COMMIT predecessors to the exact source generation; checks that source COMMIT/MANIFEST published the JRCM CHECKPOINT; validates both DATA JREC or BLOB fragment locators, transaction/generation, schema/object/version, payload length/CRC; strictly decodes both snapshots; and requires equal clock epoch IDs before returning either object. A DATA page may contain both JRECs; each locator resolves the matching logical record.

## Boundaries

Use a **dedicated JPVR container** for JRCM; do not mix it with standalone JNCP/JRCP. This is atomic checkpoint publication, not an atomic reward-processing transaction. It does not combine synapse weight/eligibility mutation, RewardBaselineManager baseline advancement, CQE inbox/outbox or external side effects into one transaction. The manager must not advance the baseline until all related synapse operations are terminal success or approved no-op. Pending proposals survive, but JRCM cannot infer whether an operation was partially applied in RAM immediately before a crash.

Clock epoch remains caller/scheduler-owned. The store is single-writer Python reference code; catalog materialization is bounded (default 4096 pages). Android flash behavior, power-loss guarantees and resource/device performance are unmeasured.

## Tests

- `test_runtime_store.py`: 12 tests for descriptor, atomic save/load, reopen with pending work, CAS, identity isolation, refusing legacy-pointer replacement, DATA and mixed DATA/BLOB, corruption, epoch and preflight.
- `test_network_store.py`: small JNNW DATA round-trip.
- `test_reward_baseline_store.py`: DATA descriptor and larger JRBM BLOB round-trip.

All new/changed tests are **AUTHORED, NOT RUN**.
