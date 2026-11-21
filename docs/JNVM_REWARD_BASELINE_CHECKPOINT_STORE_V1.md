# JNVM Reward Baseline Manager and JPVR Checkpoint Store V1

**Status:** implemented bounded Python reference manager, canonical snapshot codec and dedicated-container JPVR checkpoint store; 26 manager/codec tests and 15 store tests are **AUTHORED, NOT RUN**. Not device-verified or production-ready.

## Purpose and ownership

The Numerical Oracle's `apply_reward()` accepts `baseline_before` as an input and returns `baseline_after`; it deliberately does not own feedback identity, ordering, deduplication or durable progression. `RewardBaselineManager` supplies this missing bounded state owner, separately tracking expected reward for each `reward_task_id`.

The initial baseline is explicit configuration (reference default 0.5), and `beta_b` is independently configured (reference default 0.01). These are not measured or calibrated universal values. Do not silently change the canonical STARTER profile to match them; the caller must configure the manager's `beta_b` consistently with its R-STDP `RewardParams`.

## Manager state machine

For each task, V1 stores:
- expected reward in [0,1] and a monotonically increasing baseline_version;
- last committed feedback sequence, feedback ID and model timestamp;
- at most one pending immutable feedback proposal;
- bounded recent receipts (default 16, configurable up to 256).

The manager-wide task catalog is bounded (default 1024, hard maximum 100,000). The current API is single-writer; it is not a multi-threaded reservation/locking service.

`prepare_feedback(task_id, feedback_id, sequence, reward, model_time, clock_epoch_id=...)` validates nonempty stable IDs, reward [0,1], positive u64 sequence/model time, exact nonzero 128-bit manager epoch and per-task nondecreasing model time. The next accepted sequence is exactly `last_committed_feedback_sequence + 1`.

- A gap raises `RewardBaselineDeferred(expected_sequence, received_sequence)` without changing task state.
- An exact repeat of the active pending proposal returns the same proposal.
- Another pending feedback for the same task is a conflict.
- An exact committed replay within retained history returns a DUPLICATE proposal whose commit returns the original receipt without advancing baseline again.
- A reused sequence with a different ID/payload, or a replay older than retained history, conflicts safely.
- Different tasks maintain independent baselines and sequence streams.

The proposal is bound to `manager_id` and `clock_epoch_id`; a feedback ID cannot be reused at another sequence inside retained task history. It snapshots the old baseline and computes:

\[
M = \operatorname{clip}(r-b,-1,+1),\quad
b' = \operatorname{clip}(b+\beta_b(r-b),0,1)
\]

Preparing a proposal does not mutate the committed baseline. `commit_feedback(proposal, processing_status=...)` advances baseline, sequence and version only for these terminal outcomes: `APPLIED`, `APPLIED_NO_CHANGE`, `NO_ELIGIBILITY`, `EXPIRED`, `DISABLED_NO_OP`. Other statuses such as `RETRY_REQUIRED` or `FAILED` are rejected; pending state remains available for recovery. This is the baseline gate only; it is not a multi-synapse transaction.

## Snapshot codec

- Format: `JARVIS_REWARD_BASELINE_MANAGER_V1`
- Record type: `0x0103`
- Schema ID: `0x4A52424D` (ASCII JRBM)
- Schema version: `1`
- Object ID: first 16 bytes of SHA-256 over domain prefix `JARVIS:JNVM:REWARD-BASELINE:V1\\0` followed by strict UTF-8 manager_id.
- Payload: canonical UTF-8 JSON with sorted object keys, deterministically sorted task records, strict field sets, no duplicate keys or non-finite constants. Pending proposals and recent receipts survive restore.

Restore rejects malformed identifiers, duplicate feedback IDs in retained history, noncanonical/zero epochs, duplicate tasks, out-of-range values, invalid sequence/version/history relationships, tampered baseline/modulator equations, invalid pending reservations and unknown fields.

## JPVR descriptor and commit

`JPVRRewardBaselineStore` writes a BLOB logical record followed by one `PageType.CHECKPOINT` page containing the fixed `JRCP` V1 descriptor. The descriptor records object ID, record version, contiguous BLOB first page/count, source generation, payload length and CRC32C. The typed transaction builder publishes BLOB + CHECKPOINT + MANIFEST + COMMIT as one JPVR storage transaction.

Load checks the active Superblock and MANIFEST checkpoint pointer, CHECKPOINT envelope/type/schema, the exact source COMMIT in the active predecessor chain, source transaction/generation/container identity and its MANIFEST pointer, BLOB page range and transaction metadata, record/schema/object/version fields, payload length/CRC and inner canonical snapshot. `expected_record_version` provides a CAS guard. Genesis INDEX initialization is a separate schema COMMIT, as in existing JPVR stores. The materialized page catalog defaults to 4096 pages.

### Dedicated container requirement

V1 must use a dedicated JPVR container for reward-baseline state. The JPVR MANIFEST exposes one active `checkpoint_page_no`; `JPVRNetworkCheckpointStore` already uses that pointer for the complete NetworkRuntime snapshot. Saving a reward baseline checkpoint into the same container would replace the active network pointer. This store does not attempt to combine or extend JNCP V1.

A later composite Runtime checkpoint needs a new versioned descriptor/index contract that references both the network and reward-baseline object snapshots and publishes their identity in one commit. It should not rely on two competing uses of `checkpoint_page_no`.

## Clock and recovery boundary

The caller supplies a stable nonzero 128-bit `clock_epoch_id`. Manager snapshots preserve it and require equality on new feedback; per-task model time is monotonic inside that epoch. The manager does not invent a new epoch, convert old monotonic timestamps, or implement a scheduler rebase rule.

A saved pending proposal allows the caller to resume the same feedback reservation after process restart. It does not establish whether external synapse work already committed. The caller must save pending state before side effects and must not advance the baseline until all associated synapses have terminal success or explicit safe no-op results.

## Explicit non-goals

- No atomic transaction spanning reward baseline + one/many synapse weight/eligibility updates + network checkpoint.
- No durable RewardProcessingRecord, CQE inbox/outbox, session or side-effect persistence.
- No integrated source/critic authorization registry or policy-version verification.
- No concurrent writer protocol, multi-pending processing per task, or unbounded dedupe; history older than retention is rejected.
- No automatic epoch rebasing, model-time migration, Android storage measurement, hardware power-loss guarantee or production NCC.
- This is not proof of useful R-STDP learning, general intelligence or production readiness.

## Tests and verification

`tools/neuron_runtime/test_reward_baseline.py`: 26 methods for task isolation, baseline-before semantics, sequence gaps/conflicts, pending retry, duplicate replay, terminal status gating, epoch/time checks, bounds, strict restore and canonical snapshot behavior.

`tools/neuron_runtime/test_reward_baseline_store.py`: 15 methods for descriptor encoding, save/load, pending feedback after reopen, CAS, dedicated pointer isolation, object ID isolation, genesis bounds, corrupted BLOB, source COMMIT identity and catalog bounds.

All 41 tests are **AUTHORED, NOT RUN**. No Python tests, import/syntax checks, fault injection, Android/Termux I/O, builds or CI were executed for this stage.


## DATA/BLOB sizing note

The JRBM adapter emits `DataRecordInput` for payloads up to 3976 bytes and `BlobRecordInput` for larger snapshots. JRCP V1 flag 0 identifies BLOB; flag 1 identifies one DATA page. The store validates exact JREC identity inside DATA plus transaction/generation metadata and payload CRC. The dedicated-container rule remains unchanged.
