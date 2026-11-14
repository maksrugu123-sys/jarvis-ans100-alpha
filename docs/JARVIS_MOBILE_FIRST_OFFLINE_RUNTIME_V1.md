# JARVIS Mobile-First Offline Runtime V1

**Status:** architecture decision / implementation contract  
**Date:** 2026-11-14  
**Target:** first usable JARVIS running on the user's Android phone  
**Default operating mode:** local-first and offline-capable

## 1. Decision

The first usable JARVIS is an Android application whose essential capabilities do not require a network connection or a separately running Termux process.

The app must be able to start, accept text, maintain local conversation state, retrieve saved memories, execute bounded local reasoning/task steps, and return a response while the device is in airplane mode. Voice is a later local capability and must not be presented as available until offline speech components are actually integrated and verified.

Network access is an optional extension for explicitly requested remote capabilities. It is not a dependency of the core conversation path.

## 2. Product boundary

The initial mobile product is not required to reproduce the capabilities of a large cloud language model. It must provide a dependable, measurable local assistant foundation.

The 64 MiB target applies only to a deliberately small/compact neural core profile, not to the entire Android app, language model, conversation history, indexes, or operating-system overhead. Total process memory must be measured on target devices and reported separately.

Do not claim full natural-language competence, human-like reasoning, continuous learning, or offline voice until the corresponding implementation and tests demonstrate those capabilities.

## 3. Runtime architecture

```text
Android UI (Jetpack Compose)
        |
        v
Conversation / Application Coordinator
        |
        +--> Local Inference Adapter (pluggable; offline model or bounded fallback)
        |
        +--> Reasoning & Task Engine (bounded steps, deadlines, cancellation)
        |
        +--> Memory API
        |       +--> Conversation Store
        |       +--> Durable User Memory
        |       +--> Search / Retrieval Index
        |
        +--> ANS-100 Neural Runtime
        |       +--> compact neuron/network state
        |       +--> bounded adaptation and checkpointing
        |
        +--> Safety / Permission Gate
        |
        +--> Local Storage & Diagnostics

Optional network adapter (disabled from core path by default)
Optional offline speech input/output (later phase)
```

### Ownership rules

- Android owns lifecycle, permissions, UI, foreground/background execution policy, and durable app storage.
- A single application coordinator owns request cancellation, time budgets, and routing between local components.
- Every engine exposes a narrow interface; UI code must not depend on Python reference modules.
- The Python implementation remains a research/reference and evaluation environment unless a specific Android embedding strategy is separately justified.
- The Termux bridge may remain a developer tool during migration, but the end-user app must not require Termux to be installed or running.
- The local inference adapter must be replaceable without rewriting memory, safety, or UI layers.

## 4. Offline contract

The following must work with network access unavailable:

1. App launch and navigation.
2. Text conversation through the installed local response engine.
3. Saving and loading conversation history.
4. Explicit user memory creation, retrieval, correction, and deletion.
5. Bounded local reasoning and task planning.
6. ANS-100 runtime initialization, state persistence, and recovery from an interrupted checkpoint.
7. Safety checks for local actions and clear user confirmation for consequential operations.

The app must show a clear capability state when a feature is unavailable. It must never silently send private conversation or memory data to a remote service.

Optional remote features must be separately permissioned, visibly indicated, and fail without breaking local conversation or memory.

## 5. Android implementation sequence

### Phase A — Local runtime boundary

- Define Kotlin interfaces for conversation inference, memory, reasoning, neural runtime, and safety.
- Introduce a coordinator that routes requests to local implementations.
- Ensure UI ViewModels call the coordinator rather than the Termux bridge directly.
- Keep the bridge behind a developer/compatibility adapter, not as the primary runtime.

**Acceptance:** a local fake/stub engine can run an end-to-end conversation flow without a socket, server, or Termux process.

### Phase B — Durable local memory

**Implemented at source level:** Android retains recent conversation history in app-private SharedPreferences. JARVIS Core can also cache user-approved JPVR workspace memories after an authenticated Termux bridge refresh, confirmed import, or restore, and retrieves at most three lexical matches as untrusted model context. The cache is in-memory, bounded to 2,000 compact records, and cleared when the bridge token is removed. Desktop/PWA retrieves from the portable JPVR workspace directly.

**Not yet complete:** Android does not yet own a native in-process JPVR store for durable user memories; the optional cache requires a workspace snapshot supplied by the loopback service and is not a substitute for a local persistent memory implementation. There is no live cross-device synchronization. Memory retrieval uses keyword overlap, not semantic search.

**Acceptance:** Android must load, inspect, correct, delete, persist and retrieve user-approved memories without Termux; deletion and process/device restart must be covered by authorized tests.

### Phase C — Offline response engine

**Implemented at source level:** Android and desktop/PWA now have optional LiteRT-LM 0.17.1 adapters using the manually supplied Qwen3-0.6B INT4 no-think model artifact (approximately 347 MB). Android imports the exact file through SAF into app-private storage and runs a CPU Engine behind LocalResponseEngine; desktop uses the optional pinned Python package and an explicit --model path. Both use bounded prompt/history/output limits and disable automatic tool calling. The deterministic Android fallback remains available when no model is installed.

**Not yet verified or complete:**
- No actual model has been loaded and used for inference in this development cycle.
- Memory, cold/warm load, first-token latency, generation speed, battery/thermal impact and Russian response quality have not been measured on the target phone or desktop.
- Android checks only the expected filename and size; a trusted model-content digest is not yet checked.
- Generation is synchronous from the background/server execution path; cancellation and token streaming are not implemented.
- The current eight Android tests cover the generic conversation repository/coordinator, not the LiteRT model store/engine. Four desktop fake-runtime tests cover the Python adapter. All are AUTHORED, NOT RUN.

**Acceptance:** NOT YET MET. Airplane-mode text interaction must be demonstrated with the actual installed artifact on the target Android device, with measured memory/latency/thermal behavior and accurate backend reporting. Desktop browser and model runtime must be checked separately; passing one platform does not verify the other.

### Phase D — Reasoning and task loop

- Execute plans with explicit step limits, deadlines, cancellation, and bounded memory.
- Separate planning from execution.
- Require confirmation before external, destructive, financial, communication, or device-sensitive actions.
- Record outcomes and errors; do not infer success merely because an action was attempted.

**Acceptance:** deterministic task tests prove bounded execution, cancellation, safe failure, and truthful result reporting.

### Phase E — ANS-100 integration

**Implemented at source level:** a bounded two-neuron ALIF-DT signal pre-pass now invokes the existing Python NeuronRuntime, NetworkRuntime and Numerical Oracle. It converts transparent request-shape features (token volume, lexical diversity, question punctuation) into neural input events and routes a spike to an attention-gate neuron. Desktop/PWA invokes it directly; Android may request the same pass through the authenticated Termux bridge with a short timeout. This is low-level signal preprocessing only.

**Not yet implemented:** a native in-process Kotlin ANS-100 runtime, full cognitive integration, semantic processing, adaptive learning from dialogue, persistent conversation-linked neural state, and checkpoint restore in the mobile conversation path. Each request currently creates a fresh in-memory two-neuron network; learning and persistence are disabled. Missing Termux does not block Android chat, but it means the optional ANS-100 signal summary is absent.

**Acceptance:** port the selected bounded neural runtime to Kotlin or an audited portable native library, differentially compare Python/device results, and verify versioned checkpoint save/restore and recovery before calling ANS-100 a complete conversation runtime.

### Phase F — Offline voice

- Add on-device speech recognition and speech synthesis only after text interaction is stable.
- Download/install model assets explicitly and show their storage requirements.
- Keep push-to-talk as the initial interaction mode; do not require always-listening microphone access.
- Voice failure must not block text interaction.

**Acceptance:** speech input and output both work in airplane mode on the target device, with measured latency and memory.

## 6. Resource policy

The device is a constrained Android environment. Resource budgets must be measured, not guessed.

Record at minimum:

- cold-start and warm-start time;
- app and runtime peak resident memory;
- model and persistent-data disk usage;
- median and p95 response latency;
- CPU time and battery/thermal behavior for representative sessions;
- checkpoint save/restore duration and size.

Use configurable profiles:

- **COMPACT:** minimum memory and storage; reduced capability.
- **BALANCED:** default profile after device measurements.
- **REFERENCE:** diagnostic mode for correctness and comparison, not the default phone mode.

The 64 MiB neural-core objective is a separate profile target. It must not be reported as the complete app memory footprint.

## 7. Privacy and safety invariants

- Core conversation, memory retrieval, and local reasoning remain on-device.
- Network use is opt-in per capability and must be visible.
- App-private data is protected by Android storage sandboxing; sensitive keys/tokens use Android Keystore-backed storage where appropriate.
- Never log raw secrets, access tokens, or full private conversation content by default.
- All actions with meaningful external consequences require explicit authorization and a result check.
- Provide user controls to inspect and delete durable memories.
- On permission denial, timeout, corrupted data, or unavailable model, fail closed and explain the limitation.

## 8. Verification strategy

No build, emulator run, device run, or test execution is implied by this architecture document.

Before calling a phase complete, record:
- exact command or device procedure;
- environment/device details;
- pass/fail counts;
- skipped tests and reasons;
- measured resource values;
- known limitations and commit reference.

Required offline regression scenarios:
1. Airplane mode enabled before app launch.
2. App restart with network still disabled.
3. Process killed during memory/checkpoint write.
4. Missing or corrupted local model asset.
5. Low-storage and low-memory conditions.
6. User cancels a long-running request.
7. Permission denied for microphone or a protected action.
8. Local response engine unavailable while history and memory remain accessible.

## 9. Immediate implementation priorities

1. **Completed at source level:** Android UI-to-conversation coordinator, replaceable response-engine boundary and in-process path independent of Termux/network.
2. **Completed at source level:** optional LiteRT-LM adapter and manual model import on Android; matching optional local model adapter and PWA endpoint on desktop.
3. **Next implementation batch:** make Android user-approved JPVR memory persistent and retrievable in-process without Termux; then connect Android conversation records to the portable workspace/JWS/JWB contract without removing local-only operation.
4. Port the bounded ANS-100 signal runtime into an in-process Kotlin/native adapter using shared deterministic fixtures. Keep the current Termux signal endpoint optional until the native path exists; do not claim full cognitive integration or enable dialogue learning.
5. Add targeted model-store, inference lifecycle, JPVR retrieval, ANS-100 bridge/runtime, failure, low-memory and conversation-persistence regression tests; keep them AUTHORED, NOT RUN until test execution is explicitly authorized.
5. Add bounded cancellation and streaming/progress reporting so long inference does not leave the user without control.
6. Obtain and validate a trusted model digest, then measure the actual model on the target phone and desktop: cold/warm load, peak RSS, first-token and total latency, token throughput, energy/thermal behavior and Russian quality. Do not infer phone performance from another device's benchmark.
7. Integrate bounded symbolic reasoning behind the local coordinator as a separate verifiable component, and compare Python reference and Android ANS-100 behavior using shared fixtures.
8. Add offline voice only after text interaction, cancellation and memory have been verified.

## 10. Definition of the first usable JARVIS

The first usable release is reached when the installed Android app can, with the phone offline:

- open normally;
- accept and answer text using a clearly identified local response backend;
- preserve and retrieve local conversation history and user-approved memories;
- complete a small set of bounded local tasks;
- recover safely after process termination;
- explain unavailable capabilities honestly;
- keep the core experience independent of Termux and remote servers.

This is a staged engineering target, not a claim that the current repository already satisfies it.


## 2026-11-14 — Built-in ANS-100 signal path with optional Termux acceleration

**Implemented at source level on `mobile-first-offline-v1`:**

- Added `LocalConversationSignalRuntime.kt`, an in-process Kotlin implementation of the bounded two-neuron conversation signal pass. It stores its compact checkpoint in app-private SharedPreferences and does not open a socket or invoke Python.
- The Android ViewModel now runs the in-process signal pass for every ordinary conversation message, whether or not Termux is installed.
- When the authenticated Termux bridge is already connected, the ViewModel prefers its Python ANS-100 signal endpoint. If that endpoint fails, the Android-local signal pass is used for the same turn. A missing Termux installation, missing token, or unavailable bridge does not block the conversation path.
- Added focused Kotlin tests for feature extraction, event bounds, checkpoint persistence across runtime recreation, failed writes, corrupt checkpoints, and punctuation-only input.
- Added a checkpoint version field to the shared signal context. The JPVR memory/workspace administration and encrypted workspace transfer features remain optional Termux-backed extensions in this stage; they are not yet replaced by a native Android JPVR store.

**Important boundaries:**

- The built-in ANS-100 pass is a low-level numeric text-shape preprocessor, not semantic language understanding or a full reasoning engine. Dialogue learning remains disabled.
- The local conversation history and signal checkpoint are app-private. The current approved long-term JPVR workspace cache is still supplied by the optional Termux bridge; native persistent Android user memory is a separate unfinished task.
- The local language model remains separately installed by the user. This change does not bundle model weights or prove successful inference.

**Verification:** source-level edits and cross-file API inspection only. Kotlin tests, Gradle sync/build, APK/AAB, emulator/device execution, airplane-mode run, CI and GitHub Actions: **NOT RUN** by instruction. No runtime behavior is claimed as verified.
