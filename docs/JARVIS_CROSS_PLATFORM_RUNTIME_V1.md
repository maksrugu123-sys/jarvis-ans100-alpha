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
| Desktop UI | PWA served by tools.jarvis_app | Local task/session console and symbolic planner preview, not a full conversational assistant |
| Android UI | Kotlin + Jetpack Compose under app/ | Native V0.5 source exists |
| Android runtime connection | LocalRuntimeBridgeClient → 127.0.0.1:8765 | Requires a separate Python process on the same phone, currently typically Termux |
| Neural runtime | Python NeuronRuntime / NetworkRuntime | Reference coordinators, not production mobile runtime |
| Persistent storage | Python JPVR reference stores, including the shared workspace checkpoint | No native Kotlin JPVR implementation or automatic cross-device sync yet |
| Offline language model | No integrated production local model established | Offline natural-language conversation is not yet a delivered capability |

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
- The current Termux bridge remains a development/compatibility adapter until an in-process runtime replaces it.
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

Manual encrypted export/import is implemented at source level on both platforms; Android uses the Storage Access Framework and desktop uses browser file selection/download. Cross-platform round-trip verification is still pending. Automatic live sync is a later optional feature. Do not expose the local console on 0.0.0.0 or the LAN as a shortcut to synchronization.

### Initial portable schema

The portable data contract is [workspace.schema.json](../schemas/workspace/v1/workspace.schema.json); the encrypted file envelope is [encrypted-bundle.schema.json](../schemas/workspace/v1/encrypted-bundle.schema.json). Desktop PWA and Android source implement encrypted export/import with the same PBKDF2/AES-GCM parameters, canonical workspace validation, stable-ID/key merge and explicit confirmation. A deterministic canonical-workspace interoperability fixture is authored in the Android instrumentation suite; executing it in both runtimes and device verification remain pending. Automatic pairing and live sync are not implemented.

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

### Batch 5 — Local inference

- Evaluate and integrate a local language backend separately on PC and Android.
- Measure memory, storage, latency, throughput, battery/thermal impact, and quality on each target.
- Keep model backend pluggable and report its identity/capabilities.

### Batch 6 — Shared ANS-100 runtime behavior

- Port bounded neural state transitions to Kotlin or a portable native library.
- Differentially compare Python reference and device implementation using the same fixtures.
- Add versioned checkpoint compatibility and recovery tests.
- Do not enable online learning by default until stability and resource behavior are established.

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
5. Both accurately report unavailable inference/voice features.
6. Airplane mode or disconnected network does not break the core local flow.
7. A portable workspace exported from one platform imports on the other with stable IDs and integrity validation.
8. Conflicts are surfaced; no silent overwrite occurs.
9. No external action is performed without the platform's own exact-action authorization.
10. PC and Android resource metrics and verification evidence are recorded separately.

Until these acceptance criteria are met, describe the work as a cross-platform architecture and staged implementation, not a completed dual-platform assistant.
