# JARVIS Safe Local Actions V1

**Status:** explicit Task Loop execution source implemented for a small read-only allow-list. Python tests and PC browser runtime remain unverified.

## Starting a run

Create a task through the authenticated local console. Then call:

```http
POST /api/tasks/<task_id>/run
Authorization: Bearer <local-token>
Content-Type: application/json
```

Example body:

```json
{
  "expected_revision": 1,
  "steps": [
    {"capability": "memory.search", "input": "JARVIS architecture"}
  ]
}
```

One to four steps are accepted. Each step is compiled into a dependency-linked planning action and an exact read-only capability binding. The Task Loop updates the task/session JPVR checkpoint and returns action outputs for the current response.

## V1 allow-list

| Capability | Effect | Boundary |
|---|---|---|
| `memory.search` | READ_ONLY | Searches only explicitly approved shared-workspace memories; returns up to five excerpts |
| `browser.search` | READ_ONLY | Desktop only; visible Chromium; bounded search query |
| `browser.open` | READ_ONLY | Desktop only; visible Chromium; URL/DNS and GET/HEAD guards |
| `browser.read` | READ_ONLY | Desktop only; reads the current visible page |

No arbitrary shell, Python execution, file mutation, downloads, forms, account login, messages, purchases, uploads, or external write tools are available. A task runs only after an explicit authenticated POST request; creating a queued task alone does not start it.

## Trust and future expansion

The loop uses capability allow-lists, exact scopes, fixed resource budgets, task/session revision checks, durable JPVR lifecycle checkpoints and the existing idempotency/recovery contracts. Read-only actions do not require a side-effect approval grant. The local Bearer token is local-console access control, not proof of a trusted human identity.

Reversible writes and external/irreversible actions remain disabled. Before adding them, require a trusted signed-approval provider bound to the exact action payload and expiry, persistent revocation, durable downstream idempotency, and trusted result reconciliation. A confirmation button by itself is not a cryptographic identity boundary.

## Verification

Four Python tests are authored for allow-listing, approved-memory filtering and Task Loop persistence; they are **AUTHORED, NOT RUN**. Visible Playwright browser launch, PWA interaction and local LLM inference have not been verified in a PC environment.

## Android Task Loop bridge

The native Android Compose panel now exposes one execution path for a queued task: a user-confirmed, single-step `memory.search` over explicitly approved workspace memories. The client sends the exact task revision, accepts only the `memory.search` capability, validates the returned task identity/status and bounded result shape, then refreshes the JPVR state. It does not expose browser, shell, file-write, message, upload or purchase capabilities.

The Android confirmation dialog displays the query before the authenticated run request. Only tasks whose owning session is open and whose revision/status still match are offered for execution. A stale revision fails closed and requires refresh. The server still owns Task Loop planning, policy checks, action budgeting and JPVR persistence.

The Android payload-builder tests are **AUTHORED, NOT RUN**. No end-to-end Termux run or Android UI/device verification has occurred.
