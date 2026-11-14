# JARVIS ANS-100 Alpha

> **Integrated experimental prototype:** see [JARVIS Experimental Integrated Prototype V1](docs/JARVIS_EXPERIMENTAL_PROTOTYPE_V1.md). This branch combines the Android client and PC local console, adds a no-model desktop fallback, and connects separate 10,000-slot sparse dialogue-feature classifiers to the native Android and Python conversation paths. It remains experimental and source-level: no APK, build, runtime or test result is claimed.

**JARVIS ANS-100 Alpha** is a research project for a compact adaptive neural system. Development is staged: begin with one artificial neuron and a small number of synapses, validate behavior and resource cost, and scale only when each previous step is supported by evidence.

The repository has entered the first executable Python reference-orchestration stage. It is still not a production neural runtime, an integrated JPVR-001 prototype, or a finished assistant.

**Platform direction:** PC + Android, local-first and offline-capable. The current implementation boundaries and staged migration are documented in [JARVIS Cross-Platform Runtime V1](docs/JARVIS_CROSS_PLATFORM_RUNTIME_V1.md), [JARVIS Mobile-First Offline Runtime V1](docs/JARVIS_MOBILE_FIRST_OFFLINE_RUNTIME_V1.md), [JARVIS Optional Termux Extension V1](docs/JARVIS_OPTIONAL_TERMUX_EXTENSION_V1.md), [JARVIS Visible Browser Research V1](docs/JARVIS_VISIBLE_BROWSER_RESEARCH_V1.md), [JARVIS PC Quick Start V1](docs/JARVIS_PC_QUICKSTART_V1.md), the [portable workspace schema V1](schemas/workspace/v1/workspace.schema.json), the [encrypted JWS transfer schema](schemas/workspace/v1/encrypted-bundle.schema.json), the [JWB backup payload schema](schemas/workspace/v1/workspace-backup.schema.json), the [JWB encrypted envelope schema](schemas/workspace/v1/encrypted-backup.schema.json), and the [Shared Workspace & Memory V1](docs/JARVIS_SHARED_WORKSPACE_MEMORY_V1.md) / [Workspace Backup & Restore V1](docs/JARVIS_WORKSPACE_BACKUP_RESTORE_V1.md) contracts. These are source-level capabilities and launch targets. An optional offline LLM adapter now exists in source for both platforms, but its runtime behavior and target-device performance are not yet verified; automatic cross-device sync is not implemented.

## Current status — 14 November 2026

- **Python dialogue R-STDP:** `tools/neuron_runtime/dialog_rstdp.py` adds a persistent, JPVR-backed four-output-neuron ALIF-DT readout with 256 feature synapses per output. Text features arrive before a separate decision pulse; explicit user-selected labels create reward-baseline proposals and an atomic multi-synapse R-STDP COMMIT. Feedback IDs are idempotent, interrupted rewards are reconciled, and raw utterances are not persisted. This is a small research classifier, not a 10k network or independent reasoning engine. Tests are AUTHORED, NOT RUN; it is not yet connected to the ordinary PC/Android chat UI.

- **Android↔PC approved-memory sync:** the app now has a manual HTTPS peer client for the PC TLS listener on port 8766, pins the entered leaf-certificate SHA-256, restricts the target to RFC1918 IPv4, and reuses the Keystore-encrypted Termux token. Only explicitly approved memory records are transferred. Sync is additive/upsert-only; deletion propagation and automatic scheduling are not yet implemented. Unit tests and TLS/device runtime are AUTHORED, NOT RUN.

- **Android read-only Task Loop:** the Compose task list now offers a per-run confirmation for one bounded `memory.search` action over user-approved workspace memories. The client sends an exact task revision, validates the returned capability/task/status and at most five excerpts, then refreshes the JPVR snapshot. No shell, browser, file mutation, message or external write capability is exposed from Android. Payload validation tests are AUTHORED, NOT RUN; Termux end-to-end and device UI remain unverified.

- **Android 10k sparse network in chat:** ordinary non-command turns now send up to 128 hashed feature IDs to the SQLite-backed LIF-DT network when the 10,000-neuron topology has been initialized. The batch is inserted atomically and processed on a separate executor, so neural failure cannot block chat. This is an observable numeric side channel only: it does not change Qwen's answer or train LIF synapses. Explicit 👍/👎 feedback still updates the separate four-label dialogue classifier. New tests are AUTHORED, NOT RUN.
- **Peer memory sync transport V1:** PC has an optional private-IPv4 TLS listener, pinned-certificate-ready endpoints and a bounded CAS memory overlay for explicitly approved records with update timestamps and deletion tombstones. Android pinning client/UI and cross-device runtime are NOT YET IMPLEMENTED. Four Python tests are AUTHORED, NOT RUN; see `docs/JARVIS_PEER_MEMORY_SYNC_V1.md`.
- **Read-only Task Loop execution V1:** the authenticated PC local console now exposes an explicit run endpoint for approved-memory search and visible-browser search/open/read only. Each run is revision-checked, dependency-planned, capability-bound, budget-limited and JPVR-checkpointed. Shell and all write/external actions remain unavailable. Four new tests are AUTHORED, NOT RUN; see `docs/JARVIS_SAFE_LOCAL_ACTIONS_V1.md`.
- **Android dialogue reward learning and native JNVM controls:** the app now exposes user-triggered initialization of its 10,000-node sparse SQLite network and bounded impulse probe; chat responses accept explicit 👍/👎 reward on a confident four-label classifier output. Pair eligibility is reward-modulated and checkpointed; it does not silently train from every message. Kotlin/Android tests and device measurements are NOT RUN.
- **Native Android sparse network/JNVM V1:** Kotlin SQLite-backed event network supports source-level ceilings of 10,000 neurons, 100,000 connections/events, with a default 128-state LRU and atomic per-timestamp SQLite commits. It is not yet the ordinary chat network; runtime/device measurements and three instrumentation tests remain NOT RUN. See `docs/JARVIS_ANDROID_SPARSE_NETWORK_JNVM_V1.md`.
- **Native Android symbolic reasoning V1:** a bounded BFS planner now validates preconditions and dependencies for a Core-derived candidate plan before the response engine is called. It reports solved/clarification/no-plan/budget states; it does not establish world facts or authorize actions. Five JVM regression tests are AUTHORED, NOT RUN. See `docs/JARVIS_ARCHITECTURE.md` §3.73.
- **Android link-first web search:** source-level Android Kotlin search now recognizes explicit search phrases and fetches only Bing's HTTPS search-results HTML. It displays up to five titles, short snippets and clickable HTTPS URLs in chat; result pages are not fetched or opened until the user taps a link. The parser and intent tests are authored, not run; live search, Gradle build, APK and device behavior remain unverified.

- **Offline local conversation:** Android and desktop/PWA have source-level JARVIS Core orchestrators that own the conversation turn and call an optional LiteRT-LM 0.17.1 Qwen3-0.6B INT4 no-think language component. Android retains local SharedPreferences history and runs an in-process Kotlin ANS-100 signal pass with a bounded app-private checkpoint. Android's separate 10,000-slot sparse dialogue-feature classifier processes bounded unigram/bigram features for four simple intent labels, abstains while untrained, and learns only when the user explicitly selects a feedback label. It stores numeric weights only and is not the ten-neuron ANS-100 network or a semantic language model; its checkpoint is not interchangeable with Python's BLAKE2-based classifier. Android now also has a source-level resumable in-app downloader for the pinned ~331 MiB Qwen3-0.6B INT4 no-think model, validating size and SHA-256 before publishing it to app-private storage (the base APK does not bundle weights); actual download and inference remain unverified. Android also stores explicitly approved device-local memories in a bounded app-private versioned SharedPreferences store; approved records imported through the authenticated Termux bridge are merged locally. The bridge remains optional and provides shared JPVR workspace, task/session administration, bounded symbolic preview and encrypted transfer; while connected it may provide the Python signal endpoint, with fallback to Kotlin on failure. When Termux is absent or unavailable, ordinary chat and the local signal pass remain in-app. Desktop/PWA retrieves bounded JPVR memories and invokes the Python ten-neuron ALIF-DT pass. The desktop/PWA Python pass persists its ALIF adaptive threshold in a separate JPVR checkpoint; Android's Kotlin pass persists its checkpoint in app-private storage. STDP/R-STDP dialogue learning and trained semantic understanding remain disabled. Both Android and PC JARVIS Core paths now run the bounded JARVIS_UNDERSTANDING_V1 parser and create a JARVIS_REASONING_PLAN_V1 template plan before calling the response engine. The plan carries up to six message-specific focus terms and five intent-specific steps, can request clarification when context references have no recent user turn, and marks recent history as available without claiming to resolve it; execution is always disabled. Qwen only formulates natural-language answers from the original message and Core context. Both no-model fallbacks also receive the Core analysis, but remain limited and cannot answer arbitrary questions. A shared TSV fixture is consumed by both test suites. On PC/PWA, the Core additionally routes non-ambiguous question/explanation/comparison/action-request templates through `ReasoningEngineV01` to validate a bounded candidate DAG; the native Android path has not yet ported that DAG adapter. This remains deterministic heuristic parsing/template planning, not a trained semantic model or evidence-backed general reasoning system; twelve Android understanding tests, three Android Core handoff/history/fallback tests and fifteen Python contract/reasoning-integration tests are AUTHORED, NOT RUN. All capabilities are source-level only; actual model inference, Android/desktop runtime, memory relevance, RAM, latency, thermal behavior and Russian quality have not been verified. Android conversation, local-memory, Termux-extension, Python Core/signal/bridge and desktop model-adapter tests are AUTHORED, NOT RUN. A shared Python/Kotlin ten-neuron fixture is also AUTHORED, NOT RUN. Android chat history and newly created device-local memories remain separate from the portable JPVR workspace/JWS/JWB; local memory is not yet automatically uploaded or synchronized. The store is not native JPVR and application-level at-rest encryption is not added.

- **Visible PC browser research:** [JARVIS Visible Browser Research V1](docs/JARVIS_VISIBLE_BROWSER_RESEARCH_V1.md) now has a source-level V0.1 Playwright controller and authenticated PWA panel. It opens a visible, dedicated Chromium profile, searches, opens and reads the current page, supports explicit pause/manual takeover, and can pass a bounded page excerpt to the local model with an untrusted-data warning; stored replies include the source URL. Playwright >=1.48/Chromium are optional and must be installed separately. No headful browser, PWA interaction or model-assisted page answer has been runtime-verified. The URL/DNS filter is a best-effort guard, not a complete network sandbox; HTTP is GET/HEAD only, WebSockets, downloads and service workers are blocked, and no forms, messaging, purchases, uploads or external actions are enabled.

- **JPVR reference:** the JPVR reference, neuron runtime and local-console suites contain hundreds of authored test methods; the exact repository-wide total has not been re-counted after the shared workspace additions. The COW-integrated typed builder feeds a file-backed two-Superblock storage engine. JPVRStorage.scan_history() audits the full COMMIT/MANIFEST history and validates the OUTBOX chain even for the last verified prefix when a later generation is damaged. JPVRStorage.recover_copy(destination, expected_commit_id=...) can publish that verified prefix as a separate file: it rechecks history under a shared lock, requires the exact commit identity, copies and reads back only verified pages, reconstructs both alternating Superblock slots, and publishes atomically without overwriting an existing path. The original file is never truncated or rewritten. The 23 historical-scan tests and 12 recovery-copy tests are AUTHORED, NOT RUN. The reference writer/scanner/copy-recovery are not yet Android-device-verified or production-ready. In-place repair, compaction, free-page reuse, durable CQE delivery and hardware-level power-loss guarantees remain out of scope. **Neuron checkpoint persistence:** tools/neuron_runtime/jpvr_adapter.py serializes complete one-neuron state/configuration into versioned JREC/DATA or BLOB records, and tools/neuron_runtime/jpvr_store.py now resolves a fixed 100-byte versioned locator in the neuron COW INDEX. JPVRNeuronCheckpointStore.save() commits the checkpoint record and locator in one transaction; load() validates the COMMIT ancestry, DATA/BLOB metadata, CRCs and snapshot schema before restoring NeuronRuntime. A genesis container receives its seven empty index roots in a separate initialization commit. The 17 adapter tests and 17 new store tests are AUTHORED, NOT RUN. The one-neuron facade defaults to a 4096-page materialized catalog. A separate JPVRNetworkCheckpointStore now persists the complete bounded NetworkRuntime topology, pending events, scheduler limits and model clock in one BLOB+CHECKPOINT commit. A bounded RewardBaselineManager now owns per-task ordered feedback and idempotency; JPVRRewardBaselineStore persists its pending/committed state in a dedicated container. The two checkpoints are not yet one atomic Runtime snapshot.
- **Numerical Oracle:** a deterministic Python/float64 reference implementation exists for Adaptive LIF-DT, STDP and R-STDP. It is a research oracle, not the production runtime.
- **Neuron runtime coordinator:** tools/neuron_runtime contains an in-memory Python reference coordinator for one Adaptive LIF-DT neuron and multiple incoming excitatory synapses. It delegates neuron/STDP/R-STDP equations to the Numerical Oracle. Its 15 focused tests were included in the user's full Termux run on 8 November 2026: 216/216 repository tests passed in 19.280 s. This is Python reference verification, not production NCC/runtime or device performance evidence.
- **Atomic network JNVM checkpoint:** `tools/neuron_runtime/network_jnvm_store.py` and `NetworkJNVMCoordinator` now provide a source-level single-container group store plus a fail-closed timestamp coordinator. The coordinator durably records external inputs and rewards, executes at most one complete timestamp per call, commits changed neuron records plus the complete scheduler queue/clock in one JPVR COMMIT, and only then publishes the candidate in-memory state. Uncertain Superblock publication freezes writes until a newly reopened handle proves the exact commit active or proves the exact previous commit remains active; any other state stays frozen. A definite CAS/version conflict marks the coordinator stale until a coherent snapshot is reloaded from a new handle. Focused coordinator and store tests are AUTHORED, NOT RUN. The coordinator still holds the full NetworkRuntime in RAM; the 128-shard per-neuron LRU is not wired as its working set. JPVR page reclamation, compaction and measured 10,000-node operation are not implemented.
- **Multi-neuron network coordinator:** Python NetworkRuntime adds directed delayed connections, deterministic same-timestamp batching, bounded event/run budgets and in-memory timestamp-level candidate commit. The Python reference topology has source-level ceilings of 10,000 neurons, 100,000 directed connections and 100,000 total synapses; these are capacity guards, not performance guarantees. A sparse-chain scaling benchmark covers 1/10/100/1,000/10,000-node profiles, AUTHORED, NOT RUN. Fifteen scheduler tests remain AUTHORED, NOT RUN. The existing network checkpoint persists a whole topology snapshot. A separate per-neuron compact F64/LRU working-set prototype now uses JPVR COW indexes through 128 deterministic shards, but is not integrated into NetworkRuntime and does not provide atomic multi-neuron timestamp commits. Durable records remain JSON JNVM V1; checkpoint loads now use lazy referenced-page reads, while save/planning still has a 4,096-page default catalog bound and no compaction. New codec/working-set tests are AUTHORED, NOT RUN. Android's native Kotlin signal pass still has ten neurons; standalone Android 10,000-neuron execution is NOT IMPLEMENTED. Details: docs/JARVIS_10K_NEURON_SCALING_V1.md and docs/JARVIS_JNVM_WORKING_SET_V1.md.
- **CQE reference:** a partial, single-threaded Python contract reference exists. It now includes age-based queue promotion, a bounded 4,096-entry recent subscriber-event deduplication window, stricter committed-event validation and bounded outbox preflight. Commit store and inbox/outbox remain volatile; durable JPVR integration is not implemented. The latest additions are not yet test-verified.
- **Latest test evidence:** before V4, the user ran 107 tests. Dedicated V4, V4.1 and V4.2 suites passed (12, 15 and 6 tests). On 8 November, V4.3 focused tests passed (7 tests, 1.636 s); smoke and pilot completed. Pilot held-out accuracy: FIXED 81.84%, STDP 100%, frozen R-STDP V4 83.01%, Decision-Latched 82.23%, Neutral 81.84%. Decision-Latched processed all 6,144 inputs and skipped 1,024 post-decision updates, but did not beat V4. Separately, the exact uploaded repository archive passed local container verification on Python 3.13.5/Linux x86_64 after two regression-test corrections: BTCC 17/17, V5.1 tests 6/6, and full repository suite 158/158. These are reference/unit checks, not an Android-device run or task-level BTCC benchmark.
- **Calibration:** Round 1's 99-scenario sweep was generated and reviewed. Round 2's 23-scenario report was generated locally and summarized in docs/CALIBRATION_ROUND2.md. The canonical STARTER parameter profile has not been calibrated or changed.
- **Temporal task V1:** user-run held-out accuracy was FIXED 80%, STDP 90%, R-STDP 80% on one small deterministic challenge. This was preliminary evidence, not proof of generalization.
- **Temporal robustness V2:** user-run held-out accuracy was FIXED 74.375%, STDP 66.25%, rewarded R-STDP 74.375%, neutral R-STDP 74.375%. Rewarded and neutral R-STDP predictions matched on all four seeds; task-level reward benefit was not demonstrated. Details: docs/TASK_VALIDATION_TEMPORAL_V2_RESULTS.md.
- **Temporal robustness V3:** user-run held-out accuracy was FIXED 100%, STDP 98.75%, R-STDP 100%, neutral R-STDP 100%. All 128 training reward modulators were positive, so negative reward correction was not tested. FAST delivered three pulses before the 7 ms deadline while SLOW delivered two; V3 therefore tested deadline-bounded event-density/latency, not same-count interval discrimination. Details: docs/TASK_VALIDATION_TEMPORAL_V3_RESULTS.md.
- **V4 — R-STDP feedback/error recovery:** the exploratory two-seed pilot completed with 12/12 dedicated tests passing. Held-out accuracy: FIXED 81.84%, STDP 100%, R-STDP 83.01%, neutral R-STDP 81.84%. R-STDP used both reward signs, but all 1,355 non-zero reward-driven updates depressed the weight; task-level benefit over FIXED was small (+1.17 percentage points, 6 fewer false positives), while STDP was best. The full pre-registered profile remains pending. Canonical STARTER weight 0.5 is unchanged; 0.70 is only the shared diagnostic initial weight. Details: docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md.
- **V4.1 alignment audit:** user-run Android Termux verification: **15/15 dedicated tests passed** and the two-seed pilot completed 8/8 conditions. R-STDP recorded 2,048 feedback events and 0 positive / 1,355 negative / 693 zero weight updates. The cross-tab showed correct FAST outcomes with negative eligibility (therefore depressing the weight), false-positive SLOW→FAST outcomes with positive eligibility (also depressing it), and correct SLOW outcomes with zero eligibility. Baseline/modulator and product/delta sign checks were consistent. Full repository suite after V4.1 remains NOT RUN. Details: docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md.
- **V5 Compact Adaptive Neuron Core:** the architecture proposal is in `docs/JARVIS_COMPACT_NEURON_CORE_V5.md`. The V5.1 reference resource-baseline harness (`tools/compact_core_v5/resource_baseline.py`) passed 6/6 unit tests and its small 1/10-item smoke profile ran in a Linux x86_64 Python 3.13.5 container. This measures only the Python Oracle reference representation; there is no packed candidate, Android-device baseline, or demonstrated memory benefit.
- **SuperNeuron research track:** BTCC (Boundary-Triggered Counterfactual Credit) has an implemented deterministic reference replay oracle in `tools/super_neuron_btcc/credit_oracle.py`; focused tests passed 17/17 in the local Python 3.13.5 container. It estimates leave-one-event-out credit and compares its direction with central finite-difference gradients. A documented redundancy counterexample shows a known blind spot. BTCC is not integrated into the learning rule and has no task-level accuracy or learning improvement established.
- **V4.2 causal-credit audit:** user-run Android Termux verification: **6/6 dedicated tests passed**; smoke (128 episodes) and pilot (2,048 episodes) completed. In pilot, R-STDP produced 1,355 negative, 0 positive and 693 zero updates. Correct FAST episodes had negative eligibility despite positive feedback; false-positive SLOW→FAST episodes had positive eligibility with negative feedback; correct SLOW episodes had zero eligibility. The 1 ns shadow changed aggregate eligibility magnitude/sign balance but did not change any per-episode eligibility or weight-update signs. This diagnoses a temporal-credit alignment limitation, not a proven fix. Details: docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md and docs/TASK_VALIDATION_R_STDP_V4_2_RESULTS.md.
- **V4.3 decision-boundary temporal credit:** focused tests passed (7/7, 1.636 s), and smoke/pilot completed on Android Termux. In the two-seed pilot, Decision-Latched reached 82.23% held-out accuracy versus 83.01% for frozen V4 R-STDP and 100% for STDP. It processed all sensory inputs and gated exactly 1,024 post-decision plasticity events, but did not improve held-out quality. It remains experimental, not the default. Details: docs/TASK_VALIDATION_R_STDP_V4_3.md and docs/TASK_VALIDATION_R_STDP_V4_3_RESULTS.md.

- **Not implemented yet:** production neuron/NCC runtime, scheduler-owned clock-epoch continuity/rebase policy, general CQE inbox/outbox delivery, in-place JPVR repair/rollback, compaction/free-page reuse, trusted human identity and exact-action approval provider, signed grants, production-grade policy/approval/revocation service, verified downstream idempotency and trusted reconciliation, runtime resource enforcement, production-grade scheduler guarantees, natural-language task parsing/decomposition, automatic safe-action generation and built-in tool execution. Local SafetyPolicy/revocation and SQLite executor-idempotency reference stores now exist in tools/neuron_runtime, but neither authenticates approvers nor guarantees exactly-once external side effects; local file digests are not tamper-proof against the owner. Android Compose V0.5 now connects to the on-device loopback Python console API v2 with a local Bearer token, administers JPVR task/session records, requests a bounded symbolic Reasoning Engine preview and provides a consent-gated shared-memory manager; it still does not invoke Task Loop, Permission & Safety Gate approvals, ActionExecutor or external execution. The local token is transport access control, not a trusted human identity or action approval. Reasoning Engine V0.1, Permission & Safety Gate V1 and Task Loop V0.1 remain bounded Python reference components, not production autonomous-agent infrastructure. One-neuron, network, and task/session stores remain bounded Python reference APIs, not production JNVM services. Non-destructive recovery-copy publication remains a Python reference API, not automatic restoration or production repair.
- **Not measured or validated yet:** target-device runtime performance, RAM/CPU/energy/I/O, float32 runtime equivalence, and JPVR crash consistency.
- No application builds, APK/AAB generation, CI runs or GitHub Actions workflows were performed for these experiments.

## Android application — bridge V0.5 + in-process conversation V0.1

The native Kotlin/Compose app at app/ has a JARVIS Core-owned in-process text conversation path with bounded app-private history (120 messages) and optional LiteRT-LM language generation. The deterministic fallback still recognizes greetings, /help and /status only. Core retrieves up to three explicitly approved memories from an app-private persistent store without Termux; when an authenticated Termux workspace refresh/import/restore occurs, approved shared-workspace records are merged into this local cache and passed to the model as untrusted reference data. Android also runs a local Kotlin ANS-100 signal pre-pass and stores its bounded adaptive checkpoint in app-private storage; while the Termux extension is connected, it may use the Python signal endpoint instead, falling back to Kotlin if that endpoint fails. This is not live sync: Android conversation history and device-local notes remain separate from shared PC/JPVR workspace/JWS/JWB; newly created local notes are not automatically exported. The signal pre-pass remains a low-level numeric diagnostic: it does not perform semantic understanding or STDP/R-STDP dialogue learning. The separate sparse intent classifier is an optional UI side-channel and never controls generated replies or external actions. Twelve Kotlin conversation tests, seven local-memory tests and seven new sparse-classifier tests are AUTHORED, NOT RUN. Contract: docs/JARVIS_ANDROID_IN_PROCESS_CONVERSATION_V0_1.md and docs/JARVIS_CORE_ORCHESTRATION_V1.md. The remaining native app keeps app-private drafts separate from the JPVR runtime and shared workspace. Android-only drafts remain in bounded app-private SharedPreferences (32 sessions, 500 drafts, 240 characters/title). The loopback bridge connects to the Python Termux console at `127.0.0.1:8765`, reads the authoritative TaskSessionManager/JPVR snapshot, creates backend sessions/tasks, changes session status, and cancels queued tasks. Android V0.5 also exposes the bounded `POST /api/reasoning/preview` route and authenticated Workspace API V1 operations. The Compose memory panel supports explicit-consent creation, search, listing and confirmed deletion of shared memory records. The desktop PWA and Android Compose UI support encrypted `.jws` merge transfer via PBKDF2-HMAC-SHA256 (310,000 rounds) and AES-256-GCM. Separately, both interfaces now provide encrypted `.jwb` point-in-time workspace backups and full replacement restore: local envelope/schema/digest checks, same-workspace validation, collection-count preview, acknowledgement that a separate backup of the current revision was saved, second confirmation and server-side CAS. JWB includes only the portable workspace; it does not include the task/session JPVR registry, Android drafts, auth/Keystore material or neural checkpoints. Android uses Storage Access Framework and has nine authored transfer/backup crypto instrumentation tests (NOT RUN). This is manual file transfer, not live sync. The token is encrypted using Android Keystore; the console stores only its SHA-256 digest. The preview does not change the queue or execute actions. The app still does not invoke Task Loop, a trusted approval provider, ActionExecutor or external tools; a created QUEUED task is not an executing task. Android build/install/device verification has **NOT RUN**. Contract: [JARVIS Android App V0.5](docs/JARVIS_ANDROID_APP_V0_1.md), [Android Runtime Bridge V1](docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md), and [Workspace Backup & Restore V1](docs/JARVIS_WORKSPACE_BACKUP_RESTORE_V1.md).

## Repository map
| `tools/neuron_runtime/workspace_store.py` | Shared canonical desktop/Android workspace codec and CAS-protected JPVR memory store (AUTHORED, NOT RUN) |
| `schemas/workspace/v1/encrypted-bundle.schema.json` | Strict envelope schema for password-encrypted offline JWS workspace transfer V1 |
| `schemas/workspace/v1/workspace-backup.schema.json` | Versioned portable workspace backup payload schema JWB V1 |
| `schemas/workspace/v1/encrypted-backup.schema.json` | Separate password-encrypted JWB backup envelope schema V1 |
| `docs/JARVIS_WORKSPACE_BACKUP_RESTORE_V1.md` | Encrypted point-in-time workspace backup, explicit replacement restore, scope and JPVR recovery boundary |
| `app/src/main/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCrypto.kt` | Android PBKDF2/AES-GCM codec for the portable encrypted workspace envelope (AUTHORED, NOT RUN) |
| `app/src/androidTest/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCryptoTest.kt` | Android JWS/JWB crypto, deterministic desktop-WebCrypto JWS and JWB fixtures, cross-format separation, wrong-passphrase, tamper and passphrase-cleanup tests (9 authored, not run) |
| `tools/jpvr_reference/platform_io.py` | POSIX flock/pread/pwrite and conservative Windows msvcrt region locking plus binary seek-based positional I/O (Windows tests AUTHORED, NOT RUN) |
| `tools/jpvr_reference/test_platform_io.py` | Seek-based I/O and same-process locking tests plus Windows-only cross-process region-lock test (5 authored, not run) |
| `tools/neuron_runtime/test_workspace_store.py` | Shared workspace canonicalization, integrity, DATA/BLOB persistence, reopen and CAS tests (AUTHORED, NOT RUN) |
| `docs/JARVIS_SHARED_WORKSPACE_MEMORY_V1.md` | Platform-neutral workspace format, JPVR durability, local API and current cross-device sync boundary |
| `tools/neuron_runtime/task_session.py` | Bounded task/session lifecycle registry and canonical snapshot codec (not durable JPVR storage) |
| `tools/neuron_runtime/test_task_session.py` | Task/session lifecycle, CAS, idempotency, capacity and snapshot tests |
| `docs/JARVIS_TASK_SESSION_LIFECYCLE_V1.md` | Task/session lifecycle V1 contract and limitations |
| `tools/neuron_runtime/task_session_adapter.py` | Canonical versioned JPVR DATA/BLOB codec for task/session snapshots |
| `tools/neuron_runtime/task_session_store.py` | CAS-protected JPVR checkpoint save/load and recovery validation for TaskSessionManager |
| `docs/JARVIS_TASK_SESSION_JPVR_STORE_V1.md` | Durable task/session checkpoint contract and dedicated-container boundary |
| `tools/neuron_runtime/reasoning_engine.py` | Bounded symbolic BFS planner, evidence assessor and observed-result verifier V0.1 |
| `tools/neuron_runtime/test_reasoning_engine.py` | Reasoning planner, bounds, evidence and result-verification tests |
| `docs/JARVIS_REASONING_ENGINE_V0_1.md` | Reasoning Engine V0.1 contract, limits and explicit non-goals |
| `tools/neuron_runtime/permission_safety_gate.py` | Deny-by-default capability, approval, revision and resource authorization gate V1 |
| `tools/neuron_runtime/policy_registry.py` | Atomic local policy snapshot, CAS versioning, approval-ID revocation and fail-closed Gate wrapper |
| `tools/neuron_runtime/executor_idempotency.py` | Bounded SQLite invocation claim/result replay wrapper, unresolved-key inspection and fail-closed UNKNOWN handling |
| `tools/neuron_runtime/test_executor_idempotency.py` | Durable executor ledger tests for replay, recovery, reconciliation, diagnostic listing, concurrency and bounds (21 authored, not run) |
| `docs/JARVIS_EXECUTOR_IDEMPOTENCY_LEDGER_V1.md` | Local executor idempotency ledger contract, crash states and guarantees |
| `tools/neuron_runtime/test_policy_registry.py` | Policy persistence, CAS, integrity, revocation and Task Loop integration tests (17 authored, not run) |
| `docs/JARVIS_PERSISTENT_POLICY_REGISTRY_V1.md` | Local policy/revocation registry V1 contract and limitations |
| `tools/neuron_runtime/test_permission_safety_gate.py` | Permission gate allow/deny, approval binding and budget tests (26 authored, not run) |
| `docs/JARVIS_PERMISSION_SAFETY_GATE_V1.md` | Permission & Safety Gate V1 trust boundary and contract |
| `tools/neuron_runtime/signed_approval.py` | Strict Ed25519 signed-envelope decoder, pinned-key verifier and TrustedApprovalSafetyGate (source-level) |
| `tools/neuron_runtime/test_signed_approval.py` | Signed approval signature, tamper, key trust, expiry, clock-skew and JSON-contract tests (16 authored, not run) |
| `schemas/approval/v1/signed-approval.schema.json` | Portable signed exact-request approval envelope V1 schema |
| `requirements-trusted-approval.txt` | Optional Python cryptography dependency for Ed25519 verification |
| `tools/neuron_runtime/task_loop.py` | Bounded sequential reasoning-authorized task orchestration with injected executor |
| `tools/neuron_runtime/test_task_loop.py` | Task Loop execution, approval, recovery, and combined persistent policy/executor ledger integration tests (22 authored, not run) |
| `docs/JARVIS_TASK_LOOP_V0_1.md` | Task Loop V0.1 flow, durable child-step ledger, idempotency and recovery limits |
| `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt` | Russian Compose dashboard, JPVR task/session administration, reasoning preview and SAF encrypted workspace transfer UI |
| app/src/main/java/org/jarvis/ans100/alpha/data/LocalConversationRuntime.kt | Bounded Android-local conversation repository, response-engine interface and deterministic no-LLM fallback |
| app/src/main/java/org/jarvis/ans100/alpha/data/SharedPreferencesConversationStorage.kt | App-private persistent storage for Android-local chat history |
| app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationRuntimeTest.kt | Ten conversation repository/Core/memory/signal routing tests (authored, not run) |
| app/src/main/java/org/jarvis/ans100/alpha/data/JarvisCore.kt | Android conversation orchestrator, bounded JPVR recall and ANS-100 signal summary contract |
| tools/neuron_runtime/conversation_signal.py | Bounded ten-neuron ALIF-DT signal preprocessor for conversation requests (authored, not run) |
| `tools/neuron_runtime/network_jnvm_coordinator.py` | Fail-closed single-writer timestamp coordinator over the atomic JPVR group store; source-level only |
| `tools/neuron_runtime/test_network_jnvm_coordinator.py` | Timestamp group-commit, durable input, failure isolation, uncertain publication/reopen, stale reload and reward persistence tests (AUTHORED, NOT RUN) |
| tools/neuron_runtime/test_conversation_signal.py | ANS-100 conversation-signal bounds, determinism and gate behavior (authored, not run) |
| `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` | StateFlow UI model for local drafts and asynchronous loopback runtime requests |
| `app/src/main/java/org/jarvis/ans100/alpha/data/LocalDraftRepository.kt` | Bounded versioned Android-only draft/session codec and repository |
| `app/src/main/java/org/jarvis/ans100/alpha/data/SharedPreferencesDraftStorage.kt` | App-private durable storage adapter for the local draft repository |
| `app/src/main/java/org/jarvis/ans100/alpha/data/LocalRuntimeBridgeClient.kt` | API v2 loopback client for JPVR lifecycle, workspace validation, merge preview/commit and memory CRUD |
| `app/src/main/java/org/jarvis/ans100/alpha/data/RuntimeAccessTokenStore.kt` | AES-GCM token storage using Android Keystore |
| `tools/jarvis_app/auth.py` | Digest-only bearer token storage, file permissions and constant-time verification |
| `app/src/main/res/xml/network_security_config.xml` | Deny cleartext by default; allow only loopback for the local runtime bridge |
| `app/src/test/java/org/jarvis/ans100/alpha/data/LocalDraftRepositoryTest.kt` | Nine repository/codec contract tests (authored, not run) |
| `app/src/androidTest/java/org/jarvis/ans100/alpha/data/RuntimeAccessTokenStoreTest.kt` | Android Keystore AES-GCM token persistence, non-exportability, corruption and revocation tests (7 authored, not run) |
| docs/JARVIS_ANDROID_IN_PROCESS_CONVERSATION_V0_1.md | Android-local conversation path, strict history bounds, truthful fallback capability and offline runtime contract |
| `docs/JARVIS_ANDROID_APP_V0_1.md` | Android Compose app V0.5 local-draft, authenticated loopback bridge and symbolic-preview contract |
| `docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md` | Android-to-Termux JPVR task/session bridge, API version, bounds and trust boundary |
| `tools/jarvis_app/server.py` | Loopback-only Python local console with separate task/session and shared-workspace JPVR stores; authenticated workspace save and restore API V1 |
| `tools/jarvis_app/static/index.html`, `app.js`, `styles.css` | Desktop PWA shared-memory manager with explicit consent, search, create, list and confirmed delete |
| `tools/jarvis_app/static/index.html`, `app.js`, `styles.css` | Desktop PWA shared-memory manager: explicit consent, search, list, create and confirmed delete |
| `tools/jarvis_app/test_server.py` | Session lifecycle, API v2 auth coverage, workspace CRUD/CAS, full backup restore, large request, identity, digest and stale-revision tests (18 authored, not run) |
| `tools/jarvis_app/test_auth.py` | Token digest storage, owner-only permissions, one-time bootstrap, rotation, malformed input and symlink-parent/file rejection (11 authored, not run) |
| `docs/JARVIS_LOCAL_CONSOLE_V1.md` | PWA console setup, API contract and local-only safety boundary |

| docs/JARVIS_PROJECT_HANDOFF_2026-11-13.md | Consolidated project handoff: architecture/status, Android V0.5 boundaries, verification truth, safety gates and ordered next steps |
| Path | Purpose |
|---|---|
| `docs/JARVIS_ARCHITECTURE.md` | Main architecture specification, decisions, open questions and development plan |
| `docs/TESTING_POLICY.md` | Required test practices, verification evidence and result labels |
| `docs/DEVELOPMENT_LOG.md` | Append-only record of changes, tests, experiments and limitations |
| `docs/CALIBRATION_ROUND2.md` | Round 1 observations, Round 2 scenarios and verification record |
| `docs/TASK_VALIDATION_TEMPORAL_V1.md` | Initial task-level and V1 protocols/results |
| `docs/TASK_VALIDATION_TEMPORAL_V2.md` | V2 protocol |
| `docs/TASK_VALIDATION_TEMPORAL_V2_RESULTS.md` | V2 results and limitations |
| `docs/TASK_VALIDATION_TEMPORAL_V3.md` | V3 protocol |
| `docs/TASK_VALIDATION_TEMPORAL_V3_RESULTS.md` | V3 results and task interpretation |
| `docs/TASK_VALIDATION_R_STDP_V4.md` | V4 signed-feedback and error-recovery protocol |
| `docs/TASK_VALIDATION_R_STDP_V4_PILOT_RESULTS.md` | Exploratory V4 pilot metrics, interpretation and limitations |
| `docs/TASK_VALIDATION_R_STDP_V4_1_AUDIT.md` | V4.1 eligibility/reward alignment audit protocol and user-run results |
| `docs/TASK_VALIDATION_R_STDP_V4_2_CREDIT_AUDIT.md` | V4.2 per-pulse eligibility and shadow event-order diagnostic protocol |
| `docs/TASK_VALIDATION_R_STDP_V4_2_RESULTS.md` | User-run V4.2 results and causal-credit interpretation |
| `docs/TASK_VALIDATION_R_STDP_V4_3.md` | V4.3 decision-latched temporal-credit protocol |
| `docs/TASK_VALIDATION_R_STDP_V4_3_RESULTS.md` | V4.3 execution status and result summary instructions |
| `docs/JARVIS_COMPACT_NEURON_CORE_V5.md` | V5 compact-state architecture proposal and acceptance gates |
| `docs/JARVIS_SUPER_NEURON_BTCC_RESEARCH.md` | SuperNeuron BTCC learning-quality research hypothesis and acceptance plan |
| `tools/super_neuron_btcc/credit_oracle.py` | BTCC isolated event replay, counterfactual scores and gradient comparator |
| `tools/super_neuron_btcc/test_credit_oracle.py` | BTCC mathematical and failure-mode tests |
| `tools/compact_core_v5/resource_baseline.py` | V5.1 reference-object and process-resource baseline harness |
| `tools/compact_core_v5/test_resource_baseline.py` | V5.1 baseline harness unit tests |
| `tools/numerical_oracle/` | Float64 reference equations, sweeps and tests |
| `tools/neuron_runtime/` | One-neuron coordinator, bounded multi-neuron event loop, JPVR checkpoint adapter and tests |
| `docs/JARVIS_NETWORK_RUNTIME_V1.md` | Multi-neuron event-loop reference contract, queue limits and verification scope |
| `docs/JNVM_NEURON_RUNTIME_JPVR_ADAPTER_V1.md` | Versioned one-neuron checkpoint payload/schema and JPVR DATA/BLOB adapter contract |
| `docs/JNVM_NEURON_CHECKPOINT_STORE_V1.md` | Neuron-index locator, atomic JPVR save/load protocol and validation boundary |
| `tools/neuron_runtime/jpvr_store.py` | Bounded JPVRNeuronCheckpointStore facade for indexed one-neuron checkpoint persistence |
| `tools/neuron_runtime/network_adapter.py` | Canonical versioned snapshot codec for all NetworkRuntime state |
| `tools/neuron_runtime/network_store.py` | Bounded JPVR BLOB+CHECKPOINT persistence for one active network per container |
| `docs/JNVM_NETWORK_CHECKPOINT_STORE_V1.md` | Network topology, pending-event queue and scheduler-clock checkpoint contract |
| `tools/neuron_runtime/reward_baseline.py` | Ordered per-task R-STDP reward baseline owner with prepare/commit and bounded deduplication |
| `tools/neuron_runtime/reward_baseline_adapter.py` | Canonical versioned Reward Baseline Manager snapshot codec |
| `tools/neuron_runtime/reward_baseline_store.py` | Dedicated-container JPVR BLOB+CHECKPOINT persistence with ancestry and CAS validation |
| `tools/neuron_runtime/test_reward_baseline.py` | Reward ownership, ordered feedback, idempotency and snapshot codec tests |
| `tools/neuron_runtime/test_reward_baseline_store.py` | JPVR save/load, CAS, reopen and corruption tests |
| `docs/JNVM_REWARD_BASELINE_CHECKPOINT_STORE_V1.md` | Reward feedback sequence, pending processing, snapshot and persistence contract |
| `tools/neuron_runtime/test_jpvr_store.py` | Focused locator, DATA/BLOB, commit-chain and persistence regression tests |
| `tools/task_validation/` | V1–V4.3 deterministic task benchmarks, audits and tests |
| `tools/cqe_reference/` | Partial executable reference for CQE envelopes, authorization, queues, commits and events |
| `tools/jpvr_reference/` | JPVR_FORMAT_V1 codecs/planners, batch COW INDEX mutation, transaction builder, and file-backed two-Superblock storage and historical recovery scanner |
| `docs/JPVR_COW_WRITER_V1.md` | COW upsert planner contract, validation invariants, test command and explicit non-goals |
| `docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md` | Typed transaction builder and integrated COW mutation pipeline contract |
| `docs/JPVR_STORAGE_ENGINE_V1.md` | Physical file layout, ordered page writes, Superblock publication, reopen and orphan-tail recovery |
| `docs/JPVR_HISTORICAL_RECOVERY_V1.md` | Streaming page/transaction audit, historical MANIFEST validation and safe-prefix diagnostics |
| `docs/JPVR_RECOVERY_REPAIR_V1.md` | Explicit, non-destructive recovery-copy publication and failure model |
| `docs/JPVR_COMMIT_V1.md` | JCOM v1 fixed COMMIT payload, snapshot checks and non-goals |
| `docs/JPVR_JOURNAL_V1.md` | JNRL v1 operation-state entries, immutable history links and lifecycle rules |
| `docs/JPVR_OUTBOX_V1.md` | JOUT v1 immutable event/page and sequence contract |
| `docs/JPVR_OUTBOX_SCAN_V1.md` | Read-only validation of active snapshot, COMMIT chain and committed OUTBOX history |
| `AGENTS.md` | Mandatory contributor/agent instructions and project constraints |

## Run the comprehensive one-neuron exam

To check the implemented neuron as a whole in one run, use:

    python3 -m tools.neuron_validation.full_exam

This is the main scientific/engineering exam. It runs the complete Python unit-test suite, syntax and discovery checks, a deterministic noisy temporal generalization benchmark (FIXED/STDP/R-STDP/neutral controls with separate held-out data and unseen temporal gap bands), and the V5.1 Python-reference resource baseline. It writes neuron_full_exam_report.json and exits non-zero if an automated stage fails.

The default pilot profile uses two seeds, 128 training pairs and 64 held-out pairs per seed at each of three distractor levels (0/2/4). Use --profile smoke for a quick integration check or --profile full for a larger four-seed experiment (0/2/4/6 distractors, up to 10,000 reference objects):

    python3 -m tools.neuron_validation.full_exam --profile smoke
    python3 -m tools.neuron_validation.full_exam --profile full

The report explicitly distinguishes tested mechanisms from NOT_IMPLEMENTED features. It does not claim to test long-term memory, predictive surprise, fatigue/recovery, structural plasticity, JPVR persistence, a packed V5 candidate, a production runtime or Android performance. No learning rule is promoted automatically by the benchmark.

## Run the first-neuron coordinator tests

```sh
python3 -m unittest tools.neuron_runtime.test_runtime -v
```

The first-neuron focused suite is included in the 216/216 full Termux run recorded above. The separate network coordinator test module is tracked below.

## Run the JPVR neuron-runtime checkpoint adapter tests

```sh
python3 -m unittest tools.neuron_runtime.test_jpvr_adapter -v
```

The adapter round-trips one complete NeuronRuntime checkpoint into a versioned JPVR DATA/JREC or BLOB record and restores it after validation. It preserves the caller's 128-bit model-clock epoch. Its 17 tests are AUTHORED, NOT RUN. The adapter remains the record codec; indexed durable persistence is provided by the separate JPVRNeuronCheckpointStore facade below.

## Run indexed JPVR neuron checkpoint-store tests

```sh
python3 -m unittest tools.neuron_runtime.test_jpvr_store -v
```

The 17 store tests are AUTHORED, NOT RUN. JPVRNeuronCheckpointStore initializes seven empty INDEX roots for a genesis container (after preflighting the nine-page minimum catalog bound), then writes the checkpoint record and its versioned neuron-index locator in one JPVR COMMIT. Load resolves the active neuron key, proves the source COMMIT is an ancestor of the active snapshot, verifies the DATA/BLOB page range and record metadata/CRCs, and restores the runtime. The API is bounded by a materialized page catalog (default 4096 pages); it does not persist NetworkRuntime queues, topology or external reward-baseline state. Contract: docs/JNVM_NEURON_CHECKPOINT_STORE_V1.md.

## Run bounded multi-neuron network tests

```sh
python3 -m unittest tools.neuron_runtime.test_network -v
```

The network test module currently has 15 tests, including a regression that prevents events earlier than a restored synapse reward/trace timestamp. They are authored but not yet run against the real Numerical Oracle. The previously reported 216/216 Termux run predates this network module and therefore does not verify the new network layer.

## Run all automated Python checks

From the repository root, run one command:

```sh
python3 -m tools.run_all_checks
```

The master runner performs three checks: (1) compiles every Python source under `tools/` without executing it, (2) checks that every `test*.py` module is discoverable by `unittest`, and (3) discovers and runs the complete Python unit-test suite. New test modules are included automatically when they follow the `test*.py` convention and reside in Python packages.

To save a machine-readable report:

```sh
python3 -m tools.run_all_checks --json-report reports/master_test_report.json
```

A non-zero exit code means a syntax, discovery, or unit-test check failed. This command is the routine all-unit-tests entry point; focused modules may still be run directly while debugging. It does **not** run task-level research benchmarks, parameter sweeps, Android/Termux measurements, application builds, APK/AAB packaging, CI, or GitHub Actions. Those remain separate evidence gates and are never silently treated as passed.

## Run the V4 R-STDP feedback/error-recovery benchmark

```sh
# Exploratory phone-friendly pilot (512/128 pairs, two seeds)
python3 -m tools.task_validation.rstdp_feedback_v4 --profile pilot > rstdp_feedback_v4_pilot_report.json

# Full pre-registered benchmark (8000/256 pairs, four seeds)
python3 -m tools.task_validation.rstdp_feedback_v4 --profile full > rstdp_feedback_v4_report.json
```

The CLI prints progress to stderr while keeping the final JSON on stdout, so redirecting stdout to a report file does not hide progress. The pilot is exploratory and reduced-size; it must not be reported as the full pre-registered result. The JSON now includes the V4.1 per-episode eligibility/reward alignment cross-tab and reward-baseline reconciliation automatically. The full profile is the default. V4 first probes positive and negative eligibility against positive, negative and neutral reward modulation. Its task-level recovery track compares FIXED, STDP, rewarded R-STDP and neutral R-STDP on identical same-count three-pulse data and multiple deterministic seeds. It uses the canonical STARTER weight 0.5 only for a read-only baseline probe. The separately declared diagnostic recovery track starts all four conditions at weight 0.70; that perturbation is not a STARTER change or calibration.

The evaluator supplies reward 1.0 for a correct training decision and 0.0 for an incorrect one. Held-out examples are inference-only. A weight change alone is not success: compare held-out confusion metrics against FIXED and neutral R-STDP across seeds, and also report STDP even if it performs better. The protocol and limitations are in docs/TASK_VALIDATION_R_STDP_V4.md.


## Run the V4.2 pulse-level R-STDP credit audit

~~~sh
# Small smoke profile (32 training pairs, two seeds)
python3 -m tools.task_validation.rstdp_causal_credit_v42 --profile smoke > rstdp_causal_credit_v42_smoke.json

# Phone-friendly pilot (512 training pairs, two seeds)
python3 -m tools.task_validation.rstdp_causal_credit_v42 --profile pilot > rstdp_causal_credit_v42_pilot.json

# Dedicated V4.2 contract tests
python3 -m unittest tools.task_validation.test_rstdp_causal_credit_v42 -v
~~~

V4.2 replays only the V4 R-STDP training split. It records per-pulse pair traces and eligibility contributions, and compares the existing batch update with a separate 1 ns pre→post shadow ordering. The shadow is diagnostic only and cannot change actual training. The full profile is deliberately not the default; start with smoke or pilot on a constrained phone. V4.2 is not a held-out learning benchmark and cannot justify a learning-rule change by itself.

## V5.1 reference resource baseline

```sh
python3 -m unittest tools.compact_core_v5.test_resource_baseline -v
python3 -m tools.compact_core_v5.resource_baseline --profile smoke > v5_reference_baseline_smoke.json
python3 -m tools.compact_core_v5.resource_baseline --profile pilot > v5_reference_baseline_pilot.json
```

The harness measures only the current Numerical Oracle representation. It does not benchmark a compact candidate or claim total runtime savings. Run smoke first; pilot includes up to 1,000 records per class. The 10,000-item full profile is not the first phone run.

## Run the V4.3 decision-boundary temporal-credit experiment

```sh
python3 -m unittest tools.task_validation.test_rstdp_feedback_v4_3 -v
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile smoke > rstdp_feedback_v4_3_smoke.json
python3 -m tools.task_validation.rstdp_feedback_v4_3 --profile pilot > rstdp_feedback_v4_3_pilot.json
```

The smoke profile is deliberately small. The decision-latched condition continues processing each sensory input; it gates only later R-STDP updates after the first output spike. Review the protocol and result-status document before interpreting the report. Smoke and pilot are exploratory and do not replace the full pre-registered profile.

## Run historical temporal benchmarks

V3:

```sh
python3 -m tools.task_validation.temporal_robustness_v3 > temporal_robustness_v3_report.json
```

V2:

```sh
python3 -m tools.task_validation.temporal_robustness_v2 > temporal_robustness_v2_report.json
```

These are historical reference experiments. Their recorded results and limitations are in the corresponding V2/V3 documents.

## Other reference experiments

Initial burst-vs-sparse benchmark:

```sh
python3 -m tools.task_validation.temporal_patterns
```

Fixed-baseline boundary characterization:

```sh
python3 -m tools.task_validation.baseline_boundary > baseline_boundary_report.json
```

Round 1 exploratory parameter sweep:

```sh
python3 -m tools.numerical_oracle.calibration_sweeps > sweep.json
```

Round 2 behavioral characterization:

```sh
python3 -m tools.numerical_oracle.calibration_round2 > calibration_round2.json
```

These commands are reference experiments/diagnostics. They do not calibrate or modify the canonical STARTER profile.

## Important engineering boundaries

- JPVR is persistent storage backed by a physical device; it is not additional physical RAM.
- Python packages under `tools/` are reference/validation tools, not the production JARVIS runtime.
- A passing unit-test suite verifies only the assertions covered by those tests. It does not prove task usefulness, biological equivalence, consciousness, general intelligence or production readiness.
- Keep the original `JARVIS_SINGLE_NEURON_STARTER_V1` parameter set immutable. Any future calibrated profile must have its own version and reproducible evidence.
- Do not run application builds or GitHub Actions without explicit user permission. See `AGENTS.md`.

For detailed status, unresolved decisions and the next implementation gates, start with [the architecture document](docs/JARVIS_ARCHITECTURE.md).


## Run JPVR reference tests

Focused COMMIT descriptor tests:

```sh
python3 -m unittest tools.jpvr_reference.test_commit \
 tools.jpvr_reference.test_journal -v
```

Focused JOURNAL tests:

```sh
python3 -m unittest tools.jpvr_reference.test_journal -v
```

Focused OUTBOX codec/genesis tests:

```sh
python3 -m unittest tools.jpvr_reference.test_outbox -v
```

Focused committed-history scanner tests:

```sh
python3 -m unittest tools.jpvr_reference.test_outbox_scan -v
```

Complete JPVR reference suite:

```sh
python3 -m unittest tools.jpvr_reference.test_codec tools.jpvr_reference.test_records tools.jpvr_reference.test_blob tools.jpvr_reference.test_data tools.jpvr_reference.test_layout tools.jpvr_reference.test_manifest tools.jpvr_reference.test_index tools.jpvr_reference.test_cow tools.jpvr_reference.test_commit tools.jpvr_reference.test_journal tools.jpvr_reference.test_outbox tools.jpvr_reference.test_outbox_scan tools.jpvr_reference.test_transaction tools.jpvr_reference.test_typed_transaction tools.jpvr_reference.test_storage tools.jpvr_reference.test_recovery tools.jpvr_reference.test_recovery_copy -v
```

The previously documented baseline contained 262 methods across twelve modules. Since then, 16 transaction-assembly, 19 typed-builder, 11 integrated COW, 19 storage, 23 historical-recovery, and 12 recovery-copy test methods were added, for **362 methods across seventeen modules**. These 100 newer methods are **AUTHORED, NOT RUN** as a combined post-change suite; the complete command above includes all seventeen modules. See the [COW contract](docs/JPVR_COW_WRITER_V1.md), [JCOM contract](docs/JPVR_COMMIT_V1.md), [JOURNAL contract](docs/JPVR_JOURNAL_V1.md), [OUTBOX contract](docs/JPVR_OUTBOX_V1.md), [OUTBOX scanner contract](docs/JPVR_OUTBOX_SCAN_V1.md), [transaction assembly contract](docs/JPVR_TRANSACTION_ASSEMBLY_V1.md) and [typed transaction builder contract](docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md).


## JPVR transaction assembly planner

Focused transaction assembly tests:

```sh
python3 -m unittest tools.jpvr_reference.test_transaction -v
```

The in-memory planner assembles encoded staged pages, a final MANIFEST, and a final COMMIT into one validated append plan. It checks page numbering/generation/transaction identity, COMMIT and previous-COMMIT linkage, all MANIFEST roots/high-water references, and the candidate inactive Superblock. Contract: [JPVR_TRANSACTION_ASSEMBLY_V1.md](docs/JPVR_TRANSACTION_ASSEMBLY_V1.md). This is a reference planner only: no physical writes, flush/sync, Superblock publication, locking, or crash recovery.


The 2026-11-10 CQE priority-aging and JPVR OUTBOX scanner regressions were subsequently corrected; the user has confirmed that no errors remain in their current full-suite run. The 16 transaction-assembly tests, 19 typed-builder tests, 11 integrated COW tests, 19 storage tests and 22 historical-recovery tests remain AUTHORED, NOT RUN; they require focused execution on Termux.


## JPVR typed transaction builder

Focused typed builder tests:

```sh
python3 -m unittest tools.jpvr_reference.test_typed_transaction tools.jpvr_reference.test_cow -v
```

The builder accepts typed JREC, BLOB, INDEX, JOURNAL and OUTBOX inputs; packs DATA records without splitting them, fragments large BLOB records, resolves JOURNAL/OUTBOX links to the final COMMIT page, and emits MANIFEST plus final COMMIT. `IndexMutationInput` invokes one batch COW rewrite per affected tree, reuses unchanged subtrees, creates new INDEX pages, and updates the affected MANIFEST roots. It revalidates generated page families and delegates final snapshot validation to the transaction assembly planner. Contract: [JPVR_TYPED_TRANSACTION_BUILDER_V1.md](docs/JPVR_TYPED_TRANSACTION_BUILDER_V1.md). This remains in-memory only; it does not reserve physical pages, write durable storage, flush/sync, publish Superblock slots or recover after crashes.


## JPVR file-backed storage engine V1

Focused storage and recovery tests:

```sh
python3 -m unittest tools.jpvr_reference.test_storage -v
```

Create or reopen a container with `JPVRStorage.create(path)` / `JPVRStorage.open(path)`. Use `load_snapshot(max_pages=4096)` to obtain one active Superblock and its materialized bounded catalog under a single shared lock, or `load_page_catalog(max_pages=4096)` when only the catalog is needed. Submit a `TypedTransactionBuildPlan` to `store.commit(plan)`. The default catalog cap is 4096 pages (16 MiB encoded bytes plus Python mapping overhead); increase it only after considering available RAM.

Storage commits append every immutable transaction page and call `fsync` before writing the inactive Superblock slot. The new slot is synced and re-read before success is returned. A `JPVRCommitUncertainError` means publication began but its final outcome could not be confirmed: reopen the file and inspect `active_commit_id` before retrying. Contract and failure model: [JPVR_STORAGE_ENGINE_V1.md](docs/JPVR_STORAGE_ENGINE_V1.md). The Python reference now routes file locking and positional I/O through a POSIX/Windows adapter; the Windows path is source-level only and has not been run on Windows 10/11. The Android app does not embed this Python JPVR reference engine. It can use the optional authenticated Termux extension as a local-console client for shared JPVR and task/session services; without Termux, native conversation and the in-process ANS-100 signal pass remain available, but the Python JPVR storage engine is not available inside Android. The reference engine does not provide automatic prefix rollback, compaction, CQE event delivery, or a guarantee against hardware that falsely reports completed flushes.


## JPVR historical recovery scanner

Focused tests:

```sh
python3 -m unittest tools.jpvr_reference.test_recovery -v
```

JPVRStorage.scan_history() uses a lazy file-backed page catalog rather than load_page_catalog(). It reads the durable prefix in physical order, verifies every page envelope, each generation's contiguous append range and transaction identity, final MANIFEST/COMMIT ordering, every historical MANIFEST's roots and high-water references, and the committed OUTBOX chain. It returns one JPVRHistoricalSnapshot per verified generation and verified_durable_tail_page_no as the last safe commit boundary.

A damaged later generation does not erase earlier verified snapshots from the report. issue describes the first detected fault. Bytes beyond the active durable tail are reported as orphan_tail_bytes; scanning is read-only and does not truncate, rewrite a Superblock, or claim to have restored that prefix. Set verify_index_trees=True to traverse all seven B+ trees in every historical snapshot; this may be expensive. Contract: docs/JPVR_HISTORICAL_RECOVERY_V1.md.

## JPVR non-destructive recovery copy V1

Focused tests:

```sh
python3 -m unittest tools.jpvr_reference.test_recovery_copy -v
```

After scan_history(), call store.recover_copy("recovered.jpvr", expected_commit_id=report.last_verified_snapshot.commit.record.commit_id). The operation performs its own locked rescan; the supplied identity must match the last verified snapshot. It refuses an invalid or unverifiable OUTBOX prefix and any existing destination, copies only through the verified COMMIT page, reconstructs the active and immediately previous Superblock slots, fsyncs and reads back the temporary copy, then publishes it without replacing an existing path. The original file is not modified. A JPVRRecoveryCopyUncertainError means the destination may already be published; inspect it before retrying. The operation requires same-directory hard-link support. Twelve focused tests are AUTHORED, NOT RUN.


## Save and restore the complete multi-neuron network

    python3 -m unittest tools.neuron_runtime.test_network_adapter -v
    python3 -m unittest tools.neuron_runtime.test_network_store -v

JPVRNetworkCheckpointStore(storage).save(network_id, network, clock_epoch_id=...) writes one canonical JNNW BLOB plus one JNCP CHECKPOINT page in the same JPVR COMMIT. The active MANIFEST points to that page. load(network_id) checks source COMMIT ancestry, BLOB/page transaction metadata, schema, identity and CRC before restoring the entire NetworkRuntime, including delayed pending events and scheduler clock. There is one active network checkpoint pointer per JPVR container. Eleven codec tests and fourteen store tests are AUTHORED, NOT RUN. Contract: docs/JNVM_NETWORK_CHECKPOINT_STORE_V1.md.


## Persist R-STDP reward baselines

Focused reference tests (authored, not run):

```sh
python3 -m unittest tools.neuron_runtime.test_reward_baseline -v
python3 -m unittest tools.neuron_runtime.test_reward_baseline_store -v
```

`RewardBaselineManager` owns a bounded baseline per `reward_task_id`. A proposal is bound to its manager ID and clock epoch; a feedback ID cannot be reused at a different sequence inside retained task history. A pending proposal is bound to its manager ID and clock epoch; a feedback ID cannot be reused at a different sequence inside retained task history. `prepare_feedback()` validates clock epoch, monotonic model time, reward range and the exact next feedback sequence, then reserves an immutable proposal using the previously committed baseline. The baseline changes only when `commit_feedback()` receives a terminal synapse-processing status (`APPLIED`, `APPLIED_NO_CHANGE`, `NO_ELIGIBILITY`, `EXPIRED` or `DISABLED_NO_OP`). Exact retries are idempotent within the retained receipt window; gaps are deferred and conflicting replays are rejected.

`JPVRRewardBaselineStore(storage).save(manager)` writes a canonical JRBM BLOB plus a JRCP CHECKPOINT descriptor and publishes both with one MANIFEST/COMMIT. It verifies source COMMIT ancestry, object/schema/version identity, page bounds and CRC, and supports `expected_record_version` compare-and-swap. Use a **dedicated JPVR container** for this V1 store: the MANIFEST has one active checkpoint pointer, so saving the reward manager into the network container would replace its network checkpoint. The standalone manager store does not coordinate NetworkRuntime. The later JRCM composite store and `process_reward_feedback()` workflow provide a combined path; see the two sections below. Contract: `docs/JNVM_REWARD_BASELINE_CHECKPOINT_STORE_V1.md`.

The 26 manager/codec tests and 15 JPVR store tests are **AUTHORED, NOT RUN**. This standalone manager store is not itself a reward-to-synapse transaction or combined Runtime checkpoint; those are separate later layers described below.


## Atomic composite JARVIS Runtime checkpoint

```sh
python3 -m unittest tools.neuron_runtime.test_runtime_store -v
```

`JPVRRuntimeCheckpointStore(storage).save(network_id, network, reward_baseline_manager)` stores complete NetworkRuntime and RewardBaselineManager snapshots under one JRCM V1 CHECKPOINT page and one JPVR COMMIT. Both component records share the same commit generation, transaction ID and caller-owned model clock epoch. `load(network_id, manager_id)` validates exact source COMMIT ancestry, both component locators, DATA/BLOB schemas and CRCs, and matching clock epochs before restoring either object. `expected_runtime_version` protects the pair with one compare-and-swap version.

Component adapters choose DATA for payloads up to 3976 bytes and BLOB for larger payloads. Standalone network and reward-baseline stores accept both forms too. Use a dedicated runtime JPVR container for JRCM; do not share it with standalone JNCP/JRCP pointers. This layer provides atomic checkpoint publication only. The higher-level `process_reward_feedback()` coordinator now prepares candidate synapse/manager state and publishes both through this composite store. Twelve composite-store tests are AUTHORED, NOT RUN. Contract: `docs/JNVM_COMPOSITE_RUNTIME_CHECKPOINT_V1.md`.


## Run the atomic R-STDP feedback transaction tests

```sh
python3 -m unittest tools.neuron_runtime.test_feedback_transaction -v
```

`process_reward_feedback()` clones the network and baseline manager, applies all selected synapse rewards to candidate state, and commits both snapshots through one composite JRCM JPVR transaction. The function leaves caller objects untouched; replace them with the returned network and manager after success. The runtime version is mandatory for compare-and-swap. See `docs/JNVM_ATOMIC_REWARD_FEEDBACK_TRANSACTION_V1.md`.


## Reasoning Engine V0.1

Run the focused structured-reasoning contract tests (authored, not run):

```sh
python3 -m unittest tools.neuron_runtime.test_reasoning_engine -v
```

`ReasoningEngine` performs bounded deterministic breadth-first planning over caller-supplied symbolic facts and pure actions, groups only explicitly reviewed evidence by hypothesis, and compares caller-supplied observed facts with a plan's goal. Pending, rejected, or unverified-quality evidence does not affect a hypothesis assessment. The VERIFIED marker and review reference are caller-supplied provenance, not cryptographic authentication. It returns a shortest action-count plan when one is found. It does **not** execute actions, interpret natural language, retrieve/authenticate evidence, or authorize tools. Thirty-one focused tests are AUTHORED, NOT RUN. Contract: [JARVIS_REASONING_ENGINE_V0_1](docs/JARVIS_REASONING_ENGINE_V0_1.md).

## Durable CQE reward-feedback inbox

process_cqe_reward_feedback() preflights all targets on a detached network copy, then persists the normalized reward EVENT and its target/context digest as a pending JRCM checkpoint before applying synapse rewards to authoritative state. The completed receipt, baseline advance and network updates are then published together. After interruption, reload the active JRCM checkpoint and retry the exact envelope with its latest runtime version. This adapter handles reward-feedback only; upstream source authorization and external schema registration are required; general CQE events remain volatile. Contract: docs/JNVM_CQE_REWARD_FEEDBACK_INBOX_V1.md. Twelve focused tests are **AUTHORED, NOT RUN**.


## Task & Session Lifecycle V1

Run focused reference tests (authored, not run):

\`\`\`sh
python3 -m unittest tools.neuron_runtime.test_task_session -v
\`\`\`

The bounded TaskSessionManager provides explicit session/task states, revision checks, idempotent task creation, ordered lifecycle events and a canonical snapshot codec. Its state can now be persisted with `JPVRTaskSessionCheckpointStore` as one versioned DATA/BLOB record plus a JTSC CHECKPOINT descriptor in one JPVR COMMIT, then restored after reopening the file. This is a single-writer Python reference store; use a **dedicated JPVR container** because MANIFEST has one active CHECKPOINT pointer. It does not persist external side effects, scheduler leases, authorization grants or CQE delivery, and does not guarantee exactly-once tool execution. Contracts: [lifecycle](docs/JARVIS_TASK_SESSION_LIFECYCLE_V1.md) and [JPVR store](docs/JARVIS_TASK_SESSION_JPVR_STORE_V1.md). Fifteen lifecycle tests and fourteen JPVR adapter/store tests are **AUTHORED, NOT RUN**.


### Task & Session JPVR Persistence V1 (12 Nov 2026)

Focused persistence tests:

```sh
python3 -m unittest tools.neuron_runtime.test_task_session_store -v
```

TaskSessionManager now has a versioned JPVR DATA/BLOB adapter and a CAS-protected checkpoint store. It commits the snapshot record and CHECKPOINT descriptor together, validates source COMMIT ancestry and payload CRC on load, and restores the last committed task/session registry after reopening the JPVR file. Use a **dedicated JPVR container**; the format currently has one active CHECKPOINT pointer. This does not provide exactly-once external tool execution, scheduler leases, authorization persistence, or multi-process locking. Contract: [JARVIS_TASK_SESSION_JPVR_STORE_V1](docs/JARVIS_TASK_SESSION_JPVR_STORE_V1.md). Fourteen focused tests are AUTHORED, NOT RUN; no builds or CI were run.
## Permission & Safety Gate V1

Run the focused contract tests (authored, not run):

```sh
python3 -m unittest tools.neuron_runtime.test_permission_safety_gate -v
python3 -m unittest tools.neuron_runtime.test_signed_approval -v
```

The deny-by-default gate binds each action to current manager/task/session revisions, exact capability/scope, effect, resource budget, retry index and policy digest. A new source-level Ed25519 verifier and TrustedApprovalSafetyGate verify signed exact-request envelopes against a pinned in-memory approver-key snapshot; the trusted variant rejects legacy unsigned grants. The envelope schema and optional cryptography dependency are recorded in the repository. This is not yet a production identity service: Android Keystore signing/enrollment, desktop signer, authenticated trust-root provisioning, signed key rotation/revocation and rollback protection remain unimplemented. Android/PWA do not yet use the verifier, and external actions remain disabled. The legacy gate's 26 tests and the new 16 signed-approval tests are AUTHORED, NOT RUN. Contract: [Permission & Safety Gate V1](docs/JARVIS_PERMISSION_SAFETY_GATE_V1.md) and [architecture §3.53](docs/JARVIS_ARCHITECTURE.md#353-signed-exact-request-approval-verification-v1).

## JARVIS Task Loop V0.1

Run the focused reference tests (authored, not run):

```sh
python3 -m unittest tools.neuron_runtime.test_task_loop -v
```

The sequential Task Loop connects the structured Reasoning Engine to TaskSessionManager and the Permission & Safety Gate. Every step is re-authorized immediately before an injected executor is called, then checked against observed postconditions and final goal facts. Each plan step now has a deterministic child TaskRecord: its RUNNING intent is committed to the same JPVR task/session checkpoint before the executor is called, and a known result is recorded with a digest. On recovery, the same action idempotency key is replayed; completed-step responses are checked against the stored digest. A local DurableIdempotencyExecutor SQLite wrapper is now available: it persists the invocation claim/result and replays known results without calling the delegate again. IN_PROGRESS/UNKNOWN keys are not blindly retried; a separate authoritative reconciler is required. This is not a shared JPVR/external transaction and does not prove exactly-once downstream side effects. Non-read-only actions require a dedicated task/session JPVR checkpoint store and CAS version. WAITING retains the parent RUNNING revision while approval or outcome reconciliation is pending. Twenty-two Task Loop tests and twenty-one executor-ledger tests are AUTHORED, NOT RUN. The Task Loop suite includes a composition test that runs a bounded read-only plan with both PersistentPolicySafetyGate and DurableIdempotencyExecutor. The executor-ledger suite explicitly checks that an unresolved key remains UNKNOWN after the wrapper is reopened and is not dispatched to a replacement delegate. The Task Loop suite includes a durable-wrapper recovery test: an UNKNOWN execution is not redispatched; after an explicit result reconciliation, the loop resumes the next step. Contract: [JARVIS Task Loop V0.1](docs/JARVIS_TASK_LOOP_V0_1.md) and [Durable Executor Idempotency Ledger V1](docs/JARVIS_EXECUTOR_IDEMPOTENCY_LEDGER_V1.md).



## Native Android application V0.5

The native Compose application provides app-private local drafts and a separate JPVR runtime panel. The runtime panel connects to the local Python console at 127.0.0.1:8765 and can read/create backend sessions/tasks, pause/resume/close sessions with revision checks, cancel QUEUED tasks and request a non-mutating symbolic reasoning preview. The local draft store remains independent and is persisted in SharedPreferences using JARVIS_ANDROID_DRAFTS V1 (32 sessions, 500 drafts, 240 characters/title, 512 KiB maximum encoded state).

The Android and PWA clients authenticate to local-console API v2 using a 43-character random Bearer token printed once by Termux. The Python process stores only the SHA-256 digest. POSIX uses owner-only file permissions; Windows relies on inherited user-profile ACLs, which the current standard-library adapter does not audit or configure. Android encrypts the token with Android Keystore; the PWA retains it only in the current tab's sessionStorage. To revoke/replace a lost token, stop the console and restart with `--rotate-token`.

The local draft repository is **not** the JPVR TaskSessionManager store. The separate runtime panel reads authoritative backend task/session records through the authenticated local-console API, but it does not call Permission & Safety Gate approval workflows, Task Loop or ActionExecutor. A created backend task remains QUEUED; no execution or approval is implied. The service remains bound only to loopback. Token possession is not a trusted human identity or exact-action approval.

Android repository tests:

```text
app/src/test/java/org/jarvis/ans100/alpha/data/LocalDraftRepositoryTest.kt
```

Nine Android JVM local-repository tests, seven Android Keystore instrumentation tests and twenty-seven Python console/API-auth tests and eleven shared workspace-store tests are **AUTHORED, NOT RUN**. Kotlin compilation, Gradle sync/build, APK/AAB, emulator/device installation, UI tests, Android↔Termux networking and Android performance measurements are **NOT RUN**. The repository currently has no Gradle wrapper script; Android Studio/system-Gradle compatibility has not been verified. Contracts: [JARVIS Android App V0.5](docs/JARVIS_ANDROID_APP_V0_1.md), [Android Runtime Bridge V1](docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md) and [Local Console V1](docs/JARVIS_LOCAL_CONSOLE_V1.md).

## Separate local PWA console

The Python local console can be started from the repository root:

```sh
python3 -m tools.jarvis_app --port 8765
```

On first startup, copy the one-time token from Termux into the PWA login panel or the Android app. Open `http://127.0.0.1:8765/` on the same device. The same running console serves both frontends and the JPVR-backed TaskSessionManager; Android SharedPreferences drafts remain separate. The service binds only to loopback. The console/API-auth and workspace endpoint tests are **AUTHORED, NOT RUN**. The desktop PWA memory UI is source-level only; browser verification has not been run.

## Offline local LLM (opt-in)

JARVIS now has source-level adapters for one optional local model on Android and desktop/PWA. Both paths use the same LiteRT-LM model artifact: `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm` from the [Qwen3-0.6B INT4 model repository](https://huggingface.co/litert-community/Qwen3-0.6B-int4). The file is about 347 MB and is **not** included in the APK or repository. You must obtain it separately.

**Android:** open the JARVIS conversation panel and choose **Выбрать модель**, then select the exact `.litertlm` file in Android's file picker. The app copies it to private app storage. It does not download the model. The runtime uses CPU, a 1,280-token context ceiling and a 96-token output ceiling. Loading is refused when Android reports low memory or less than 1,280 MiB available; this is only a guardrail, not an assurance that the model fits every phone.

**Desktop/PWA:** install the optional runtime with `python3 -m pip install -r requirements-litert-lm.txt`, then start the local console with:

```sh
python3 -m tools.jarvis_app --model /absolute/path/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm
```

LiteRT-LM 0.17.1 is pinned on both platforms. The short no-think model is a compact first inference target, not a high-capability reasoning model. Russian quality, first-load time, speed, RAM and heat have not been measured on the target phone or PC.

The desktop chat persists in `records.conversations` in the portable workspace. Android chat still uses app-private SharedPreferences and is **not yet copied into JWS/JWB**; histories do not sync automatically between PC and Android. A manual cross-platform conversation transfer step remains.

No network fallback, model auto-download, tools, shell access or external action execution is enabled. Voice inference is not included.


- **Android app-private memory:** source-level local approved-memory store and Compose management panel are present without Termux. The versioned SharedPreferences codec is bounded (2,000 records, 700 content chars, 64 category chars) and publishes a new cache only after a successful commit. App-local notes are not yet synced to or exported into the PC JPVR workspace. Memory tests are AUTHORED, NOT RUN.
- **ANS-100 Kotlin/Python parity contract:** both test suites consume app/src/test/resources/ans100/conversation-v2.tsv. It compares three fixed requests across features and network output metrics. Tests are AUTHORED, NOT RUN, so parity is not verified.


- **Visible PC browser V0.1:** source-level Playwright controller, dedicated persistent profile, authenticated browser API, PWA panel, manual takeover and model question with bounded untrusted page text/source URL are present. Optional setup: install `playwright>=1.48` via `pip install -r requirements-browser.txt`, then `playwright install chromium`. Browser/UI/Core tests are AUTHORED, NOT RUN; no headed browser or PC runtime has been verified. This is not a secure network sandbox or web agent with action permissions.

## Current autonomous-core milestone

The current implementation sequence and release gates are tracked in [JARVIS Autonomous Core Milestone V1](docs/JARVIS_AUTONOMOUS_CORE_MILESTONE_V1.md). This is a plan/status record, not a claim that the runtime work or verification gates are complete.
