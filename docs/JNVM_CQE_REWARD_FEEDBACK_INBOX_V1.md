# JNVM Durable CQE R-STDP Reward Feedback Inbox V1

**Status:** implemented as a bounded Python reference adapter over JRCM; twelve focused tests are **AUTHORED, NOT RUN**. It is not the production NCC, not a general-purpose durable CQE inbox, and not Android-device-verified.

## Purpose and trust boundary

The ordinary CQE v1 reference dispatcher remains an in-memory/volatile coordinator. This increment adds a narrowly scoped durable ingress path for reward feedback only. It uses the existing JRCM composite checkpoint and RewardBaselineManager pending/receipt history; it does not add a second MANIFEST checkpoint pointer or a separate file whose version could drift away from network state.

The caller must first validate and authorize the source through its upstream security/policy layer. The adapter validates and records the source module, policy version and a UUID authorization-receipt reference, but it does not authenticate the source, issue/verify capabilities, evaluate policy, or itself connect to a policy gate. The receipt ID is provenance for later audit, not cryptographic proof. Calling this adapter before authorization is a caller-side trust-boundary violation.

## CQE message contract

The accepted message is a CQE v1 EVENT with schema jarvis.reward.feedback, schema version 1. This is an adapter-specific schema contract; any external CQE router must register and authorize it before routing. The adapter does not add this schema to the general dispatcher registry. The payload has the exact fields:
- reward_task_id, feedback_id, positive feedback_sequence;
- finite normalized reward_value, unsigned model_time_ns;
- the active non-zero 128-bit clock_epoch_id as lowercase hexadecimal;
- optional learning_context_id;
- one to 128 unique {neuron_id, synapse_id} targets.

Message and event IDs must be canonical lowercase UUIDs. Targets are sorted by (neuron_id, synapse_id) before the durable envelope is generated. The complete normalized envelope includes all CQE envelope fields plus policy_version and authorization_receipt_id, and is stored as canonical JSON. A SHA-256 digest is calculated over canonical {learning_context_id, targets}. The decoder recomputes this digest and verifies that the envelope task/feedback/sequence/reward/time/epoch matches the owning pending proposal or receipt.

## Durable inbox and processing protocol

1. Normalize and validate the EVENT, its bounded target set, clock epoch and authorization provenance.
2. Reserve RewardBaselineManager.prepare_feedback() with the canonical envelope and target/context digest. Exact pending/committed replays must match the same envelope. Reusing a message ID or event ID with different content or another task conflicts.
3. Run a deterministic preflight over every target on a detached NetworkRuntime copy. It checks target neuron/synapse identity, R-STDP mode, beta agreement, model-time/context constraints and that Oracle baseline_after/modulator match the prepared proposal. Rejection here creates no durable inbox record and does not mutate caller objects.
4. Publish the PREPARED proposal with JPVRRuntimeCheckpointStore.save() before reward updates are applied to the authoritative runtime. This JRCM COMMIT is the durable inbox-accept boundary. It also publishes the unchanged network snapshot under the same runtime version.
5. Apply all selected synapse rewards to a detached candidate NetworkRuntime using the existing all-or-nothing feedback transaction. The final receipt, target/context digest, baseline advance and network weight/eligibility updates are published in one subsequent JRCM COMMIT.
6. On process interruption after inbox acceptance, reopen the JPVR file and call load(network_id, manager_id). Retry the exact same envelope with the loaded network, manager and latest runtime version. A matching persisted pending proposal is resumed without another inbox write. If the final COMMIT already succeeded, the retained receipt is reconciled as a duplicate without reapplying the reward.

A new accepted EVENT normally advances the JRCM runtime version twice: once for durable pending acceptance, once for completion. Recovery of an already-persisted pending item needs only the completion version. Exact completed replay performs no write. inbox_runtime_version in CQEFeedbackResult is the version created by this call for the pending inbox, or None when resuming/replaying an existing item.

## Persistence and compatibility

The existing JRBM schema ID/version remains unchanged. RewardBaselineProposal and RewardBaselineReceipt now have two optional fields: canonical cqe_envelope_json and feedback_context_digest. The JRBM V1 decoder accepts both the old field sets and the extended field sets; legacy checkpoints restore with both fields set to None. New state exports include the optional fields. Strict restoration validates canonical JSON, duplicate keys, envelope schema, UUID identities, target ordering, target/context digest and binding to the surrounding proposal/receipt.

The metadata remains inside the existing JRBM component snapshot, which JRCM already publishes atomically alongside NetworkRuntime. No standalone CQE page or competing MANIFEST pointer is introduced.

## Bounds and explicit limitations

- Only reward-feedback EVENT schema v1 is accepted. This is not durable storage for arbitrary CQE COMMAND/QUERY/EVENT messages, generic subscriber inboxes, or an event broker.
- One pending feedback proposal per reward task and the existing bounded task/receipt history remain in force. No unbounded global UUID history is claimed.
- The existing JRCM runtime-version compare-and-swap is the single-writer guard. There is no multi-process locking or production NCC serialization.
- The adapter records upstream authorization provenance but does not perform authorization.
- No sessions, plans, external side effects, durable general outbox, or exactly-once delivery are implemented.
- An uncertain JPVR commit still requires reopening and reconciling the active JRCM version before retry.
- Android storage behavior, power-loss behavior, concurrency and resource performance remain unmeasured.

## API

process_cqe_reward_feedback(store, network_id=..., network=..., reward_baseline_manager=..., envelope=..., policy_version=..., authorization_receipt_id=..., expected_runtime_version=...)

The returned CQEFeedbackResult contains the underlying atomic reward transaction, message/event IDs, inbox runtime version (if newly published), and final runtime version.

## Tests

tools/neuron_runtime/test_cqe_feedback.py: twelve focused tests for durable acceptance, pending recovery without a duplicate inbox write, exact replay/no-write behavior, target-set conflict, message/event identity reuse, policy provenance, schema/type rejection, duplicate targets and tampered digest rejection.

All twelve tests are **AUTHORED, NOT RUN**. No Python tests, imports/syntax checks, fault injection, Android I/O, benchmarks, builds, CI or GitHub Actions were executed for this increment.
