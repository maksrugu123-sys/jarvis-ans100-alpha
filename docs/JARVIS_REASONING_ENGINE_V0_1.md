# JARVIS Reasoning Engine V0.1

**Status:** implemented as a bounded deterministic Python reference planner and evidence assessor. Focused tests are authored, not run. This is not a natural-language model, autonomous agent, tool executor, or production NCC component.

## Purpose

Reasoning Engine V0.1 turns a structured planning problem into a deterministic action sequence. It accepts:
- an explicit task identity and goal description;
- an initial set of symbolic facts;
- required goal facts;
- caller-supplied pure planning actions with preconditions and add/delete effects;
- explicit hypotheses and evidence records.

The engine uses breadth-first search over immutable fact sets. With unit-cost actions, the first solution is a shortest action-count plan. Actions are visited in stable action-ID order, so equal-length solutions are deterministic. A plan step records required facts, declared effects, rationale, and the resulting predicted fact set.

## Hypothesis and evidence review

Evidence records identify a hypothesis, source reference, polarity (supports/refutes), quality label, and summary. The engine reports one of four evidence-coverage states:
- `insufficient` — no linked evidence;
- `support_only` — supporting entries and no refuting entries;
- `refute_only` — refuting entries and no supporting entries;
- `mixed` — both directions are present.

These labels describe the supplied record set only. They are not probabilities, source authentication, independent corroboration, or proof that a hypothesis is true or false. Quality labels are carried through for review and do not silently change the verdict.

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

The test module covers deterministic shortest-plan search, already-satisfied and unreachable goals, depth/state limits, action preconditions and effects, input bounds/identity validation, evidence grouping, and observed-result verification. Tests are AUTHORED, NOT RUN. No Python execution/import/syntax check, Android/Termux measurement, benchmark, build, CI or GitHub Actions run was performed for this implementation.
