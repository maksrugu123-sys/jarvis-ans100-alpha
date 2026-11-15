# ANS-100 Hybrid Cognitive Architecture V1

**Status:** DECIDED / SPECIFIED. The user selected the hybrid architecture (one 10,000-neuron sparse network with specialized interacting populations and bounded software agents). This document is a design contract, not an implementation or a claim of general intelligence.

**Target branch:** `experiment/jarvis-integrated-prototype-v1`

## 1. Decision

JARVIS will use one logical ANS-100 network with a fixed ceiling of 10,000 logical neurons. The network is divided into typed, interacting populations. A bounded software orchestration layer (the Agent Manager) assigns work to those populations and manages a small number of analysis passes. The agents are controllers over shared evidence and network state; they are not separate language models and are not one-agent-per-neuron.

Qwen3-0.6B INT4 remains the natural-language realization component. It receives the original user message plus a bounded, provenance-preserving ANS-100 decision object. It does not replace ANS-100 analysis, and ANS-100 does not update Qwen weights.

## 2. Existing implementation baseline (source audit)

The current Android implementation already has:
- a SQLite-backed sparse event-driven LIF-DT runtime with a source-level 10,000-neuron ceiling;
- a bounded resident LRU and atomic same-timestamp SQLite publication;
- a separate app-local Core understanding/planning pipeline (currently heuristic and explicitly described as such);
- a separate ten-neuron request-shape signal pass;
- a separate 10,000-slot hashed dialogue feature classifier;
- an Android sparse dialogue graph with an input/hidden/readout structure and explicit-feedback R-STDP;
- a LiteRT-LM response engine for Qwen.

The current Android sparse dialogue topology is not yet the selected hybrid architecture. Its source initializer documents 9,000 hashed text inputs, 996 LIF hidden neurons, and four readout neurons. Inputs fan out to a bounded set of hidden targets; hidden units connect to the four readouts. The four readouts are not a general reasoning system. Existing R-STDP is limited to explicit feedback and does not establish semantic understanding or useful generalization.

The existing heuristic Core output is useful as upstream evidence but must not be relabelled as a learned semantic representation. The ten-neuron signal pass, 10,000-slot hashed classifier, and 10,000-node sparse spiking graph are distinct components and must remain separately identified until a deliberate integration replaces their roles.

## 3. Logical populations within the 10,000-neuron budget

The following is the initial *design allocation*, not a measured optimum. It must be versioned and can be revised only through documented evaluation. IDs are stable within a topology version.

| Population | Initial capacity | Purpose |
|---|---:|---|
| Evidence/input | 1,200 | Typed upstream evidence and feature activation |
| Association | 2,800 | Sparse co-activation and learned relationships between evidence features |
| Working state | 1,800 | Bounded active task/context state across analysis rounds |
| Candidate hypotheses | 1,500 | Competing interpretations or solution candidates |
| Critic/uncertainty | 1,200 | Conflict, missing-evidence and uncertainty signals |
| Goal/planning | 900 | Goal progress, dependency and next-step signals |
| Decision/readout | 600 | Candidate ranking signals and structured decision fields |
| **Total** | **10,000** | |

Population names describe engineering roles, not biological cell types or independent intelligence. Not every neuron is active on every turn. The runtime must activate only a bounded subset based on evidence and the selected compute profile.

The Agent Manager is software orchestration and does not consume additional logical neuron IDs. It may not bypass network budgets or safety gates.

## 4. Bounded software agents

The first orchestration version has six deterministic controller roles:

1. **Router** — classifies task complexity from available Core evidence and chooses a bounded compute profile.
2. **Evidence Analyst** — checks evidence types, provenance, source availability and missing fields before scheduling input activation.
3. **Hypothesis Manager** — creates and tracks a bounded set of candidate interpretations/solution paths; it must not invent evidence.
4. **Critic** — checks candidates against supporting/conflicting evidence and uncertainty signals; it can request one additional bounded analysis pass.
5. **Decision Arbiter** — selects a supported candidate or abstains; raw membrane values or synaptic means are not calibrated probabilities.
6. **Memory/Tool Planner** — selects approved memory references or proposes read-only tool steps. It cannot perform side effects or grant itself permissions.

These are bounded state machines operating over typed records. They do not call one another recursively. A single orchestrator controls ordering, budgets and cancellation.

## 5. Request pipeline

1. Preserve the exact original user message.
2. Run JARVIS Core understanding and reasoning-plan extraction. Mark heuristic fields as heuristic and retain their source.
3. Collect only available typed evidence: user message structure, Core intent/entities/ambiguity, plan metadata, relevant conversation references, user-approved memory references, and verified tool results.
4. The Evidence Analyst rejects malformed or unsupported fields. Missing evidence remains missing.
5. The Router selects a profile: FAST, STANDARD, DEEP or RESEARCH. Profiles differ by active population limits, maximum event count, analysis rounds, candidate count and deadline. The initial profile values are configuration proposals, not performance claims.
6. The network processes sparse, deterministic, versioned events. Typed population paths allow evidence to activate associations, working state and candidate groups; critic signals can feed back into candidate/goal state within a fixed round budget.
7. The Critic evaluates the current candidate set. At most one additional pass is permitted in V1; later increases require measured latency and resource evidence.
8. The Decision Arbiter emits a structured decision or an explicit abstention.
9. Qwen receives the original request, Core context, structured decision, approved memory excerpts/references and applicable response constraints. Neural outputs remain advisory and are never treated as verified facts or action authorization.
10. The response is returned. Any tool operation remains a separate permission-gated path; a proposed plan is not an executed action.

## 6. Evidence and decision contracts

Every encoded feature must include:
- schema/version;
- stable feature-family and feature identifier;
- source kind (raw-message structural feature, Core field, plan field, approved memory reference, verified tool result);
- source reference or bounded provenance token;
- evidence status (observed, heuristic, user-provided, verified-tool-result);
- bounded numeric activation and timestamp/turn identity.

Do not use a hash of arbitrary text as proof of semantic meaning. Hashing may provide stable indexing for a declared feature family, but its provenance and interpretation must remain explicit. Character counts, punctuation and lexical-shape signals are structural signals only.

The V1 decision object must include:
- schema and topology versions;
- request/turn identifier;
- inferred task goal and its evidence references;
- bounded candidate list with support/conflict references;
- uncertainty and missing-information fields;
- selected candidate or abstention reason;
- suggested response structure and constraints;
- active population IDs/counts, processed event count and budget-exhaustion status;
- advisory confidence metadata with calibration status explicitly marked uncalibrated until measured;
- approved memory references only.

The decision object must be size-limited before prompt construction. It must not contain unapproved private memory or raw hidden state dumps.

## 7. Network and storage invariants

- Preserve a hard ceiling of 10,000 logical neurons and sparse directed connectivity; no dense N×N matrix.
- Preserve bounded connection, synapse, queue, timestamp-batch and per-run event limits.
- No population may directly inject a final answer or bypass documented propagation/readout paths.
- Use deterministic topology generation with a versioned seed and stable IDs.
- Keep ALIF-DT, delay, eligibility and reward equations versioned; topology changes must not silently reinterpret persisted state.
- V2 uses a new topology/database namespace. Do not destructively rewrite or reinterpret the existing V1 SQLite database.
- Provide explicit initialization, integrity validation, rollback/abstention and migration/reset behavior before routing ordinary chat through V2.
- Preserve atomic timestamp publication: changed neuron state, consumed/generated events and model clock commit together before in-memory cache publication.
- Keep the active resident set bounded. SQLite/flash is persistence, not active RAM.
- Do not enable the new graph by default until correctness tests and named-device profiling pass.

## 8. Learning policy

- Ordinary conversation does not automatically reward or punish synapses.
- Only explicit user feedback, explicit expected labels, or independently verified outcomes may produce reward.
- Every reward must bind to the exact turn, topology version, decision version and eligibility trace IDs.
- Duplicate, stale, cross-turn or already-consumed feedback must be rejected idempotently.
- Keep supervised labels, explicit reward and inferred outcomes separate.
- No automatic modification of Qwen weights is implied.

## 9. Evaluation gates

The hybrid network is not accepted merely because all 10,000 IDs are allocated. Each population must demonstrate meaningful participation: it receives correctly typed evidence, propagates through documented edges and changes a measurable readout under controlled perturbation.

Required offline fixtures:
1. Deterministic topology/readout for identical input, seed and version.
2. Missing or malformed evidence leads to abstention or safe fallback, never fabricated fields.
3. Each population has at least one positive participation fixture and one ablation fixture.
4. Removing a relevant population or connection measurably changes the expected decision; irrelevant perturbations do not.
5. Candidate/critic feedback stays within a fixed round/event/time budget.
6. No input can directly stimulate final readout without the declared paths.
7. Reward attribution is limited to the originating turn and trace.
8. Persistence/reopen preserves topology, clock, state, weights and pending events.
9. Decision serialization respects prompt and memory-privacy limits.
10. Held-out comparison: Core+Qwen baseline vs Core+legacy-ANS+Qwen vs Core+hybrid-ANS+Qwen. Report task accuracy, abstention quality, calibration, latency, peak RAM, CPU, battery/thermal observations and model-token overhead.

No claim of better reasoning or generalization is allowed without held-out results. No Android performance claim is allowed without measurement on a named target device.

## 10. Rollout sequence

**Phase 0 — contract and inventory (current):** record the hybrid decision, reconcile current topology documentation with source, map existing Core/ANS/Qwen boundaries.

**Phase 1 — typed evidence contract:** implement Kotlin data classes, deterministic bounded encoder and fixtures. Keep V1 graph and current reply path unchanged.

**Phase 2 — decision object and Qwen handoff:** add bounded serialization and prompt integration behind a disabled-by-default feature flag. Preserve the original request and fallback path.

**Phase 3 — V2 topology builder:** create deterministic population ranges and sparse typed connectivity in a separate database namespace. Add topology inspection and migration/reset handling.

**Phase 4 — bounded agent orchestration:** implement Router, Evidence Analyst, Hypothesis Manager, Critic, Decision Arbiter and Memory/Tool Planner as deterministic bounded controllers. Integrate one population at a time.

**Phase 5 — explicit learning and persistence audit:** bind feedback to exact traces/turns and validate durable atomicity.

**Phase 6 — offline evaluation and device profiling:** compare ablations and measure resource use. Enable the hybrid path only if it improves held-out outcomes within the Android budget.

## 11. Non-goals and safety

- No claim of consciousness, human-like thought, emotions or GPT-6-equivalent ability.
- No claim that 10,000 logical neurons are 10,000 simultaneously active or independently intelligent units.
- No unrestricted autonomous actions, arbitrary shell, external side effects or self-granted permissions.
- No replacement of Qwen as the natural-language generator in this architecture.
- No builds, tests, CI, GitHub Actions, APK/AAB generation or device workflows without separate explicit user authorization.
- All implementation remains on `experiment/jarvis-integrated-prototype-v1`; `main` is untouched.
