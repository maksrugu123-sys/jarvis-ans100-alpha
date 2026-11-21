# JARVIS CQE v1 — executable contract reference

**Status:** reference implementation only; not the production JARVIS runtime.

This package turns selected parts of architecture sections 3.13–3.15 into a
small, executable Python reference so the protocol can be reviewed and tested
before the production language and runtime are selected. It uses only the
Python standard library and is intentionally single-threaded.

## Implemented in this reference

- CQE `COMMAND`, `QUERY`, and `EVENT` envelopes with UUID identity checks and
  schema-version validation.
- Module registration and operation/schema routing.
- Deny-by-default command capabilities with sender, operation/schema, priority,
  policy-version and expiry checks, revocation, and a second authorization check
  immediately before commit. Object/domain scope and resource-budget enforcement
  from the full architecture are not implemented in this reference.
- NCC-owned priority selection; a sender's requested priority is only a hint.
- Bounded P0–P4 queues, per-class quotas, a per-source ordinary-message limit,
  FIFO within each class, and explicit `ACCEPTED` / `DEFERRED` / `REJECTED` results.
- Payload, descriptor, envelope, query-result, deadline and registry bounds.
- Command `operation_id` and idempotency-key handling, status lookup, deadline
  checks, and expected-state-version conflict detection.
- A single-writer **volatile** reference commit coordinator.
- Event creation only after the commit coordinator returns a successful receipt,
  per-subscriber in-memory deduplication, and a bounded volatile retry queue.
- Refusal of commands that require durable storage when no durable JPVR adapter
  exists. The reference never pretends that an in-memory write is durable.

## Run its focused checks

From the repository root:

```sh
python3 -m unittest tools.cqe_reference.test_cqe -v
```

The tests exercise protocol behavior and failure boundaries. They are not a
benchmark, a build, a JPVR crash-consistency test, or proof of production safety.

## Explicit limitations

This package does **not** implement:

- the production JARVIS runtime or a final production language/API;
- JPVR_FORMAT_V1 reader/writer, `flush`/`sync`, crash recovery or a durable commit;
- an atomic persistent commit-record + outbox transaction;
- durable subscriber inboxes or deduplication across process restarts;
- real concurrency, worker pools, async cancellation, RPC or external brokers;
- the complete NCC lifecycle/recovery/resource managers or the central JARVIS
  capability-issuance service;
- explicit capability scope checks for domain/object ranges and capability resource
  budgets; the current reference checks sender, operation/schema, priority, expiry
  and policy version only;
- Aging/starvation protection for queued work: the dispatcher always selects the
  highest non-empty priority class (P0→P1→P2→P3→P4) and FIFO within that class;
  a sustained higher-priority load can therefore delay lower-priority work;
- durable retention/compaction policies for idempotency and event inbox records.

The in-memory coordinator accepts only volatile proposals. A proposal marked
`requires_durable=True` is rejected as unavailable before any state change.
The in-memory event retry queue is likewise volatile and can be lost on process
exit. Its purpose is to test ordering and retry semantics, not to claim the
crash-safe outbox specified in section 3.15.6.

The reference retains bounded deduplication metadata. Once those conservative
metadata caps are reached, new messages/operations are deferred instead of
silently deleting records that may still be needed to suppress a duplicate.
A production implementation must connect retention/compaction to durable
operation and event records before relaxing these limits.

## Key constants

The initial limits mirror the CQE v1 starter profile where applicable: 256
queued messages total; P0/P1/P2/P3/P4 quotas of 16/16/64/96/64; 32 ordinary
queued messages per source; 8 KiB envelope; 4 KiB inline payload; 1 KiB per
external descriptor with at most 16 descriptors; 16 KiB QUERY result; and
1 MiB / 256 records for the outbox. This reference keeps 16 outbox records
reserved and does not yet expose a P0/P1 durable-outbox path.

These are safety-oriented starting limits, not measured throughput or latency
claims. They remain subject to validation on the target device.


## Specialized durable reward-feedback adapter

A separate module, tools/neuron_runtime/cqe_feedback.py, can persist only the adapter-specific JARVIS R-STDP reward-feedback EVENT through the JRCM composite Runtime checkpoint. The adapter does not register the schema in this general dispatcher and does not implement source authorization; an upstream security/policy layer must authorize it first. It preflights targets on a detached copy, persists a pending proposal, then commits the terminal receipt with network/baseline state. It does not make arbitrary CQE EVENT delivery durable. See docs/JNVM_CQE_REWARD_FEEDBACK_INBOX_V1.md.
