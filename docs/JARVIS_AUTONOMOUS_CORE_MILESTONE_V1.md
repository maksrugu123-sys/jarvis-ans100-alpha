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

1. Android has `AndroidSparseNeuronNetwork`: SQLite-backed sparse LIF-DT event runtime, ceiling 10,000 neurons / 100,000 connections / 100,000 pending events, and a default 128-entry LRU hot set. Its feed-forward 9,996-feature/four-output topology is initialized explicitly; ordinary chat now routes up to 128 hashed feature IDs through it on a separate executor. Explicit feedback/expected-label selection can apply bounded R-STDP rewards to recorded feature/output eligibility traces. It remains a numeric side channel, not the semantic reasoning network. Its four outputs are not yet competitive: current equal drive/initial weights can trigger all outputs, so labels still come from the separate classifier or the user. Nine instrumentation tests are authored, not run.
2. Python already has `NetworkRuntime`, `NetworkJNVMCoordinator`, JPVR checkpoint storage and `NeuronWorkingSet`. The working set is a separate component and must be wired through a network adapter without breaking timestamp-atomic persistence or conflict reconciliation.
3. Android's ordinary turn uses the ten-neuron `LocalConversationSignalRuntime`, a sparse text-feature classifier, and the separate 10k sparse LIF-DT side channel. The 10k graph now has an explicit, bounded R-STDP reward path, but no evidence yet shows useful semantic generalization or improvement to replies.
4. Python Core, a bounded symbolic Reasoning Engine, a Task Loop and a visible Playwright browser exist. The 14 November source adds an authenticated read-only action endpoint for approved-memory search and browser search/open/read. The UI invocation and real PC browser/model run remain unverified.
5. PC-side approved-memory peer sync has a TLS listener, revision CAS and deletion tombstones. Android now has a source-level HTTPS client with leaf-certificate pinning, private-IPv4 restrictions and manual pairing/sync UI. Automatic scheduling, Android deletion tombstones and end-to-end TLS/device verification remain missing.
6. The Android LiteRT-LM integration and the desktop local inference adapter exist in source. Model download/inference and browser integration have not been verified in a live PC environment.
7. The Android repository currently has no Gradle Wrapper. No build-readiness or APK claim is valid until wrapper/configuration and dependency compatibility are checked.

## Implementation sequence

### A. Independent reasoning loop
- Define a versioned Core turn contract: observation, context selection, candidate hypotheses, explicit unknowns, plan, invariant checks, result evaluation and memory proposal.
- Add deterministic, bounded search/planning with provenance for every fact and explicit abstention when evidence is insufficient.
- Keep the LLM outside the trusted decision boundary: it may explain or suggest, but cannot override policy, invent tool results or authorize execution.
- Add adversarial, multi-step and held-out evaluation tasks; compare against a no-reasoning baseline.

### B. Dialogue learning
- A source-level Python experiment now uses four ALIF-DT output neurons with 256 feature synapses each. Hashed feature events precede a separate decision pulse; only an explicit expected-label selection creates R-STDP reward.
- `DialogRstdpLearningService` persists the network and reward baseline ledger in separate JPVR containers. PREPARED reward proposals are saved before the atomic network reward COMMIT; duplicate feedback IDs are rejected and pending proposals are recovered without a second reward application.
- The experiment and regression tests are AUTHORED, NOT RUN. It is not yet connected to the normal PC/Android chat UI, and no held-out accuracy or learning benefit is claimed.
- Next: connect explicit label-feedback controls to this Python service, build disjoint Russian train/validation/test sets, and compare R-STDP against fixed-weight and no-learning baselines. Do not train on evaluation conversations or infer reward from silence/sentiment.

### C. Native Android 10k network
- Preserve the existing SQLite atomic timestamp and LRU design.
- **Implemented at source level:** the chat-specific feed-forward topology has 9,996 feature neurons and four terminal output neurons (39,984 edges), and ordinary chat routes bounded hashed features to it on a dedicated executor. A V2 DB filename preserves old V1 chain data. **Implemented at source level:** explicit bounded eligibility/reward updates now persist in SQLite for feature-to-output synapses after user feedback or expected-label selection. Tests are authored, not run; no learning-quality or device-performance claim is made.
- Connect the network to ordinary conversation as an optional, observable side channel; it must never block or corrupt the chat if the neural path fails.
- Do not call it 10k-neuron reasoning until the network contributes to measured task decisions and passes held-out evaluation.

### D. JNVM working set
- Connect Python `NeuronWorkingSet` through a network adapter with per-neuron load/flush/evict and conflict reconciliation.
- Preserve the coordinator's single committed snapshot identity. Never publish candidate memory before durable COMMIT.
- Add multi-shard, eviction-under-load, restart, uncertain-commit and memory-budget tests.
- Measure resident count, bytes, I/O amplification and latency at 10, 100, 1k and 10k neurons.

### E. Phone/PC memory synchronization
- **Implemented at source level:** Android has an HTTPS peer client with leaf-certificate SHA-256 pinning, default IP-SAN hostname verification, RFC1918 IPv4-only addressing, the existing Keystore-backed bearer token, bounded JSON and revision CAS. Compose exposes manual pairing by private IPv4 and certificate fingerprint. Only explicitly approved memory records are sent; raw chats, model weights and credentials are excluded.
- Current Android V1 is additive/upsert-only: it does not send deletion tombstones and has no automatic schedule. Pause/revoke controls and end-to-end conflict recovery remain unfinished.
- Android payload/IP/pin tests are authored, not run. Verify against a local PC TLS server before enabling automatic sync.

### F. Controlled action execution
- Android now connects the Task Loop run endpoint for exactly one confirmed `memory.search` read-only step; the client validates task ID/revision, capability and bounded results. Desktop/PWA UI execution remains to be verified.
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

## Implementation update — bounded Android dialogue topology

The Android chat graph no longer uses the generic 10,000-node sparse chain. `initializeSparseDialogueNetwork()` creates 9,996 feature neurons with four terminal readout neurons and 39,984 directed edges. Each active feature neuron can fan out to the four readouts; readout spikes do not propagate further, so a single chat turn cannot leave a long chain cascade queued for later turns. The 10,000 hashed feature IDs map modulo the 9,996 input slots.

The new graph uses a V2 SQLite filename so an existing V1 chain database is preserved. Two instrumentation cases were added for graph counts and bounded feature-to-output propagation. The network remains an observable numeric side channel; it does not learn synaptic weights or control the response. All Android tests remain AUTHORED, NOT RUN.

## Current verification status

All newly authored or previously unrun suites remain **AUTHORED / NOT RUN** until executed. The Android sparse network, PC browser, peer TLS transport, LLM inference and integrated action loop are **SOURCE-LEVEL / NOT VERIFIED** in a live environment. This plan itself is not evidence that any gate has passed.

## Implementation update — Android sparse chat side channel

The first Android integration batch now connects ordinary non-command chat turns to the existing SQLite-backed sparse LIF-DT runtime, provided the user has initialized the 10,000-neuron topology in the UI.

- The dialogue feature classifier exposes at most 128 deterministic hashed feature-slot IDs per turn. Raw message text is not written into the sparse-network database.
- `AndroidSparseNeuronNetwork.scheduleInputs()` writes the same-timestamp feature batch in one SQLite transaction. Duplicate IDs, mixed timestamps, missing neurons and queue overflow reject the batch without partial insertion.
- The ViewModel queues the sparse pass on its dedicated executor. It does not wait for the neural pass before sending the message to JARVIS Core/Qwen. A neural error is reported separately and never fails the ordinary chat turn.
- The Compose status now displays real network counts because escaped Kotlin string templates in the prior UI/probe source are corrected.
- **Boundary:** this network currently contributes only an observable low-level spike signal. It does not yet choose the response, update synaptic weights, or receive STDP/R-STDP reward. Existing 👍/👎 updates still train the separate sparse dialogue classifier. No claim of independent reasoning or full 10k-network dialogue learning is made.

The added batch-input and feature-ID regression tests are **AUTHORED, NOT RUN**. No Kotlin/Python test, Gradle build, APK, device, memory, latency or thermal check was performed for this increment.
