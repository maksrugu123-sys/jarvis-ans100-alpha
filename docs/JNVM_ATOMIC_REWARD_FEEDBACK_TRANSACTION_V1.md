# JNVM Atomic R-STDP Reward Feedback Transaction V1

**Status:** implemented as a bounded Python reference coordinator; 9 focused tests are AUTHORED, NOT RUN. This is not production NCC, CQE integration or Android-device-verified behavior.

## Purpose

The JPVR composite JRCM checkpoint already publishes NetworkRuntime and RewardBaselineManager together. This increment closes the in-memory consistency gap for a bounded feedback operation: one feedback proposal, one or more target synapses, one baseline advance and one new composite checkpoint.

## API

`process_reward_feedback(store, ..., targets, expected_runtime_version)` accepts:
- one `NetworkRuntime`;
- its `RewardBaselineManager` with a shared caller-owned clock epoch;
- a per-task feedback ID, sequence, normalized reward and model timestamp;
- a non-empty, unique list of `RewardSynapseTarget(neuron_id, synapse_id)`;
- one optional R-STDP learning context ID;
- a required expected JRCM runtime version.

All selected neurons must use R-STDP and each target's `RewardParams.beta_b` must exactly match the manager's `beta_b`. The caller must process queued network events through the feedback timestamp before requesting reward; the scheduler rejects a reward if an event at or before that time remains pending.

## Candidate / commit protocol

1. Clone the manager from its strict checkpoint state and call `prepare_feedback()`. A committed duplicate is reconciled against the active durable JRCM snapshot and returned without reapplying any synapse update.
2. Validate every target before changing candidate state.
3. Deep-copy the network and apply each target reward to that candidate at the same model timestamp. If any target fails (wrong context, late time, invalid synapse, queue ordering or Oracle error), discard the entire candidate. Caller-owned objects and JPVR remain unchanged.
4. Confirm each Oracle result's baseline-after and modulator equal the prepared manager proposal. Map the set of terminal Oracle statuses into one accepted receipt status.
5. Commit the proposal on the cloned manager, then pass both candidate objects to `JPVRRuntimeCheckpointStore.save()` with the required expected runtime version. Network synapse weight/eligibility and task baseline/feedback receipt are published in one JRCM CHECKPOINT + MANIFEST + COMMIT.

The function does not mutate its input objects. On success, callers must replace both references with `result.network` and `result.reward_baseline_manager`. On a stale runtime version, target error or pre-publication failure, callers retain the original objects. If the storage layer reports an uncertain commit outcome, reopen/load the composite checkpoint and retry the same feedback identity; the manager's retained receipt makes an already committed retry a no-op.

## Status aggregation

- Any `APPLIED` target => `APPLIED`.
- Otherwise any `APPLIED_NO_CHANGE` target => `APPLIED_NO_CHANGE`.
- All targets `EXPIRED` => `EXPIRED`.
- Other terminal combinations of `EXPIRED` and `NO_ELIGIBILITY` => `NO_ELIGIBILITY`.

No baseline advancement occurs for a non-terminal Oracle outcome or a failed target. An all-no-eligibility batch is a valid terminal no-op: synapse trace/eligibility clocks are updated and the baseline follows the existing manager policy.

## Idempotency and limits

The manager deduplicates task feedback by feedback ID, sequence, reward value and model timestamp within its retained history. The caller must treat the target set and learning context associated with a feedback ID as immutable; V1 does not persist a target-set digest. The manager's history is bounded, so retries older than retention are rejected rather than replayed.

This is a single-writer reference API. The required runtime-version CAS prevents a stale caller from overwriting a newer composite checkpoint, but no concurrent multi-process writer protocol or NCC serialization is claimed. The operation contains only local deterministic reference calculations; it does not include external actions, CQE inbox/outbox, authorization policy, session state or exactly-once delivery.

## Tests

`tools/neuron_runtime/test_feedback_transaction.py`: 9 tests for one-commit success, multi-target rollback, durable duplicate replay, stale duplicate version rejection, no-eligibility terminal outcome, beta mismatch, stale runtime CAS, queued-event blocking and duplicate target rejection.

All 9 tests are **AUTHORED, NOT RUN**. No Python tests, syntax/import checks, fault-injection tests, builds, CI or Android I/O measurements were run for this increment.
