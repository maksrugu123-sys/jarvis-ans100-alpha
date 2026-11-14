# JARVIS Autonomous Core Milestone V1

**Status:** engineering plan based on the current experimental branch source audit. This document does not claim runtime completion.

## Non-negotiable boundaries

- Work only on `experiment/jarvis-integrated-prototype-v1`; never update `main` without explicit approval.
- Keep the language model as a language interface and optional generator. JARVIS Core owns state, memory selection, planning, policy and action lifecycle.
- No silent learning from raw conversations. Training signals must be explicit, attributable, bounded and auditable.
- No arbitrary shell, filesystem writes, purchases, messages, uploads or other external side effects. Expand action capabilities only with exact-scope approvals, revocation, idempotency and reconciliation.
- Do not claim 10,000-neuron performance from topology capacity. Measure memory, latency, thermal load, flash writes and sustained operation on named devices.
- Source presence, authored tests and static review are not runtime verification.

## Existing source discovered during the 14 November 2026 audit

1. Android already has `AndroidSparseNeuronNetwork`: SQLite-backed sparse LIF-DT event runtime, ceiling 10,000 neurons / 100,000 connections / 100,000 pending events, and a default 128-entry LRU hot set. It is currently initialized and probed manually from the conversation UI; it is not the ordinary chat-turn reasoning network. Its tests are authored, not run.
2. Python already has `NetworkRuntime`, `NetworkJNVMCoordinator`, JPVR checkpoint storage and `NeuronWorkingSet`. The working set is a separate component and must be wired through a network adapter without breaking timestamp-atomic persistence or conflict reconciliation.
3. Android's ordinary turn still uses the ten-neuron `LocalConversationSignalRuntime` and a sparse text-feature classifier. The existing dialog reward is not equivalent to STDP/R-STDP across the 10k spiking graph.
4. Python Core, a bounded symbolic Reasoning Engine, a Task Loop and a visible Playwright browser exist. The 14 November source adds an authenticated read-only action endpoint for approved-memory search and browser search/open/read. The UI invocation and real PC browser/model run remain unverified.
5. PC-side approved-memory peer sync has a TLS listener, revision CAS and deletion tombstones. Android certificate-pinned client, pairing UI, automatic schedule and end-to-end sync are still missing.
6. The Android LiteRT-LM integration and the desktop local inference adapter exist in source. Model download/inference and browser integration have not been verified in a live PC environment.
7. The Android repository currently has no Gradle Wrapper. No build-readiness or APK claim is valid until wrapper/configuration and dependency compatibility are checked.

## Implementation sequence

### A. Independent reasoning loop
- Define a versioned Core turn contract: observation, context selection, candidate hypotheses, explicit unknowns, plan, invariant checks, result evaluation and memory proposal.
- Add deterministic, bounded search/planning with provenance for every fact and explicit abstention when evidence is insufficient.
- Keep the LLM outside the trusted decision boundary: it may explain or suggest, but cannot override policy, invent tool results or authorize execution.
- Add adversarial, multi-step and held-out evaluation tasks; compare against a no-reasoning baseline.

### B. Dialogue learning
- Replace implicit classifier-only feedback with an explicit event schema containing turn ID, time, feature/event IDs, eligibility trace, reward source, baseline and model version.
- Implement and test STDP/R-STDP updates with deterministic replay, delayed reward, neutral feedback, duplicate rejection and rollback.
- Keep separate train/validation/test conversations. No training on evaluation data; no reward inferred from sentiment or user silence.
- Integrate first into a small, bounded subnetwork. Expand only after held-out benefit is demonstrated.

### C. Native Android 10k network
- Preserve the existing SQLite atomic timestamp and LRU design.
- Add task-specific sparse topology, typed input encoding, persistent synapse state and bounded learning updates.
- Connect the network to ordinary conversation as an optional, observable side channel; it must never block or corrupt the chat if the neural path fails.
- Do not call it 10k-neuron reasoning until the network contributes to measured task decisions and passes held-out evaluation.

### D. JNVM working set
- Connect Python `NeuronWorkingSet` through a network adapter with per-neuron load/flush/evict and conflict reconciliation.
- Preserve the coordinator's single committed snapshot identity. Never publish candidate memory before durable COMMIT.
- Add multi-shard, eviction-under-load, restart, uncertain-commit and memory-budget tests.
- Measure resident count, bytes, I/O amplification and latency at 10, 100, 1k and 10k neurons.

### E. Phone/PC memory synchronization
- Implement Android TLS client with certificate SHA-256 pinning, Keystore-backed credentials, bounded JSON, revision CAS and explicit pairing.
- Sync only user-approved memory records and explicit deletion tombstones. Do not sync secrets, raw chats or model files by default.
- Add conflict preview/merge, pause/revoke, retry/backoff and last-sync state.
- Verify against a local PC TLS server using a test certificate before enabling automatic sync.

### F. Controlled action execution
- Connect the existing Task Loop run endpoint to Android and desktop UI with exact task revision and visible preview.
- Keep V1 read-only. Require explicit per-run confirmation even for read-only operations.
- Any future write capability needs a signed, expiring, exact-payload approval, durable revocation, idempotency and result reconciliation. Never expose a general shell executor.

### G. Real PC browser and model verification
- Create a reproducible PC setup with pinned Python dependencies and documented browser install.
- Test visible Chromium launch, search, page open/read, pause/stop, URL and DNS/private-address protections.
- Test local model load, one conversation, context limits, cancellation, restart and no-network offline behavior.
- Record OS, Python, model artifact hash, browser version, RAM and exact commands. No live success claim before those runs.

## Release gates before Android build

1. Restore and commit a compatible Gradle Wrapper.
2. Run Python syntax/import and focused test suites; then the full relevant suite.
3. Run Android unit tests and instrumentation tests on an emulator/device.
4. Run Gradle debug build and inspect APK manifest, package ID, permissions and model/runtime packaging.
5. Install on the target Android device; measure cold start, chat latency, memory, thermal behavior, offline operation and 10k sparse-network load.
6. Record every result in the development log. Failed or unrun checks remain visibly marked.

## Current verification status

All newly authored or previously unrun suites remain **AUTHORED / NOT RUN** until executed. The Android sparse network, PC browser, peer TLS transport, LLM inference and integrated action loop are **SOURCE-LEVEL / NOT VERIFIED** in a live environment. This plan itself is not evidence that any gate has passed.
