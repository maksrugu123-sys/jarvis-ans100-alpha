# JARVIS Local Console V1

**Статус:** loopback-only Python console on HTTP + PWA web UI. The same local API is now also consumed by the native Android Compose client; both interfaces operate on the same Python TaskSessionManager and JPVR checkpoint while talking to the same running console process. Nine console test methods are AUTHORED, NOT RUN. The console remains a reference task/session manager with a non-mutating symbolic planner preview, not a production executor.

## Start on Android / Termux

From the repository root:

    python3 -m tools.jarvis_app --port 8765

Open the web UI on the same device at:

    http://127.0.0.1:8765/

The native Compose application also connects to http://127.0.0.1:8765; launch one console process and keep it running while using either or both local interfaces. JPVR file default: ~/.jarvis-ans100-alpha/local-console.jpvr; override using --data. Stop with Ctrl+C in Termux.

## Capabilities

- Create sessions and tasks; each mutation is persisted through a dedicated JPVRTaskSessionCheckpointStore.
- Pause/resume/close sessions with expected-revision checks; a session cannot close while any task is non-terminal.
- Cancel QUEUED tasks with expected-revision checks.
- Read sessions, tasks, recent lifecycle events and checkpoint version.
- Structured Reasoning Engine preview from caller-supplied facts/actions. The preview is non-mutating and does not alter the task/session checkpoint or execute actions.
- PWA static assets are cached; API responses are not cached.

## Versioned API contract

GET /api/health returns status, storage, api_schema = "jarvis.local-console" and api_version = 1. The Android client fails closed for an unsupported schema/version.

- GET /api/health — service and API contract version.
- GET /api/state — manager identity, sessions, tasks, recent lifecycle events, JPVR checkpoint version and capability flags.
- POST /api/sessions — JSON object with title.
- POST /api/tasks — JSON object with session_id and title.
- POST /api/sessions/{session_id}/status — JSON object with status and expected_revision.
- POST /api/tasks/{task_id}/cancel — JSON object with expected_revision.
- POST /api/reasoning/preview — bounded structured ReasoningProblem payload.

Mutating POST routes require exact JSON fields. Mutations are serialized under the service lock, applied to a detached candidate manager and published through JPVR compare-and-swap before the in-memory manager reference is replaced. Failed commits do not publish the candidate state.

## Safety and trust boundary

The console reports external_actions=false, tool_execution=false and remote_access=false. It has no shell, filesystem browsing, browser automation, credentials, network connector, approval provider or ActionExecutor. The console does not run JARVIS Task Loop; it manages task/session lifecycle and supplies a caller-driven structured reasoning preview. The Android V0.4 client also calls this preview route and strictly decodes the bounded plan response.

The server validates the exact loopback Host and rejects a foreign Origin. It must bind only to 127.0.0.1; do not change to 0.0.0.0. Loopback/Host/Origin checks are not identity authentication: other local software may still reach the service. Never use this unauthenticated HTTP interface for secrets or side-effecting tool actions.

The PWA is one local frontend. The Compose application is a separate native frontend that uses the same loopback API; Android SharedPreferences drafts remain a separate, non-interoperable store and are not copied into JPVR tasks.

## Verification

Run: python3 -m unittest tools.jarvis_app.test_server -v

Nine test methods cover session pause/resume/close and revision flow, close blocked by a non-terminal task, no-op status update, HTTP session status, versioned health contract, service-level reasoning preview and checkpoint non-mutation, duplicate-fact rejection, HTTP reasoning-preview response and foreign-Origin rejection. Tests are AUTHORED, NOT RUN. No Python tests, Android build/APK, browser/device check, Termux end-to-end test, CI or GitHub Actions run was performed for the current Android bridge batch.
