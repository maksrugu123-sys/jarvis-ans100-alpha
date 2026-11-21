# JARVIS Core Reasoning API V1

**Status:** implemented at source level on mobile-first-offline-v1. The API contract is not runtime-verified.

## Purpose and limits

The JARVIS Core adapter uses tools.reasoning_engine.engine.ReasoningEngineV01 for bounded deterministic planning over an explicit structured request. It does not derive a task graph from natural-language chat. A caller must provide the objective, subtasks, dependencies, acceptance criteria and any hypotheses/evidence records.

The engine does not execute steps, inspect local files, browse, run shell commands, authenticate evidence or grant capabilities. A plan is a proposal. External actions remain disabled.

## Authentication and transport

All routes below are POST requests to the local Termux service at http://127.0.0.1:8765. Requests must include Content-Type: application/json and Authorization: Bearer <local-console-token>.

The HTTP server is loopback-only and checks the Host and optional Origin. Existing request-size limit is 64 KiB. Do not expose this service to a LAN or the public internet.

## Structured request

The planning request object accepts only these root fields:

| Field | Required | Meaning |
|---|---|---|
| request_id | yes | Stable request identifier |
| idempotency_key | yes | Caller-controlled stable key |
| objective | yes | Overall goal |
| subtasks | no | Task descriptions with dependencies |
| hypotheses | no | Claims to assess against supplied evidence |
| evidence | no | Explicitly supplied weighted evidence records |
| constraints | no | Caller-supplied constraints |
| assumptions | no | Caller-supplied assumptions |

A subtask contains task_id and title, and may contain depends_on (task IDs) and criteria (objects with criterion_id and description). The engine rejects duplicate IDs, unknown dependencies and cyclic graphs.

Core-level limits: 8 subtasks, 16 total acceptance criteria, 8 hypotheses, 32 evidence items, dependency depth 8, 32 constraints, 32 assumptions and 6,000 total normalized text characters. The engine applies additional per-field and identifier validation.

Example request:

    {
      "request_id": "local-report-001",
      "idempotency_key": "local-report-001-v1",
      "objective": "Prepare and review a local report",
      "subtasks": [
        {
          "task_id": "collect",
          "title": "Collect the required source material",
          "criteria": [{
            "criterion_id": "sources-ready",
            "description": "All required source material has been collected"
          }]
        },
        {
          "task_id": "review",
          "title": "Review the collected source material",
          "depends_on": ["collect"],
          "criteria": [{
            "criterion_id": "review-ready",
            "description": "The collected material has been reviewed"
          }]
        }
      ],
      "constraints": ["Do not execute external actions"],
      "assumptions": ["The caller supplies the source material"]
    }

## Build a plan

POST /api/reasoning/plan

Body: an object with one property, reasoning_request, containing the structured request above.

The response includes plan_id, deterministic topological steps, criteria, hypothesis assessments and execution_authorized: false. The plan digest is tied to the normalized request. Submit the same request again to reproduce the same plan.

## Use the plan in a chat turn

POST /api/conversation

Send the normal text and expected_revision fields plus an optional reasoning_request. Core creates the plan before language generation and forwards a compact summary to the language model. The model only explains the plan; it is not allowed to claim that the steps have run or to execute them.

Omitting reasoning_request preserves the ordinary chat path. Core does not automatically convert free text into tasks.

## Review a reported outcome

POST /api/reasoning/verify

Body fields:
- reasoning_request: the exact original request;
- plan_id: digest returned by /api/reasoning/plan;
- completed_step_ids: caller-reported completed task IDs;
- observations: caller-reported criterion observations. Each object has criterion_id, passed (true, false or null), optional evidence_refs and optional note.

Results:
- verified: every plan step was reported complete, every criterion was marked passed and each passed criterion had at least one evidence reference;
- not_verified: at least one criterion was explicitly marked failed;
- needs_evidence: one or more steps, observations or evidence references are missing, or the plan has no acceptance criteria.

Important: verified is not independent proof. The current adapter trusts the caller-supplied completion flags and evidence references; it does not fetch or authenticate the referenced evidence. Do not present this status as a certified or cryptographic verification.

## Capability boundary

- The legacy /api/reasoning/preview fact/action planner remains available for compatibility.
- Plans are not persisted in JPVR yet. Verification rebuilds the deterministic plan from the original request and checks the digest.
- No Task Loop dispatch, shell/tool execution, external actions, web retrieval, automatic evidence collection or independent source verification is connected.
- The Android Kotlin bridge has not yet been upgraded to these V1 endpoints. The current endpoints are available to authenticated clients of the Termux loopback server; a later Android adapter/UI stage is still needed.
- This component is symbolic planning, not ANS-100 neural cognition or natural-language understanding.