# JARVIS Mobile-First Offline Runtime V1

**Status:** architecture decision / implementation contract  
**Date:** 2026-11-13  
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

- Store conversation messages and explicit memories in app-private storage.
- Use transactional writes and schema/version migration.
- Distinguish conversation history from durable user memories.
- Support memory inspection, correction, and deletion.
- Define retention and backup/export behavior; do not store secrets as ordinary memories.

**Acceptance:** data survives process death and device restart; interrupted writes do not corrupt the store; deletion is reflected in retrieval.

### Phase C — Offline response engine

- Add a pluggable local inference backend.
- Evaluate candidate model/runtime combinations on the actual target phone before selecting one.
- Track model file size, peak memory, first-token latency, generation speed, battery/thermal impact, and language quality.
- Provide a small deterministic fallback for commands and app navigation when no language model is installed.
- Never label a fallback or template engine as a full language model.

**Acceptance:** airplane-mode text interaction succeeds using the installed local backend, and the app accurately reports the active backend and its limitations.

### Phase D — Reasoning and task loop

- Execute plans with explicit step limits, deadlines, cancellation, and bounded memory.
- Separate planning from execution.
- Require confirmation before external, destructive, financial, communication, or device-sensitive actions.
- Record outcomes and errors; do not infer success merely because an action was attempted.

**Acceptance:** deterministic task tests prove bounded execution, cancellation, safe failure, and truthful result reporting.

### Phase E — ANS-100 integration

- Integrate the compact neural runtime behind a Kotlin-facing interface.
- Start with inference/state update and checkpoint restore; keep experimental learning rules opt-in.
- Bound neuron count, topology, event queues, per-turn compute, and checkpoint size.
- Use versioned, checksummed checkpoints and atomic replacement.
- Keep research metrics separate from user-facing claims.

**Acceptance:** runtime can initialize, process a bounded input, save, restore, and produce equivalent state under a documented deterministic test.

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

1. Inspect and document the current Android UI-to-runtime call graph.
2. Define local engine interfaces and the coordinator without changing user-visible behavior.
3. Implement a local in-process conversation path that does not depend on the Termux bridge.
4. Add durable local conversation/memory storage behind a repository interface.
5. Add offline-mode regression tests.
6. Only then select and integrate a real on-device language model.
7. Integrate ANS-100 and offline voice as separate, measured stages.

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
