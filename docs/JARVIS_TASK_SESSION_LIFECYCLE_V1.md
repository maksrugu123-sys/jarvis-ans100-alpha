# JARVIS Task & Session Lifecycle V1

**Status:** implemented as a bounded Python reference manager and canonical snapshot codec; focused tests authored, not run. This is not JPVR persistence, a scheduler, a reasoning engine, or production NCC.

## Purpose and boundary

TaskSessionManager provides the first executable lifecycle registry needed by a future JARVIS task loop. It owns stable task/session identities, explicit state transitions, compare-and-swap revisions, bounded task/session/event catalogs, idempotent task creation, and deterministic event history.

It does not interpret natural language, decompose tasks, execute plans, authorize capabilities, invoke external tools, schedule concurrent work, or perform side effects. A caller must pass authorization and execution through the future Permission & Safety Gate and CQE/NCC interfaces.

## Session contract

A session has a stable ID, open/paused/closed status, monotonically increasing revision, logical creation order, and bounded JSON metadata. New tasks may be created only in an open session. Paused sessions prevent new tasks and prevent queued/waiting tasks from entering running. A session may close only when every task in it is terminal. Closed sessions cannot be reopened.

## Task contract

A task has stable task ID and idempotency key, owning session, bounded title, queued/running/waiting/completed/failed/cancelled status, revision, logical creation/update order, bounded input references, and optional terminal result/failure code.

Allowed transitions:
- queued -> running | cancelled
- running -> waiting | completed | failed | cancelled
- waiting -> running | failed | cancelled

Terminal states cannot transition. Every mutating task transition requires the exact expected task revision. Exact repeated task creation with the same idempotency key and identical identity/content returns the existing record; conflicting reuse is rejected. The registry retains idempotency mappings for the lifetime of its bounded task catalog; it does not claim global or unbounded deduplication.

## Boundedness and snapshots

Defaults: 128 sessions, 1,024 tasks, 4,096 lifecycle events, 32 input references per task, 4 KiB metadata/result JSON per object, and 4 MiB encoded snapshot. Limits are configurable within hard maxima. The registry is single-writer and uses logical counters rather than wall-clock timestamps.

Each successful create/transition appends one ordered lifecycle event. If event capacity is exhausted, the operation is rejected before mutation. Capacity exhaustion fails closed; terminal records are not silently evicted.

Schema ID jarvis.task-session, version 1. encode_snapshot emits canonical UTF-8 JSON. restore_snapshot rejects duplicate JSON keys, non-finite values, unknown fields/schema versions, invalid identities/statuses/revisions, duplicate task/idempotency identities, missing session references, inconsistent terminal data, over-capacity collections, and oversized payloads.

The codec is a serialization boundary only. It does not make bytes durable. A future JPVR task/session store must bind this snapshot to a versioned record/checkpoint and commit it through the existing single-writer JPVR transaction path. Until then, process restart recovery is not implemented.

## Verification

    python3 -m unittest tools.neuron_runtime.test_task_session -v

Tests cover state transitions, terminal immutability, CAS conflicts, idempotent creation, paused/closed session rules, capacity rejection, canonical round-trip, duplicate JSON keys, schema/size rejection, and invalid metadata. Tests are authored, not run.
