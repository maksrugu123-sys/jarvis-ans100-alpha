# JARVIS Shared Workspace & Memory V1

**Status:** Python JPVR store and authenticated local API implemented; Android and desktop browser memory-management UI wired to the API. Runtime/device verification is pending.  
**Date:** 2026-11-13

## Goal

Desktop and Android use one platform-neutral workspace document for conversations, explicit memories, task summaries and non-secret settings. Platform code is an adapter; it must not invent a second incompatible memory format.

This workspace is deliberately separate from the existing TaskSessionManager checkpoint. The latter continues to own the lifecycle of executable task/session records. A later integration can project completed tasks into workspace summaries without making the two stores share one JPVR checkpoint pointer.

## Durable representation

- Contract: `schemas/workspace/v1/workspace.schema.json`.
- Python reference codec/store: `tools/neuron_runtime/workspace_store.py`.
- Durable container: `workspace.jpvr`, separate from `local-console.jpvr`.
- Small snapshots are stored as one JPVR DATA record; larger snapshots use contiguous BLOB pages.
- Each published snapshot has a CHECKPOINT descriptor and one JPVR COMMIT. The active MANIFEST points at the published checkpoint.
- A monotonically increasing workspace revision provides compare-and-swap. Stale writes fail rather than silently overwriting a newer snapshot.
- Load validates canonical UTF-8 JSON, SHA-256 integrity, record identity, DATA/BLOB metadata, CRC and source COMMIT ancestry.
- Initial INDEX roots are created before the first workspace checkpoint. Keep this file dedicated to the workspace store because JPVR V1 has one active CHECKPOINT pointer.

## Canonical document rules

- UTF-8 JSON, object keys sorted lexicographically, no insignificant whitespace, no duplicate keys, no NaN/Infinity.
- Arrays retain their defined order.
- `integrity.digest` is lowercase SHA-256 over the canonical document with the entire `integrity` property omitted.
- The portable setting value types are string, integer, boolean or null. Floating-point settings are excluded to avoid platform-specific number serialization differences.
- Memory records require `user_approved: true`; the store does not infer or silently save personal facts.
- Credentials, passwords, tokens, private keys and auth-like settings are rejected. Do not put secrets in conversations or memories either.
- Unknown fields and unsupported schema versions fail closed.

## Local API V1

The loopback-only Python service exposes these routes under the existing Bearer-token authentication:

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/workspace` | Read the canonical workspace document and current revision |
| POST | `/api/workspace` | Save a validated document using `expected_revision` compare-and-swap |

POST body:

```json
{
  "expected_revision": 3,
  "workspace": {
    "...": "complete workspace V1 document including integrity"
  }
}
```

A stale revision returns HTTP 409. Invalid documents return HTTP 400. Workspace requests are bounded to 16 MiB. Health reports `workspace_api_version: 1`; the existing local-console API remains version 2 for backwards compatibility.

The service binds only to `127.0.0.1`. Do not expose it to a LAN, tunnel or public network. The Bearer token is local transport access control, not a human identity or permission to execute actions.

## Platform adapters

- Desktop Python uses the same JPVR workspace store and JSON schema.
- Android's `LocalRuntimeBridgeClient` exposes `getWorkspace()`, `saveWorkspace(document, expectedRevision)`, `createMemory(...)` and `deleteMemory(...)`. The Compose screen provides consent-gated create, search, list and confirmed delete operations.
- The desktop PWA provides the same consent-gated create, search, list and confirmed delete operations. It calculates the canonical SHA-256 envelope in the browser and uses the same compare-and-swap API.
- On Termux, launch with `--platform android`; on a PC use `--platform desktop`. This labels the platform that last wrote the snapshot.
- Both adapters use the same workspace format and CAS revision. They do not yet merge concurrent edits automatically.

## Important current boundary

The Android app currently connects to `127.0.0.1:8765` on the phone. The PC service also binds to its own loopback. Therefore the shared format and API are implemented, but a PC and phone do not automatically see each other's local file. Cross-device pairing, encrypted transfer, conflict-aware import/export and background sync are future work. Do not expose the loopback service as a workaround.

The browser and Android memory screens manage explicitly entered records only. Automatic extraction from chat, semantic retrieval, ranking and conflict merging are not implemented.

The workspace is a durable data layer, not a conversational AI model. Natural-language chat, retrieval ranking, semantic memory extraction, embeddings and offline LLM inference are not implemented by this milestone.

## Verification status

Focused tests are authored in:

- `tools/neuron_runtime/test_workspace_store.py`
- `tools/jarvis_app/test_server.py`

They cover canonical round-trip, tamper detection, explicit memory approval, secret-like setting rejection, DATA/BLOB persistence, reopen, CAS conflicts and authenticated API access. Tests and Android builds have **NOT RUN** in this change set. UI behavior is source-reviewed only and still requires browser and device verification. The Python/Android implementation is source-level until those checks run on the target environments.
