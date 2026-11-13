# JARVIS Android Runtime Bridge V1

**Status:** Android V0.5 includes JPVR task/session administration, a bounded symbolic Reasoning Engine preview and bearer-token authentication over the loopback Python API v2. End-to-end tests, Android build and device verification NOT RUN. This remains a registry/preview bridge, not tool execution.

## Ownership

The bridge exposes the existing Python TaskSessionManager backed by a dedicated JPVRTaskSessionCheckpointStore. That Python manager and its JPVR checkpoint are authoritative for remote task/session state.

The app's LocalDraftRepository remains an independent Android-only SharedPreferences store. No IDs, task records or display status strings are copied between these stores. An Android local draft is not a Python QUEUED task.

## Transport and API version

- Fixed endpoint: http://127.0.0.1:8765.
- Server binding: 127.0.0.1 only.
- Health schema: api_schema = "jarvis.local-console", api_version = 2, auth_required = true, auth_scheme = "Bearer".
- The public health check reveals no task/session state. Every state read and POST route requires one valid `Authorization: Bearer <token>` header.
- The client validates status, storage kind, schema, version and authentication mode before accepting the initial connection.
- Android INTERNET permission is declared. Network Security Config permits cleartext only to 127.0.0.1 and localhost; the base configuration denies cleartext elsewhere.
- Connect timeout: 1.8 seconds; read timeout: 2.5 seconds.
- Request body maximum: 64 KiB; response body maximum: 256 KiB.
- Redirects are disabled. IDs are validated before being inserted into URL paths. JSON records are decoded with required-field and collection bounds.

Start one backend process in Termux:

    python3 -m tools.jarvis_app --port 8765

On first start, Termux prints a random 43-character Base64URL access token once. Copy it into the Android app token field and tap **Сохранить токен**. The app encrypts it with an AES-GCM key held by Android Keystore; the plaintext token is not stored in SharedPreferences. The server stores only the SHA-256 digest in `~/.jarvis-ans100-alpha/local-console.jpvr.access-token` with owner-only file permissions. If the token is lost, stop the console and restart with:

    python3 -m tools.jarvis_app --port 8765 --rotate-token

This revokes the previous token and prints a replacement once. The PWA login keeps the token only in the current tab's `sessionStorage`. The Android app does not launch Termux or manage this process lifecycle.

## Supported operations

| Android operation | HTTP route | Backend effect |
|---|---|---|
| Connect | GET /api/health, then GET /api/state | Validate API v2 and read the authenticated JPVR-backed snapshot |
| Refresh | GET /api/state | Read current manager state and checkpoint version |
| Create session | POST /api/sessions | Create OPEN session and publish JPVR checkpoint |
| Create task | POST /api/tasks | Create QUEUED task in an OPEN session and publish checkpoint |
| Pause/resume/close | POST /api/sessions/{id}/status | Transition session with expected revision and JPVR publication |
| Cancel queued task | POST /api/tasks/{id}/cancel | Cancel only a QUEUED task with expected revision and JPVR publication |
| Reasoning preview | POST /api/reasoning/preview | Compute a caller-supplied bounded symbolic plan; no queue/JPVR mutation and no action execution |

Successful mutation responses include the updated state snapshot. The ViewModel makes requests on a dedicated single-thread executor and publishes results to UI state on the main thread.

## Symbolic reasoning preview

Android V0.5 exposes the existing Python `ReasoningEngine` preview route after token authentication. The request contains a task label, goal description, initial/goal fact identifiers and caller-authored pure state-transition actions. The client bounds requests to 16 actions, 32 input facts per list and a 64-fact resulting universe. The response is strictly decoded and displayed as a sequence of proposed symbolic steps. A preview identifier is not a TaskSessionManager task ID, and a preview does not change the durable task/session registry.

The planner searches a bounded symbolic state space. Its output is not natural-language interpretation, authenticated evidence, a permission grant, an executable command or proof that an external world state changed. No preview result is forwarded to TaskLoop or ActionExecutor.

## Failure and reconciliation

- A failed HTTP request never causes the Android app to invent an updated status.
- The bridge presents backend state only after it has parsed a bounded response.
- Session lifecycle and queued-task cancellation use exact revision checks. Stale revisions are rejected by the Python service.
- The service owns JPVR persistence and its compare-and-swap token; the Android client does not write JPVR pages or attempt to merge checkpoint versions.
- On reconnect, the client fetches a fresh /api/state snapshot rather than replaying unsent mutations.
- Android-local drafts are never automatically replayed to the backend.

## Security boundary and non-goals

API V2 requires possession of a random local bearer token for task/session state and every POST operation. The Termux process stores only the token's SHA-256 digest; the Android app encrypts the token with Android Keystore, while the PWA keeps it only for the current tab. The auth directory/file must be owner-controlled; symlinked parent/file paths are rejected, and the auth path must not alias the JPVR data file. A bearer token is still not a complete user identity system or a trusted approval: it does not prove that a human reviewed a particular plan, authorize a capability, or make an action safe.

The backend capability flags remain false for external actions, tool execution and remote access. The server is still bound only to 127.0.0.1; do not expose it to LAN/Internet. Host/Origin checks alone are not authentication, and other local apps may still reach the port, but state/mutations are rejected without the token. Do not use the API for external side effects or production trust decisions.

The native Android screen can request a non-mutating Reasoning Engine preview, but it does not invoke PermissionSafetyGate, Task Loop, approval workflows or neural NetworkRuntime. It has no ActionExecutor. Creating a QUEUED task does not mean that the task is running or completed.

## Verification status

- Android local repository tests: 9 methods, AUTHORED, NOT RUN.
- Android Keystore token-store instrumentation tests: 7 methods, AUTHORED, NOT RUN.
- Python local-console/API tests: 13 methods in `test_server.py`; auth-file tests: 9 methods in `test_auth.py`; all AUTHORED, NOT RUN.
- GitHub source readback/static inspection only.
- Kotlin compilation, Gradle sync/build, APK/AAB, Android install/UI tests, Android-to-Termux end-to-end I/O, Python tests, performance and security testing: NOT RUN.
