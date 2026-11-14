# JARVIS Network JNVM Transaction V1

**Status:** architecture proposal grounded in the current JPVR transaction and NetworkRuntime APIs. The typed builder now exposes logical DATA/BLOB placement maps needed by a future group checkpoint store; group commit and scheduler integration remain NOT IMPLEMENTED. No performance claim.

## 1. Why a network-level transaction is required

The current NeuronWorkingSet is deliberately a per-neuron write-back cache. Its deterministic 128-way shard mapping places different neurons in different JPVR files. A JPVR COMMIT is atomic only inside one container; it cannot atomically publish mutations across those 128 independent files.

NetworkRuntime processes a timestamp as one logical unit:
1. collect all queued events at the timestamp;
2. group inputs by target neuron;
3. calculate candidate state for every affected neuron;
4. derive outgoing events from the candidate spikes;
5. publish all candidate neurons, queue changes and the network clock together.

Committing each neuron separately would expose a partial timestamp after a crash. Publishing outgoing events before all candidate states are durable could also create events from a timestamp whose neuron changes were not committed. Therefore the current per-neuron working set must not be wired directly into NetworkRuntime.

## 2. V1 storage topology

Use one JPVR container per logical network, not 128 independent files.

The container owns:
- a COW neuron INDEX: stable neuron object ID -> immutable versioned neuron checkpoint locator;
- a COW network-state INDEX: network ID -> immutable scheduler checkpoint locator;
- optionally a COW topology INDEX if topology updates are made independently from initial network creation;
- immutable DATA/BLOB records for changed neuron states, scheduler state and topology snapshots;
- one MANIFEST and one COMMIT per published transaction.

A timestamp transaction writes only changed neuron records plus one scheduler checkpoint, and applies all corresponding INDEX mutations in the same JPVR typed transaction. The scheduler checkpoint includes the network clock, has_processed, bounded pending-event queue, event-ID deduplication state, topology version and transaction sequence. The commit's single Superblock publication is the visibility boundary.

The existing JPVR typed transaction builder already accepts multiple DATA/BLOB records and multiple index mutations in one transaction request. The current one-neuron checkpoint facade does not expose that group operation; a dedicated NetworkCheckpointStore must own validation, planning and recovery rather than looping over individual saves.

## 3. Timestamp transaction protocol

### Prepare
- NCC is the single writer. Capture the expected network generation and each touched neuron's record version.
- Read only the required neuron states and parameter bundles into the active working set.
- Sort timestamp events and target neuron IDs using the existing deterministic ordering.
- Calculate all affected neuron candidates without mutating authoritative resident entries.
- Build outgoing routed events from candidates; validate event-ID uniqueness, queue bounds, timestamp overflow and total fan-out before persistence.
- Serialize changed neuron candidates and the complete bounded scheduler checkpoint into immutable record inputs.

### Commit
- Build one JPVR transaction containing all changed neuron records, all corresponding neuron-index mutations and the scheduler-state record/index mutation.
- Validate expected record versions and expected network generation before writing.
- Stage DATA/BLOB pages, COW index pages, CHECKPOINT, MANIFEST and COMMIT in canonical order.
- Publish exactly one Superblock only after the full transaction has been validated and written.
- Treat the returned durable generation and record versions as the committed result.

### Publish in memory
- Only after JPVR confirms the commit, replace authoritative resident neuron states, the pending-event queue, pending-ID set and network clock.
- A crash before the Superblock publication recovers the previous complete timestamp. A crash after publication recovers the new complete timestamp.
- No outgoing event becomes visible independently of the neuron state and scheduler checkpoint that created it.

### Uncertain commit
- If Superblock publication outcome is uncertain, freeze the network writer and do not re-run the timestamp.
- Reopen/recover the same JPVR container and compare the active generation, commit ID, transaction ID and scheduler checkpoint.
- If the transaction is active, adopt its exact committed neuron and scheduler versions. If it is absent and the prior generation is active, retry the same prepared transaction only after proving no conflicting writer advanced the container.
- If a different newer generation is present or the record set cannot be reconciled, fail closed and require recovery; never overwrite by guessing.

## 4. Bounded working set and eviction

- The network's topology/index metadata is separate from resident neuron state.
- The active cache has an explicit measured capacity. Cold neurons load from the single network container; clean least-recently-used entries may be evicted.
- A dirty neuron is not independently flushed. Its candidate joins the next complete timestamp transaction; an explicit checkpoint/close operation commits all pending dirty state and scheduler metadata together.
- Parameter bundles are interned by content identity, but the parameter-set registry/version policy must be persisted and validated before supporting runtime parameter migration.
- Storage backpressure prevents accepting a timestamp whose required records cannot fit within configured transaction and page limits.
- JPVR append-only growth and page-catalog bounds require a compaction/reclamation design before long-running 10k operation can be considered production-ready.

## 5. Compatibility boundary

- Preserve the Numerical Oracle equations and float64 behavior. Compact F64 is a serialization/residency representation, not quantization.
- Keep the existing in-memory NetworkRuntime as the correctness reference until the storage-backed scheduler passes differential tests.
- The new storage-backed scheduler must preserve external input restrictions, one-owner-per-target-synapse, deterministic same-timestamp batching, event budgets and clock-epoch validation.
- The existing 128-shard NeuronWorkingSet remains useful for isolated per-neuron persistence experiments, but is not the backend for atomic multi-neuron network execution.
- Android's native Kotlin runtime remains separate and unchanged. Porting this protocol to Android requires a native JPVR/JNVM implementation and Python/Kotlin golden vectors; Termux remains optional.

## 6. Acceptance gates

1. JPVR group transaction tests: multiple neuron records and scheduler record become visible together or not at all.
2. Failpoint recovery at every write/publication boundary, including uncertain Superblock publication.
3. Differential tests against NetworkRuntime for same-timestamp batches, fan-out, rewards, queue backpressure and event ordering.
4. Crash/reopen tests prove no partial timestamp and no lost/duplicated committed outgoing events.
5. Bounded cache tests cover dirty-state retention, eviction, page exhaustion, compaction and storage failure.
6. Resource profiles at 1/10/100/1,000/10,000 neurons record RAM, disk growth, read/write amplification, latency and CPU on a named PC.
7. Only after the Python storage-backed reference passes, port the versioned contract to Kotlin and measure the Android device.

**Verification:** documentation/design review only. No implementation, test, benchmark, build or device measurement is claimed.
