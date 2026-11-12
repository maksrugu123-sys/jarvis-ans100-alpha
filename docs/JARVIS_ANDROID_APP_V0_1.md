# JARVIS Android App V0.4 — JPVR bridge and symbolic reasoning preview

**Статус:** Android Compose V0.4 adds a bounded symbolic Reasoning Engine preview to the existing loopback JPVR task/session bridge. It still has no task executor or approval flow. Nine Android local-repository tests and nine Python console tests are AUTHORED, NOT RUN. Gradle sync/build, APK/AAB, installation, device checks and UI instrumentation are NOT RUN.

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

The fixed endpoint is http://127.0.0.1:8765. It connects only to a Python console running on the same Android device. The client rejects an unexpected API schema/version, bounds request/response bodies, and sets connect/read timeouts. Redirects are disabled.

### Start the backend on the phone

From the repository root in Termux:

    python3 -m tools.jarvis_app --port 8765

Keep that process running, open the Android app and tap **Подключить JARVIS**. The app does not launch Termux or start the backend automatically. Stop the backend with Ctrl+C in Termux.

## API contract

The client requires GET /api/health to return:
- status: "ok";
- storage: "jpvr";
- api_schema: "jarvis.local-console";
- api_version: 1.

It then uses:
- GET /api/state;
- POST /api/sessions;
- POST /api/tasks;
- POST /api/sessions/{session_id}/status;
- POST /api/tasks/{task_id}/cancel;
- POST /api/reasoning/preview (bounded, non-mutating symbolic planning request).

Every successful mutation returns the committed backend state snapshot. Session status and task cancellation requests include expected revision. The server serializes state mutations and publishes them through JPVR compare-and-swap before returning the updated snapshot.

## Trust and capability boundary

The native bridge has no endpoint or code path for shell, filesystem browsing, remote network, tool execution, approval grants or ActionExecutor. The backend capability flags remain false for external_actions, tool_execution and remote_access. Creating a task only creates a QUEUED lifecycle record; it does not execute it.

The loopback check and Host/Origin validation are not identity authentication. Other software on the same device may be able to reach a loopback HTTP service. Therefore the console must remain bound to 127.0.0.1, must not be exposed on LAN/Internet, and must not accept secrets or side-effecting action requests. No external action is authorized by this bridge.

The native UI calls the structured `/api/reasoning/preview` endpoint, but does not invoke PermissionSafetyGate, Task Loop or ActionExecutor. Preview results are informational only and are not approvals. Trusted identity, authenticated approval, durable approval/revocation storage, executor-owned idempotency and recovery remain future work.

## Verification

- Android local repository tests: nine methods, AUTHORED, NOT RUN.
- Python local-console API tests: nine methods, AUTHORED, NOT RUN.
- GitHub readback/static source review only for this bridge batch.
- Kotlin compilation, Gradle sync/build, APK/AAB, device installation, Compose UI tests, Python tests, Android/Termux end-to-end I/O and performance measurements: NOT RUN.
