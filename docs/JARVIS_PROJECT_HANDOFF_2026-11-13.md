# JARVIS ANS-100 Alpha — Project Handoff (13 November 2026)

**Purpose:** current working handoff for continuing the JARVIS project across conversations. It consolidates the agreed direction, implementation boundaries, Android/Python integration, verification status and next safe engineering stages. This is a status record, not a claim that the assistant is complete.

## Product direction

JARVIS ANS-100 Alpha is a research project for a compact adaptive assistant. The long-term goal is an Android-first assistant backed by a small, resource-conscious local runtime. Development proceeds in layers: neural reference → runtime and persistence → task/session lifecycle → bounded reasoning → permission/safety boundary → orchestration → Android application.

Priorities:
1. Keep RAM, CPU, storage, energy and I/O costs explicit; optimize for constrained Android hardware.
2. Prefer deterministic, bounded components and clear ownership of state.
3. Separate research hypotheses from demonstrated results.
4. Do not claim production readiness, general intelligence, task completion or external effects without direct evidence.
5. Do not enable shell, filesystem, network, device-control or other side effects until trusted identity, exact-request approval, policy enforcement, durable idempotency and trusted reconciliation are implemented and independently verified.

## Current implementation snapshot

### Neural and storage research
- Numerical Oracle: deterministic Python/float64 reference equations for Adaptive LIF-DT, STDP and R-STDP.
- NeuronRuntime and NetworkRuntime: bounded Python reference coordinators; not a production NCC runtime.
- JPVR: typed transaction builder, COW index mutation pipeline, file-backed two-Superblock storage, committed-history scanning and non-destructive recovery-copy publication exist as Python reference components.
- One-neuron, network, reward-baseline and task/session checkpoint stores exist as bounded Python reference layers.
- CQE remains a partial reference; durable general inbox/outbox delivery and production delivery semantics are not complete.
- SuperNeuron BTCC remains an isolated reference oracle; it is not integrated into the default learning rule and has no demonstrated task-level learning benefit.
- Android-device resource measurements, float32 equivalence and production crash-consistency guarantees are not established.

### Task/session, reasoning and orchestration
- TaskSessionManager provides bounded task/session lifecycle, revisions/CAS and canonical snapshots.
- Task/session snapshots can be persisted through a dedicated JPVR checkpoint store.
- Reasoning Engine V0.1 is a bounded symbolic planner/evidence/result-verification reference. It consumes structured caller-supplied facts and actions; it is not natural-language understanding or autonomous action generation.
- Permission & Safety Gate V1 is deny-by-default and checks structured capability/scope/approval/budget inputs.
- Persistent Safety Policy Registry V1 adds local policy snapshots and approval-ID revocation, but it does not authenticate the approver or verify signed grants.
- Task Loop V0.1 orchestrates bounded sequential steps and persists child-step intent/result metadata.
- Durable Executor Idempotency Ledger V1 prevents the wrapper from redispatching known unresolved/final keys while its ledger remains intact. It does not guarantee exactly-once effects at arbitrary downstream services; trusted reconciliation and downstream idempotency remain required.
- These are Python reference components. They are not yet a production autonomous-agent service and are not connected to arbitrary external tools.

### Android app and local console — source status V0.5

The repository contains a Kotlin/Jetpack Compose Android app (application ID org.jarvis.ans100.alpha) and a Python local console (tools/jarvis_app) intended to run in Termux on the same device.

The native app has two intentionally separate data domains:
- Android-local drafts in bounded app-private SharedPreferences through LocalDraftRepository.
- Backend sessions/tasks in Python TaskSessionManager backed by a dedicated JPVR checkpoint.

The V0.5 bridge source supports:
- fixed loopback endpoint http://127.0.0.1:8765;
- API v2 health/schema/auth handshake;
- Bearer-token authentication for state reads and mutations;
- token encryption at rest in Android Keystore (AES-GCM);
- digest-only token storage in the Python console, restrictive local file permissions and token rotation;
- create/read sessions and tasks, revision-checked session transitions and queued-task cancellation;
- bounded structured Reasoning Engine preview, which does not mutate JPVR state and does not execute actions;
- a PWA login/logout flow using current-tab session storage.

A backend task created from the UI remains QUEUED. A reasoning preview is an informational symbolic plan, not an approval or execution. The Android app does not yet invoke PermissionSafetyGate, Task Loop, ActionExecutor, NetworkRuntime or CQE.

The local console must remain bound to 127.0.0.1. Do not expose it to LAN or the Internet. The Bearer token is local API access control only; it is not proof of human identity and does not authorize side effects.

## Verification status and honesty rules

For the Android V0.5 bridge, current source records 11 Python console tests and 9 Android local-draft tests as AUTHORED / NOT RUN. Other reference suites have their own mixed historical evidence; use the exact status in each component contract.

For the latest Android/authentication bridge changes, only GitHub source readback and static cross-file inspection are recorded. The following have NOT been verified for V0.5:
- Python unit-test execution and runtime authentication behavior;
- Kotlin compilation or Android unit-test execution;
- Gradle sync/build and APK/AAB generation;
- Android installation, UI/instrumentation and Keystore behavior on a device;
- Android-to-Termux end-to-end HTTP and restart/recovery behavior;
- performance, battery, RAM, CPU and I/O on the target phone;
- CI/GitHub Actions.

The repository currently has no Gradle Wrapper. Do not claim an APK exists or that current source is installed on the user's phone. Do not run tests, builds, CI or GitHub Actions unless the user explicitly changes the existing no-build/no-test working constraint. Static readback is not a runtime test.

## Next engineering sequence

Work in substantial, coherent batches, update this handoff and the append-only development log after each meaningful batch, and preserve the safety gates.

1. Close V0.5 client-auth gaps: add focused Android Keystore token-store tests and Python auth/server negative-path coverage; inspect token rotation, malformed credentials, stale revisions, oversized bodies and error handling. Do not claim runtime success until tests are actually run.
2. Define trusted approver identity: select a realistic local trust root (for example Android Keystore-backed signing key plus explicit user-authenticated approval). Bind each grant cryptographically to the exact canonical AuthorizationRequest, action, arguments, capability, scope, policy version, expiry and one-time approval ID. A generic Bearer token or preview result must never count as approval.
3. Connect policy/revocation to a trusted source: the current local policy registry is only a reference store. Define signed policy updates, key rotation, rollback prevention and revocation semantics before allowing the gate to authorize side effects.
4. Define trusted reconciliation and downstream idempotency: the current SQLite ledger is a local guard, not proof of exactly-once external execution. Specify provider idempotency keys, authoritative outcome receipts and crash recovery; unresolved outcomes must remain UNKNOWN and must not be blindly retried.
5. Add a read-only Android Task Loop preview only after exact-request and trust contracts are specified. It may display plan, required capability, budgets and approval requirements, but must not dispatch effects.
6. Only then consider a constrained executor: one allowlisted, low-risk capability at a time, with exact-action approvals, durable intent/result, provider idempotency, cancellation/revocation and recovery. Keep all unimplemented capabilities disabled.
7. Integrate neural runtime gradually: define stable interfaces from task/session orchestration to bounded NetworkRuntime/CQE; avoid coupling Android UI directly to internal neuron objects. Add explicit resource budgets and measurements before scaling.
8. Complete Android delivery: add/restore a reproducible Gradle Wrapper, verify on-device Termux ↔ app flow, then build and install an APK only when authorized. Measure actual RAM/CPU/energy and startup latency.

## Working protocol
- Repository: maksrugu123-sys/jarvis-ans100-alpha.
- Primary branch: main (the user has authorized direct commits to main in this project).
- Make real repository changes rather than only returning task lists.
- Work in large, coherent batches and report a concise summary after a batch.
- Preserve append-only development history and update README/architecture/contracts when behavior changes.
- Do not silently change project scope or claim an unfinished component is complete.
- No builds/tests/CI by default under the current working constraint; if a future task explicitly authorizes them, report exact commands and results.
- Keep external action execution disabled until every trust/recovery gate above is satisfied.

## Current overall assessment

The project has progressed beyond a neural research notebook: it has bounded Python reference components for neuron/network coordination, JPVR persistence, task/session lifecycle, symbolic reasoning, safety checks and task orchestration, plus an Android V0.5 source-level frontend with an authenticated loopback registry/preview bridge.

It is NOT yet the first complete JARVIS assistant. The largest blockers are trusted human approval, signed exact-action grants, trusted policy authority/revocation, verified downstream idempotency/reconciliation, Task Loop integration behind those gates, neural-runtime integration, and actual Android build/device verification.