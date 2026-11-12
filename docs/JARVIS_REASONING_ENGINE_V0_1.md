# JARVIS Reasoning Engine V0.1

**Status:** implemented as a bounded deterministic Python reference planner and evidence assessor. Thirty-one focused tests are authored, not run. This is not a natural-language model, autonomous agent, tool executor, or production NCC component.

## Purpose

Reasoning Engine V0.1 turns a structured planning problem into a deterministic action sequence. It accepts:
- an explicit task identity and goal description;
- an initial set of symbolic facts;
- required goal facts;
- caller-supplied pure planning actions with preconditions and add/delete effects;
- explicit hypotheses and evidence records.

The engine uses breadth-first search over immutable fact sets. With unit-cost actions, the first solution is a shortest action-count plan. Actions are visited in stable action-ID order, so equal-length solutions are deterministic. A plan step records required facts, declared effects, rationale, and the resulting predicted fact set.

## Hypothesis and evidence review

Evidence records identify a hypothesis, source reference, polarity (supports/refutes), source-quality label, and summary. They also carry:
- `verification_status`: `pending` by default, `verified`, or `rejected`;
- `verification_ref`: a stable reviewer/verification record reference required for a terminal review status.

Only records explicitly marked `verified` and whose quality is not `unverified` contribute to the hypothesis assessment. Pending, rejected, and unverified-quality records remain excluded. The verifier itself is outside this V0.1 engine: a caller supplies the review state and reference. The engine validates their shape but cannot authenticate the reviewer, prove that a review happened, or verify source contents. The resulting states are:
- `insufficient` — no eligible evidence;
- `support_only` — eligible supporting entries and no eligible refuting entries;
- `refute_only` — eligible refuting entries and no eligible supporting entries;
- `mixed` — eligible evidence points in both directions.

These labels describe only the caller-supplied, reviewed record set. The engine does not authenticate the reviewer, source, or verification reference; those fields are provenance metadata, not cryptographic proof. The states are not probabilities, independent corroboration, or proof that a hypothesis is true or false. A pending or unverified statement cannot silently support a hypothesis.

## Boundedness

Default limits: 2,048 visited fact states, 32 action steps, 256 actions, 2,048 facts per state/request, 128 hypotheses, and 1,024 evidence records. Constructor limits have hard maxima. Invalid identities, duplicate facts/action/evidence/hypothesis IDs, dangling evidence references, unsupported enum values, empty actions and conflicting add/delete effects are rejected before search.

Search outcomes are explicit:
- `solved` — a plan reaches every goal fact;
- `no_plan` — the finite explored state space has no solution;
- `depth_limit` — a potentially new state was blocked by the depth bound;
- `search_limit` — the visited-state or fact-count bound was reached.

The planner does not mutate the request or execute any action.

## Result verification

`verify_result()` accepts a caller-supplied set of observed facts after some external executor has acted. It reports whether all goal facts were observed, which predicted facts are missing, and which unexpected facts appeared. It cannot authenticate observations or inspect external side effects; the caller owns that trust boundary.

## Explicit non-goals

V0.1 does not parse natural language, generate actions from text, retrieve external evidence, authenticate sources, assign probabilistic confidence, call tools, request user approval, enforce capabilities, schedule concurrent work, or persist a plan. It is not connected to TaskSessionManager or CQE yet. The next integration must bind a plan to a durable task revision and pass every side effect through Permission & Safety Gate before execution.

## Verification

Focused tests:

```sh
python3 -m unittest tools.neuron_runtime.test_reasoning_engine -v
```

The test module has 31 methods covering deterministic shortest-plan search, already-satisfied and unreachable goals, depth/state/fact limits, cycle handling, action preconditions and effects, input bounds/identity validation, pending/verified/rejected evidence semantics, unverified-quality exclusion, required review references, conflicting evidence, bounded observed-fact iteration, and rejection of inconsistent caller-constructed plan results. Tests are AUTHORED, NOT RUN. No Python execution/import/syntax check, Android/Termux measurement, benchmark, build, CI or GitHub Actions run was performed for this implementation.
