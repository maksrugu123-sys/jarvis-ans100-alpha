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
- a PWA login/logout flow using current-tab session storage;
- shared-workspace CRUD and encrypted JWS merge transfer;
- a separate encrypted JWB point-in-time backup and full workspace replacement flow with local verification, explicit preview/confirmation, a separately saved current-revision backup acknowledgement and server-side CAS revision guard.

The JWB scope is only the portable workspace. It does not include the task/session JPVR registry, Android-only drafts, authentication tokens, Keystore keys or neural/network checkpoints. Raw JPVR file recovery remains a separate Python reference workflow.

A backend task created from the UI remains QUEUED. A reasoning preview is an informational symbolic plan, not an approval or execution. The Android app does not yet invoke PermissionSafetyGate, Task Loop, ActionExecutor, NetworkRuntime or CQE.

The local console must remain bound to 127.0.0.1. Do not expose it to LAN or the Internet. The Bearer token is local API access control only; it is not proof of human identity and does not authorize side effects.

## Verification status and honesty rules

For the current Android/console source, the inventories are: 18 Python service/API tests, 11 Python auth tests, 9 Android local-draft tests, 7 Android Keystore instrumentation tests and 9 Android transfer/backup crypto instrumentation tests. These are AUTHORED / NOT RUN. Other reference suites have their own mixed historical evidence; use each component contract for exact counts and status.

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

The near-term priority is the first usable local assistant, not completion of the external-action security stack. The current user-facing focus is Android first and PC in parallel, with shared conversation/data contracts.

1. Evaluate real compact offline language-model runtimes and model candidates against the target Android phone. Record model size, peak memory, cold/warm start, first-token latency, token rate, thermal/battery cost and Russian-language quality before choosing a backend. Do not assume the 64 MiB neural-core target is a model or whole-app budget.
2. Implement one actual on-device text-generation backend behind the new Android LocalResponseEngine interface. Preserve a deterministic fallback, strict resource/context limits, cancellation and explicit model-unavailable state. Do not label the current command/greeting fallback as an LLM.
3. Define one portable conversation/message contract for Android and PC, then bring Android-local history into explicit JWS/JWB export/import. Current mobile conversation history is a separate app-private store and is not yet synchronized or backed up through the shared workspace.
4. Add the corresponding desktop local conversation surface to the Python/PWA path, reusing the same message IDs/roles/serialization rules and a pluggable local inference backend. Do not require a remote JARVIS service.
5. Implement Android in-process memory and bounded reasoning adapters behind Kotlin interfaces. Keep the existing Termux bridge optional for development/JPVR administration; the basic conversation path must not depend on it.
6. Integrate compact ANS-100 runtime behavior only after stable local conversation and memory exist; measure device cost and preserve Python as a differential reference.
7. Revisit trusted approvals, signers and external actions only when the user prioritizes real actions. Keep shell, filesystem, network, device-control and other side effects disabled until every required gate is both implemented and independently verified.
8. Run tests, Gradle builds, APK installation and device/offline/performance checks only after the user explicitly authorizes those executions. Until then, label source tests AUTHORED / NOT RUN and implementation source-level/unverified.

## Working protocol
- Repository: maksrugu123-sys/jarvis-ans100-alpha.
- Active user-authorized branch: mobile-first-offline-v1.
- Never commit to, merge into, or move main unless the user explicitly changes this instruction.
- Make real repository changes rather than only returning task lists.
- Work in large, coherent batches and report a concise summary after a batch.
- Preserve append-only development history and update README/architecture/contracts when behavior changes.
- Do not silently change project scope or claim an unfinished component is complete.
- No builds/tests/CI by default under the current working constraint; if a future task explicitly authorizes them, report exact commands and results.
- Keep external action execution disabled until every trust/recovery gate above is satisfied.

## Current overall assessment

The project has progressed beyond a neural research notebook: it has bounded Python reference components for neuron/network coordination, JPVR persistence, task/session lifecycle, symbolic reasoning, safety checks and task orchestration, a source-level Ed25519 exact-request approval verifier with a strict envelope contract, and an Android source-level frontend with an authenticated loopback registry/preview bridge plus a separate in-process basic conversation path and bounded local history.

It is NOT yet the first complete JARVIS assistant. The largest blockers for a usable assistant are a real offline language model, shared Android/PC conversation continuity, in-process memory/reasoning integration, measured Android runtime cost, and actual build/device verification. External-action trust, signing, downstream idempotency and reconciliation remain dormant prerequisites only if the user later prioritizes external actions.

## LiteRT-LM offline chat milestone — 13 November 2026

Added an opt-in local inference path for Android and desktop/PWA using the same Qwen3-0.6B INT4 no-think LiteRT-LM artifact.

- Model file: `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm` (about 347 MB), obtained separately from `https://huggingface.co/litert-community/Qwen3-0.6B-int4`.
- Android: dependency `litertlm-android:0.17.1`, Kotlin plugins 2.3.10, explicit Storage Access Framework selection, private app copy, CPU backend, 1,280-token engine cap, 96-token answer cap, four-message short history and a 1,280 MiB available-memory guard.
- Desktop/PWA: optional `litert-lm-api==0.17.1`, CLI `--model`, lazily initialized CPU Engine, authenticated `/api/conversation` GET/POST and compare-and-swap persistence in the workspace.
- The Python and Android paths do not register tools or enable automatic tool calling. No cloud fallback or automatic model download is present.
- `tools/jarvis_app/test_local_inference.py` was authored for a fake-runtime adapter check; **AUTHORED, NOT RUN**.
- Android SharedPreferences conversation storage is still separate from portable workspace. Desktop chat is workspace-backed; cross-platform history transfer and live sync are not implemented.
- This is a source change only. No Python tests, Android build, Gradle sync, APK, device inference, browser/PWA check, CI or GitHub Actions were run. Model compatibility, Russian output quality, runtime memory, speed and thermal behavior remain unverified.

### Runtime caution

The selected model is intentionally small and no-think: it may give short direct answers but does not provide strong multi-step reasoning. The Android memory check is not a guarantee against out-of-memory termination. The model must be manually selected on Android; the desktop process must be started with `--model` and the optional Python package installed. Do not call this a fully validated JARVIS release yet.

## Integrated experimental JARVIS prototype — 14 November 2026

A separate branch `experiment/jarvis-integrated-prototype-v1` now assembles an initial usable PC-side JARVIS console around the existing source modules. It is based on `mobile-first-offline-v1` plus the earlier sparse dialogue-neuron experiment; it does not change `main`.

- PC/PWA local chat now has a deterministic offline fallback when no language model is configured. The fallback supports greetings, `/help`, `/status` and explicit service-status responses; it does not generate free-form answers. With the pinned LiteRT-LM runtime and user-supplied model file, the same PC Core uses local Qwen generation.
- The PC turn pipeline now invokes the 10,000-addressable-feature sparse dialogue classifier together with bounded approved-memory retrieval and the existing ten-neuron ANS-100 signal pre-pass. Its output is passed to the local language engine as a clearly marked heuristic signal, not as semantic understanding.
- A visible PWA panel allows the user to explicitly label a text sample as greeting/question/thanks/farewell. Only this explicit feedback updates the classifier. Its checkpoint stores numeric weights and the training count, not raw sample text.
- Existing PC workspace memory, JPVR task/session management, symbolic reasoning preview, visible read-only Chromium browser and encrypted workspace transfer/backup remain in the integrated console. External actions, tool execution and autonomous Task Loop dispatch remain disabled.
- Android retains its existing in-process conversation, local history, explicit local memories, optional model import, ten-neuron Kotlin ANS-100 pass and optional Termux bridge. **The 10,000-feature Python classifier is integrated into the Python-console path, usable on desktop or through the Termux-hosted Android PWA. It is not yet ported into the native Android Compose conversation runtime.**
- Static source read-back confirms the branch contents and branch comparison. Tests were added or updated but remain **AUTHORED / NOT RUN**. No Python tests, Playwright session, model inference, Gradle build, APK, Android install, device measurement, CI or GitHub Actions were run. This is source integration, not a runtime-verified release.

See [JARVIS Experimental Integrated Prototype V1](JARVIS_EXPERIMENTAL_PROTOTYPE_V1.md) for launch steps, operating boundaries and current limitations.
