# JARVIS Android App V0.5 — authenticated JPVR bridge and symbolic preview

**Статус:** Android Compose V0.5 adds local bearer-token pairing to the bounded JPVR task/session bridge, symbolic Reasoning Engine preview, shared-workspace memory UI, encrypted JWS merge transfer and encrypted JWB backup/restore. It still has no task executor or trusted approval flow. Nine Android JVM local-repository tests, seven Android Keystore instrumentation tests, nine Android transfer/backup crypto instrumentation tests, eighteen Python console/API tests and eleven Python auth tests are AUTHORED, NOT RUN. Gradle sync/build, APK/AAB, installation, device checks and UI instrumentation are NOT RUN.

## Project and compatibility

- Application ID / namespace: org.jarvis.ans100.alpha.
- Kotlin + Jetpack Compose + Material 3.
- Android Gradle Plugin 8.7.3; Kotlin 2.0.21.
- compileSdk / targetSdk 35; minSdk 29; JVM target 17.
- Android entry point: app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt.
- Root Gradle Wrapper files are absent; Gradle/Android Studio compatibility has not been verified.

## Two separate kinds of data

**Android-local drafts** remain in LocalDraftRepository and app-private SharedPreferences. Limits: 32 sessions, 500 drafts, 240 characters/title, 512 KiB snapshot. This store is not JPVR and is not synchronized with the Python manager.

**JARVIS runtime tasks/sessions** are loaded from the Python TaskSessionManager through the loopback bridge. These records are authoritative in the Python process and are saved through JPVRTaskSessionCheckpointStore. The native panel can:
- connect and refresh the manager snapshot and JPVR checkpoint version;
- create a Python session;
- create a QUEUED Python task in an OPEN session;
- pause, resume or close a session using its exact expected revision;
- cancel a task only while it remains QUEUED, using its exact expected revision;
- show backend session/task statuses, revisions and failure codes;
- submit a bounded caller-authored symbolic problem to `POST /api/reasoning/preview` and display its returned plan.

There is no automatic copying or migration between Android drafts and Python tasks. A successful Android-local draft write does not create a backend task.

## Symbolic Reasoning Engine preview

The native panel provides editable task ID, goal description, initial facts, goal facts and a line-based action editor. Each non-empty action line uses this format:

    action_id | summary | preconditions | add_facts | delete_facts

The final `delete_facts` field is optional. Fact lists are comma-separated identifiers. The Android client caps a request at 16 actions and 32 facts per input list; the resulting fact universe is bounded to 64 identifiers. Each action must add or delete at least one fact and cannot both add and delete the same fact. The local Python engine independently enforces its own state, depth and search bounds.

The response displays SOLVED, NO_PLAN, DEPTH_LIMIT or SEARCH_LIMIT, the expanded-state count, predicted final facts and every planned step. This is deterministic symbolic planning over facts and caller-provided transitions. It is not natural-language understanding, evidence authentication, safety authorization, task queue execution or neural-runtime inference. The preview route does not mutate TaskSessionManager or JPVR checkpoint state and does not call an executor. Preview input is independent of any queued task record.

## Loopback bridge

Implementation:
- data/LocalRuntimeBridgeClient.kt — bounded JSON/HTTP client, strict response parsing and API-version check.
- JarvisViewModel.kt — single-threaded background request executor and StateFlow connection state.
- MainActivity.kt — native runtime panel and lifecycle/task controls.
- AndroidManifest.xml and res/xml/network_security_config.xml — INTERNET permission and cleartext allowance scoped to 127.0.0.1 / localhost; other cleartext destinations remain disallowed.

The fixed endpoint is http://127.0.0.1:8765. It connects only to a Python console running on the same Android device. The health check requires schema `jarvis.local-console`, API version 2 and `auth_scheme=Bearer`. State reads and all POSTs use the access token. On first start, Termux prints a random 43-character token once; paste it into the app. The Android app stores it encrypted using an AES-GCM key held by Android Keystore. The client rejects an unexpected API schema/version/auth mode, bounds request/response bodies, and sets connect/read timeouts. Redirects are disabled. If a token is lost, stop Termux and restart with `--rotate-token` to revoke the old token and issue a replacement.

### Start the backend on the phone

From the repository root in Termux:

    python3 -m tools.jarvis_app --port 8765

On first run, copy the one-time access token printed in Termux and paste it into the Android token field. Save it before tapping **Подключить JARVIS**. Keep the process running; the app does not launch Termux or start the backend automatically. Stop the backend with Ctrl+C in Termux.

## API contract

The client requires GET /api/health to return:
- status: "ok";
- storage: "jpvr";
- api_schema: "jarvis.local-console";
- api_version: 2;
- auth_required: true;
- auth_scheme: "Bearer".

It then uses:
- GET /api/state;
- POST /api/sessions;
- POST /api/tasks;
- POST /api/sessions/{session_id}/status;
- POST /api/tasks/{task_id}/cancel;
- POST /api/reasoning/preview (bounded, non-mutating symbolic planning request);
- GET /api/workspace and POST /api/workspace (authenticated shared-workspace read/save with CAS);
- POST /api/workspace/restore (authenticated full-workspace replacement with CAS, used only after local JWB decryption, validation, preview and explicit confirmation).

Every state read and POST route except the public health check requires `Authorization: Bearer <token>`. Every successful mutation returns the committed backend state snapshot. Session status and task cancellation requests include expected revision. The server serializes state mutations and publishes them through JPVR compare-and-swap before returning the updated snapshot.

## Shared workspace, encrypted transfer and backup

The shared workspace is a separate JPVR file owned by the local Python console. Android and the desktop PWA use the same workspace V1 JSON/schema and revision contract. This data store is distinct from Android-local SharedPreferences drafts and from the Python task/session JPVR registry.

- JWS is a merge-oriented manual transfer: records merge by stable ID and settings by key; conflicts keep destination values.
- JWB is a password-encrypted point-in-time backup of the entire portable workspace. It uses a distinct envelope/AAD from JWS, PBKDF2-HMAC-SHA256 (310,000 rounds) and AES-256-GCM. Android uses the Storage Access Framework; backup/restore crypto logic is in app/src/main/java/org/jarvis/ans100/alpha/data/WorkspaceTransferCrypto.kt.
- Before restore, Android decrypts and validates the backup and both integrity digests locally, checks that the workspace ID matches, and shows counts for conversations, memories, task summaries and settings. The user must first save a separate backup of the current revision and acknowledge it, then explicitly confirm replacement.
- The restore call uses POST /api/workspace/restore with the exact expected revision. The server independently validates the complete workspace and publishes by JPVR compare-and-swap; a stale revision fails instead of overwriting newer data. A failed/ambiguous operation must be refreshed and inspected, not blindly retried.
- JWB does not include task/session JPVR records, Android-only drafts, authentication tokens, Android Keystore keys, app binaries or neuron/network checkpoints. It is not a raw JPVR backup and cannot repair a damaged workspace.jpvr that the local service cannot open. Preserve the original JPVR file and use the separate non-destructive JPVR recovery-copy reference workflow.

Manual cross-device file movement is required. There is no pairing, LAN access or automatic synchronization. The loopback service remains bound to 127.0.0.1.
## Trust and capability boundary

The native bridge has no endpoint or code path for shell, filesystem browsing, remote network, tool execution, approval grants or ActionExecutor. The backend capability flags remain false for external_actions, tool_execution and remote_access. Creating a task only creates a QUEUED lifecycle record; it does not execute it.

The bearer token protects state and mutations, but token possession is not a trusted human identity or an exact-action approval. Host/Origin checks alone are not authentication. Other software on the same device may reach the port, so keep the token secret. The console must remain bound to 127.0.0.1 and must not be exposed on LAN/Internet. No external action is authorized by this bridge.

The native UI calls the structured `/api/reasoning/preview` endpoint, but does not invoke PermissionSafetyGate, Task Loop or ActionExecutor. Preview results are informational only and are not approvals. Trusted identity, authenticated approval, durable approval/revocation storage, executor-owned idempotency and recovery remain future work.

## Verification

- Android local repository tests: nine methods, AUTHORED, NOT RUN.
- Android Keystore token-store instrumentation tests: seven methods, AUTHORED, NOT RUN.
- Android transfer/backup crypto instrumentation tests: nine methods, including deterministic desktop-WebCrypto-compatible JWS and JWB fixtures, AUTHORED, NOT RUN.
- Python local-console/API tests: eighteen methods; auth-file tests: eleven methods; all AUTHORED, NOT RUN.
- GitHub readback/static source review only for this bridge batch.
- Kotlin compilation, Gradle sync/build, APK/AAB, device installation, Compose UI tests, Python tests, Android/Termux end-to-end I/O and performance measurements: NOT RUN.
