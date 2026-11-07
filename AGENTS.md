# JARVIS Project Instructions for AI Assistants

These instructions apply to every AI assistant, coding agent, and automated contributor working in this repository. Read this file before making changes.

## 1. Project identity and goal

- Repository: `maksrugu123-sys/jarvis-ans100-alpha`.
- Project: **JARVIS ANS-100 Alpha**.
- This is an experimental adaptive neural-system project. It is not the separate `shlep.net` browser project.
- The near-term engineering goal is a small, measurable first prototype: one Adaptive LIF-DT neuron, a small set of synapses, controlled signal processing and learning, and persistence/recovery through the planned JNVM/JPVR storage layer.
- Scale gradually (1 → 10 → 100 → 1,000 → 10,000) only after the previous scale passes explicit correctness, stability, and resource checks.
- Do not claim that a small neuron count provides human-like intelligence, GPT-level reasoning, consciousness, emotions, or biological equivalence. Treat terms such as attention, surprise, reward, memory, and fatigue as engineering variables unless evidence establishes more.

## 2. Authority and working style

- The user has authorized the assistant to make reasonable technical and architectural decisions, explain the rationale, and record them without asking the user to choose between options at every step.
- Prefer the simplest design that satisfies the stated requirements. Do not automatically choose a hybrid or adaptive design just because it sounds more advanced.
- Ask the user only when a decision changes project goals, creates a significant irreversible consequence, requires a permission the user has not granted, or depends on an essential unknown.
- Work incrementally but deliver coherent improvements. Avoid unnecessary tiny commits that do not produce a meaningful result.
- Be transparent about assumptions, trade-offs, unresolved questions, and what has actually been implemented or verified.

## 3. Git and change permissions

- The user has authorized creating commits and pushing project changes to GitHub without asking for confirmation for each ordinary documentation or code change.
- The current working branch for this repository is `main`. Fetch/read the latest `main` before editing; use the latest file/blob SHA for updates. Do not overwrite a newer change based on a stale SHA.
- After writing, fetch the changed file again and verify the committed contents. Report the actual commit SHA and link when available.
- Do not force-push, rewrite shared history, delete project data, change repository visibility, alter secrets/permissions, or make other destructive/account-level changes without explicit permission.
- Do not silently change the repository's branch strategy.

## 4. Strict build and execution restriction

**Do not run builds unless the user separately and explicitly authorizes them.**

This prohibition includes, but is not limited to:

- Gradle, Maven, CMake, Make, Cargo, npm build, or equivalent build commands;
- APK, AAB, executable, package, or release generation;
- CI builds and GitHub Actions workflows, including manually dispatching a workflow;
- commands that indirectly trigger a build as a side effect.

The user has not granted build permission by asking for architecture work, code edits, commits, pushes, or static review. If a build would be useful, explain why and wait for separate permission.

Static inspection, documentation checks, source review, schema validation that does not compile/build, and other non-build checks are allowed. Do not claim tests, benchmarks, builds, or device measurements were run unless they actually were.

## 5. Architecture is the source of truth

- Main architecture document: `docs/JARVIS_ARCHITECTURE.md`.
- Read the relevant existing sections before changing a subsystem. Preserve established contracts and terminology.
- Update the architecture document whenever a meaningful architectural decision, interface, invariant, parameter profile, failure mode, or project-status change is made.
- Prefer extending the existing numbered sections over creating duplicate or contradictory specifications.
- Keep unresolved decisions explicitly marked as unresolved. Do not silently convert a proposal, starter value, estimate, or planned test into a confirmed result.
- Keep the project-status section consistent with the detailed sections and the actual repository state.
- If an architectural rule conflicts with this instruction file, do not guess: explain the conflict and follow the user's latest explicit instruction.

## 6. Evidence and accuracy

Always distinguish these states:

1. **Proposed** — an idea under consideration.
2. **Specified** — documented behavior, interfaces, formulas, or constraints.
3. **Implemented** — corresponding source code exists.
4. **Verified** — the implementation passed the stated checks.
5. **Measured** — resource use or behavior was measured on a named environment/device.

A documented design is not an implemented feature. A static text check is not a runtime test. A successful commit is not a successful build.

- Cite technical sources for research-derived claims and record useful references in the relevant architecture section.
- Prefer primary papers, official documentation, and reproducible technical references.
- Do not present a paper's parameter values as universal biological constants. Mark them as examples or starting points and explain the need for calibration.
- Do not invent measurements, test outcomes, hardware limits, implementation status, or repository contents.

## 7. Memory, storage, and hardware constraints

- JNVM means JARVIS Neural Virtual Memory; JPVR means JARVIS Persistent Virtual RAM.
- Persistent storage is not physical RAM. A flash/SSD-backed store can retain inactive neural state, but active computation still requires data to be brought into RAM. Account for I/O latency, write amplification, endurance, cache misses, recovery, and thrashing.
- Never promise infinite memory, zero-cost persistence, or that storage-backed state remains continuously active in the same way as RAM.
- Keep hot runtime state, persisted authoritative state, derived/cache values, indexes, and journals conceptually separate.
- Release or evict dirty state only after the persistence layer confirms the required durable commit.
- Device, RAM, storage, operating-system, and performance constraints must be measured or supplied; do not assume a fixed memory budget or target device without evidence.

## 8. Engineering invariants to preserve

Unless the architecture is deliberately revised and the reason documented:

- Keep modules behind explicit, versioned contracts; avoid uncontrolled direct mutation across modules.
- Preserve the Neural Core Controller (NCC) single-writer commit rule for shared authoritative state.
- Use bounded queues, explicit backpressure, priorities, and starvation protection.
- Use stable IDs, idempotency keys, version checks, and explicit lifecycle states for retryable operations.
- Do not claim exactly-once message delivery; preserve the documented at-least-once/deduplication model.
- Do not publish externally visible events before the required durable commit.
- Reject malformed, unsupported, corrupted, or incompatible persisted state rather than silently interpreting it.
- Keep recovery deterministic and fail safely when integrity cannot be established.
- Keep timestamps and clock epochs explicit; monotonic clocks from one process run must not be treated as directly comparable across restarts.
- Bound neural weights, traces, queues, caches, and adaptation values. Unexpected inputs must not cause unbounded growth or uncontrolled plasticity.

## 9. Current documented design snapshot

Use the latest architecture file as the authoritative detail; this summary is only a navigation aid.

- First-neuron model: Adaptive Leaky Integrate-and-Fire with Dynamic Threshold (Adaptive LIF-DT), using event-driven updates and analytical decay.
- Synapse: separate directed object with versioned state; do not duplicate an authoritative weight at both endpoints.
- Learning: pair-based excitatory STDP and reward-modulated STDP have documented mathematical specifications. Inhibitory plasticity remains a separate unresolved design item; do not apply excitatory STDP to inhibitory synapses by default.
- Numeric parameters: `JARVIS_SINGLE_NEURON_STARTER_V1` is a starter profile, not a calibrated production profile. Values must be tested against deterministic sequences and compared with a float64 reference before being treated as validated.
- Neural Core State, Neural Core State Engine, NCC, CQE v1, JNVM, and JPVR have architectural specifications. Specifications must not be reported as implemented subsystems.
- The architecture document contains open questions and planned validation work. Check it before deciding what to do next.

## 10. Recommended workflow for each task

1. Read this file and fetch the latest `main`.
2. Inspect the relevant architecture sections and existing source files before proposing edits.
3. Identify the exact objective and any constraints that apply.
4. Make the smallest coherent change that improves correctness, clarity, safety, or progress toward the first prototype.
5. Update architecture/status documentation when the design changes.
6. Run only checks that do not violate the build restriction. State exactly what was and was not checked.
7. Review the diff for contradictions, duplicated rules, stale status statements, accidental scope expansion, and unsupported claims.
8. Commit and push the change when it is ready; do not run builds or workflows.
9. Fetch the result from GitHub and verify the final contents and commit.
10. Report a concise summary, changed paths, commit link, verification performed, and the next meaningful step.

## 11. Communication

- Be direct, clear, and honest. The user prefers substantive explanations rather than overly short replies.
- Do not repeatedly ask the user to choose between options when a justified engineering decision can be made autonomously.
- Give progress percentages only as clearly labeled rough estimates of architectural planning or implementation scope. Never imply that a percentage is a measured fact.
- State blockers early. Never say "done" for an implementation when only a design document has been written.
- Continue in Russian when communicating with the user unless the user requests another language.
