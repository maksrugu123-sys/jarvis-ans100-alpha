# JARVIS Cross-Platform Runtime V1 — PC + Android

**Status:** architecture contract and migration plan  
**Date:** 2026-11-13  
**Applies to:** Windows / Linux / macOS desktop and Android phone  
**Primary goal:** one JARVIS product identity and compatible user data across both device classes, with local-first operation.

## 1. Product decision

JARVIS will support two first-class device targets:

- **PC/Desktop:** a local Python runtime using the existing research/reference components, with a browser-based local UI as the initial desktop interface. A native desktop shell is optional and comes later.
- **Android:** a native Kotlin/Jetpack Compose application. The end-user app must ultimately run its essential runtime in-process and must not require Termux to be installed or kept open.

Both targets must be usable without a remote JARVIS server. Internet connectivity is optional and must not be required for local conversation, local memory, or local task planning.

This is a shared product and data contract, not a claim that the current implementation already meets these requirements.

## 2. Current repository reality

| Area | Existing source | Current limitation |
|---|---|---|
| Desktop runtime | Python modules under tools/ | Reference/research components; no packaged desktop product yet |
| Desktop UI | PWA served by tools.jarvis_app | Local task/session console and optional local-model conversation through JARVIS Core; source-level only, not runtime verified |
| Android UI | Kotlin + Jetpack Compose under app/ | Native V0.5 source exists |
| Android runtime connection | LocalRuntimeBridgeClient → 127.0.0.1:8765 | Optional Termux extension for shared JPVR workspace, task/session administration, symbolic preview and encrypted transfer; core chat/history and the Kotlin ANS-100 signal pass remain in-process |
| Neural runtime | Python NeuronRuntime / NetworkRuntime reference; Kotlin in-process Android signal pass | Bounded ten-neuron request-shape signal preprocessing is used by desktop Core and Android; Android may prefer the authenticated Termux Python endpoint when connected, but falls back to Kotlin. This is not full cognition or dialogue learning |
| Persistent storage | Python JPVR reference stores, including the shared workspace checkpoint | No native Kotlin JPVR implementation or automatic cross-device sync yet |
| Offline language model | Optional LiteRT-LM Qwen3 adapters called by JARVIS Core on Android and desktop/PWA | Source-level only; no inference or target-device performance has been verified |

The existing desktop PWA can be hosted by the Python local console on a PC. This gives a practical desktop development surface without pretending that a packaged desktop executable already exists. The shared workspace JSON contract, JPVR workspace store, authenticated local API V1, Android Compose memory manager and desktop PWA memory manager are present on `mobile-first-offline-v1`; both UIs support explicit-consent memory creation, search, listing and confirmed deletion. Manual encrypted `.jws` transfer is implemented on both platforms; the phone and PC still keep separate loopback-local files until the user explicitly moves a file between them.

The Python JPVR file layer now has a Windows adapter (`msvcrt` region locking, serialized seek-based positional I/O and binary-mode file descriptors) intended for Windows 10 and 11. Windows auth storage relies on inherited user-profile ACLs; the current standard-library implementation does not inspect or configure ACL entries. Neither Windows version has been runtime-tested, so Windows remains experimental until the platform-specific and full storage tests pass.

## 3. Shared architecture

JARVIS user data follows a versioned portable workspace contract.

Desktop / PC:
- Browser UI / PWA
- Python application host and local coordinator
- Engine adapters for inference, memory, reasoning, ANS-100, and safety
- PC-local storage

Android phone:
- Native Compose UI
- Kotlin application host and local coordinator
- Engine adapters for inference, memory, reasoning, ANS-100, and safety
- Android app-private storage

Optional explicit authenticated sync/export connects the two local workspaces. Neither local core path depends on a remote service.

### Shared domain contract

The following concepts must have the same meaning on both platforms:

- conversation, message, attachment reference, and turn status;
- durable user-approved memory versus ordinary conversation history;
- task, session, task-step, lifecycle status, revision, and idempotency key;
- reasoning request/result, evidence provenance, and planner bounds;
- ANS-100 model configuration, event ordering, checkpoint version, and deterministic replay metadata;
- safety capability, authorization request, approval state, revocation, and action result;
- resource profile and runtime capability report.

UI layout and platform permissions may differ. Domain meaning and persisted identifiers must not.

## 4. Implementation strategy — avoid a premature rewrite

Do not rewrite the existing Python research code into Kotlin all at once. It would discard useful reference implementations and introduce a large unverified port.

Use a staged adapter strategy:

1. Keep Python as the desktop executable/reference implementation while its contracts are stabilized.
2. Define language-neutral, versioned request/response and persistence contracts.
3. Keep Android platform code in Kotlin and put all engine access behind interfaces; do not let Compose screens call Python bridge classes directly.
4. Port the minimum required deterministic components to Kotlin in small, parity-tested increments, beginning with memory/task primitives and then the compact neural runtime.
5. Preserve Python as a differential oracle for the Kotlin implementation.
6. Consider extracting the stable performance-critical neural core into a portable native library (for example Rust/C ABI) only after measurements show a benefit and the Android packaging cost is justified.

A future shared native core is an option, not a prerequisite for starting the two-platform product. Avoid maintaining two unrelated algorithms: parity fixtures and versioned serialized state are mandatory.

## 5. Runtime interfaces

Each platform coordinator must depend on interfaces equivalent to:

- InferenceEngine: backend identity, availability, resource estimate, generate, cancel.
- MemoryRepository: append conversation, store/forget explicit memory, retrieve, export/import.
- ReasoningEngine: bounded plan, evidence assessment, observed-result verification.
- NeuralRuntime: initialize, process bounded batch, snapshot, restore.
- TaskRepository: create/read/update task and session using revisions and idempotency.
- SafetyGate: deny by default, authorize exact canonical request, revoke, record outcome.
- CapabilityRegistry: report actual local features, model availability, permissions and resource limits.

Every call must carry a schema/version, request ID, bounded input size, deadline or step budget, and explicit cancellation semantics where relevant. Unsupported versions and malformed payloads must fail closed.

## 6. Platform-specific responsibilities

### PC / Desktop

- Run the Python application host and local runtime in one user-owned process.
- Initial UI: existing PWA in a local browser.
- Provide a one-command launcher and clear first-run data-directory selection in a later delivery phase.
- Store data in a user-private application directory; never assume the repository checkout is the data directory.
- Windows 10/11 now have a source-level JPVR I/O and lock adapter; do not mark them supported until cross-process locking, storage, token rotation and full console tests pass on both OS versions. Linux/macOS still require their own path, permission, locking and packaging verification.
- Use OS-protected secret storage where available; never write raw access tokens to logs.
- Optional CPU acceleration is selected only when detected and explicitly enabled; CPU-only operation remains the baseline.
- Desktop closing/restarting must not corrupt checkpoints or lose acknowledged writes.

### Android

- Compose owns UI, lifecycle, permissions, and user-facing capability status.
- App-private storage owns conversation, memory, settings, and checkpoints.
- The core conversation path must not depend on 127.0.0.1, Termux, a local HTTP server, or a network permission.
- The Termux bridge remains an optional extension for shared JPVR/task services and transfer. It is not in the critical conversation path; the Android in-process signal runtime already provides the local signal fallback.
- Android Keystore protects cryptographic keys and secrets; Android permissions gate microphone and other platform capabilities.
- Use bounded background work and respect Android lifecycle, battery, thermal, and storage constraints.
- Offline text remains the first target; offline voice follows only after local speech models are integrated and measured.

## 7. Portable workspace and cross-device data

PC and Android exchange data through a versioned portable workspace bundle. The `.jws` file is not a raw copy of the JPVR file because JPVR is currently a Python reference storage implementation and its internal file format is not a cross-platform interchange guarantee.

The portable workspace/transfer contract includes:

- explicit format name and major/minor version;
- workspace ID and export ID;
- creation timestamp plus source-platform metadata;
- records for conversations, explicit memories, tasks, and settings;
- stable record IDs and per-record revision/version;
- canonical UTF-8 serialization, deterministic ordering, and integrity checksum;
- authenticated AES-256-GCM encryption for portable transfer files; the workspace remains plaintext only inside the authenticated local application process;
- strict size/count limits and validation before import;
- V1 conflict policy: retain the destination's local record, skip a differing imported record with the same stable ID/key, and show the conflict count before confirmation; keep the source `.jws` unchanged for manual resolution; never silently overwrite;
- import preview and explicit user confirmation;
- validate the complete merged workspace before one revision-checked JPVR publication;

Never sync raw credentials, access tokens, private keys, Android Keystore material, or device-specific permission grants. A device must independently authorize sensitive capabilities.

Manual encrypted JWS export/import and separate JWB workspace backup/restore are implemented at source level on both platforms; Android uses the Storage Access Framework and desktop uses browser file selection/download. JWB restore is an explicit full-workspace replacement behind a saved-current-backup acknowledgement, preview, second confirmation and server-side CAS revision guard. Cross-platform round-trip and restore verification are still pending. Automatic live sync is a later optional feature. Do not expose the local console on 0.0.0.0 or the LAN as a shortcut to synchronization.

### Initial portable schema

The portable data contract is [workspace.schema.json](../schemas/workspace/v1/workspace.schema.json); the JWS transfer envelope is [encrypted-bundle.schema.json](../schemas/workspace/v1/encrypted-bundle.schema.json). The JWB backup payload and encrypted envelope are [workspace-backup.schema.json](../schemas/workspace/v1/workspace-backup.schema.json) and [encrypted-backup.schema.json](../schemas/workspace/v1/encrypted-backup.schema.json). Desktop PWA and Android source implement the same PBKDF2/AES-GCM transfer and backup formats, canonical validation, purpose-specific AAD and explicit confirmation. JWS merges stable IDs/keys and retains destination conflicts; JWB validates both integrity layers then replaces the complete same-ID workspace through a 16 MiB authenticated CAS route. The Android instrumentation suite has deterministic desktop-WebCrypto-compatible JWS and JWB interoperability fixtures plus backup format separation, tamper, wrong-passphrase and buffer-cleanup tests; all new tests remain unexecuted. Automatic pairing and live sync are not implemented.

Canonical integrity rule for implementations: construct the workspace object without the integrity.digest member, serialize as UTF-8 JSON with lexicographically sorted object keys, no insignificant whitespace, no non-finite numbers and only safe integers (±9,007,199,254,740,991) in settings; calculate lowercase SHA-256 over those bytes and place the hexadecimal result in integrity.digest. Array order is preserved and must be deterministic (records sorted by stable ID; messages kept in conversation order). Validators must enforce both schema limits and an implementation-level total byte limit before allocating/importing records.

Unknown major versions must be rejected. A newer minor version may only be accepted when all unknown fields are explicitly documented as safely ignorable; with the current additionalProperties=false schema, strict rejection is the default. Credentials, tokens, keys, approvals, and device permissions are not portable settings and must be rejected by import implementations.


## 8. Resource profiles

Both platforms expose the same profile names but may choose different budgets based on measured hardware:

- **COMPACT:** constrained RAM/storage; small context, low concurrency, minimal background work.
- **BALANCED:** default after device-specific measurement.
- **REFERENCE:** diagnostics and differential correctness; not a user-facing performance promise.

The 64 MiB target applies only to a compact neural-core experiment. It is not the complete application, language model, memory database, or total process memory target.

The capability registry reports actual limits and active backends. A PC may use a larger local language model than a phone; both still use compatible memory/task semantics. A model unavailable on one device must be reported as unavailable, not silently replaced by a cloud service.

## 9. Security and trust invariants

- Local-first means data stays on the current device unless the user explicitly initiates a transfer or enables a separately described sync feature.
- Desktop and Android each establish their own trusted local identity and device keys.
- Approval grants are device-bound and must not be copied as portable workspace records.
- Any future sync transport must authenticate both endpoints, encrypt in transit, prevent replay, and expose pairing/revocation controls.
- External actions remain disabled until exact-action approval, policy enforcement, durable idempotency, and trusted outcome reconciliation are implemented.
- The current local Bearer token is API access control only; it is not proof of human identity or authorization for side effects.

## 10. Delivery sequence

### Batch 1 — Cross-platform contract (this document)

- Establish PC + Android as first-class targets.
- Record current implementation and gaps accurately.
- Define shared domain semantics and migration boundaries.

### Batch 2 — Desktop local product foundation

- Make the Python local console start cleanly from a user-selected working directory.
- Add OS-neutral configuration/data-directory handling.
- Add a documented PC quick start and launcher.
- Keep loopback binding and current safety limitations.
- Do not claim native desktop packaging yet.

### Batch 3 — Android in-process boundary

- Introduce Kotlin interfaces and a coordinator.
- Move ViewModel away from direct LocalRuntimeBridgeClient ownership.
- Add an in-process local runtime implementation for the first supported operations.
- Keep the Termux bridge as an optional developer adapter.

### Batch 4 — Shared data contract

- Portable workspace export/import uses the shared canonical workspace V1 document.
- Add cross-language golden fixtures consumed by Python and Kotlin.
- Verify deterministic encoding, integrity checks, size bounds, migrations, and conflict handling.

### Batch 5 — Local inference and JARVIS Core

**Implemented at source level:** Android and desktop/PWA now have JARVIS Core conversation orchestrators. They own the turn boundary and call optional LiteRT-LM 0.17.1 adapters for the same manually supplied Qwen3-0.6B INT4 no-think artifact. Android imports through SAF and keeps recent chat in app-private SharedPreferences; desktop uses --model and persists chat in the JPVR-backed portable workspace. Desktop Core retrieves bounded JPVR memory; Android Core retrieves from an in-memory cache of user-approved JPVR records after an authenticated bridge refresh/import/restore.

**Remaining acceptance work:** Android memory cache still depends on the optional Termux bridge and is not a native durable in-process JPVR store. Android and desktop histories remain separate; manual transfer of Android conversation into the shared workspace is not yet implemented. No actual inference, browser flow, model integrity verification, memory relevance evaluation, target-device resource measurement or cross-platform history round-trip has been run.

### Batch 6 — Shared ANS-100 runtime behavior

**Implemented at source level:** the bounded Python ALIF-DT pre-pass uses NeuronRuntime, NetworkRuntime and the Numerical Oracle for desktop Core and the optional Termux endpoint. Android also has a Kotlin in-process ten-neuron implementation with app-private checkpoint persistence; it is the default path when Termux is missing or its signal endpoint is unavailable.

**Limit:** both implementations process explicit request-shape features and return numeric signal summaries. They do not provide semantic understanding, full cognition or adaptive dialogue learning. Kotlin/Python differential equivalence and target-device behavior remain unverified.

- Port the bounded neural state transitions behind an in-process Kotlin or audited portable-native interface.
- Differentially compare Python reference and device implementation using shared fixtures.
- Add versioned checkpoint compatibility and recovery tests.
- Keep dialogue-driven/online learning disabled until stability and resource behavior are established.

### Batch 7 — Optional pairing and sync

- Pair devices with explicit user approval.
- Encrypted `.jws` transfer and conflict-aware import are implemented at source level on desktop and Android; runtime verification remains pending.
- Keep sync optional; offline use must continue if pairing or transport is unavailable.

### Batch 8 — Native desktop packaging and voice

- Package a desktop shell only after the local desktop runtime and data lifecycle are stable.
- Add offline voice separately on each OS/device after selecting and measuring local speech models.

## 11. Acceptance criteria for the first dual-platform milestone

The milestone is reached only when all are true:

1. PC starts JARVIS locally and opens the local UI without a remote service.
2. Android starts its native app without Termux for the supported in-process operations.
3. Both platforms can create, save, reload, and retrieve local conversation/memory records.
4. Both can create and inspect bounded tasks using the same lifecycle semantics.
5. Both accurately report the actual inference backend and its availability, and neither presents unavailable voice features as implemented.
6. Airplane mode or disconnected network does not break the core local flow.
7. A portable workspace exported from one platform imports on the other with stable IDs and integrity validation.
8. Conflicts are surfaced; no silent overwrite occurs.
9. No external action is performed without the platform's own exact-action authorization.
10. PC and Android resource metrics and verification evidence are recorded separately.

Until these acceptance criteria are met, describe the work as a cross-platform architecture and staged implementation, not a completed dual-platform assistant.


## 11. Conversation signal runtime V2 — 14 November 2026

The in-process Android runtime and desktop/Termux Python reference now use a common ten-neuron signal topology:

1. Six bounded request-shape sensors: token volume, lexical diversity, question marker, punctuation density, sentence density and uppercase ratio.
2. Two aggregators: content and request structure.
3. One adaptive ALIF-DT request integrator.
4. One output/attention gate.

The topology contains nine directed connections and uses a 64-event per-turn ceiling in the Python network scheduler; Android also enforces a 64-event per-turn ceiling. The current rule set does not enable dialogue-driven STDP or R-STDP. These features summarize surface form only and are not semantic language understanding.

Both platforms emit JARVIS_ANS100_CONVERSATION_SIGNAL_V2. Android validates returned event, batch and neuron counts and accepts V1 responses only as a compatibility path. The Python local-console endpoint returns V2. A V1 two-neuron Android checkpoint migrates to V2 while retaining prior integrator and output-gate state; unrelated or invalid checkpoints remain fail-closed. A Python V1 JPVR network checkpoint is rebuilt into V2 at load and durably rewritten on the next successful turn. Legacy checkpoints with pending routed events are rejected rather than migrated ambiguously.

Source status: IMPLEMENTED at source level; tests AUTHORED, NOT RUN; Android/desktop execution and resource use NOT MEASURED.
