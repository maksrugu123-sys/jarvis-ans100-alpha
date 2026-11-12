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
- Keep Reasoning Engine sessions bounded: limit task decomposition, hypothesis count, plan depth, retries, context size, time, and compute. Preserve evidence provenance and unresolved contradictions; do not treat heuristic confidence scores as calibrated probabilities.
- Reasoning Engine must not bypass Permission & Safety Gate, grant itself capabilities, mutate authoritative shared state directly, or repeat side-effecting commands without CQE idempotency/commit handling.

## 9. Current documented design snapshot

Use the latest architecture file as the authoritative detail; this summary is only a navigation aid.

- First-neuron model: Adaptive Leaky Integrate-and-Fire with Dynamic Threshold (Adaptive LIF-DT), using event-driven updates and analytical decay.
- Synapse: separate directed object with versioned state; do not duplicate an authoritative weight at both endpoints.
- Learning: pair-based excitatory STDP and reward-modulated STDP have documented mathematical specifications. Inhibitory plasticity remains a separate unresolved design item; do not apply excitatory STDP to inhibitory synapses by default.
- Numeric parameters: `JARVIS_SINGLE_NEURON_STARTER_V1` is a starter profile, not a calibrated production profile. Values must be tested against deterministic sequences and compared with a float64 reference before being treated as validated.
- Neural Core State, Neural Core State Engine, NCC, CQE v1, JNVM, and JPVR have architectural specifications. Specifications must not be reported as implemented subsystems.
- Reasoning Engine V0.1 is implemented as a bounded deterministic Python reference planner/evidence assessor (architecture §§3.23/3.44); Permission & Safety Gate V1 and Task Loop V0.1 are implemented as separate reference modules (§§3.45–3.46). They do not provide natural-language parsing, production NCC, built-in tools, or a trusted production identity/approval service.
- Native Android: Compose V0.5 retains its separate bounded SharedPreferences draft repository and uses the fixed loopback Python local-console API v2 at 127.0.0.1:8765. State reads and all POST routes require a random Bearer token; the native app stores it encrypted with Android Keystore and the PWA holds it only in tab sessionStorage. The Termux service stores only the token SHA-256 digest and must remain bound to 127.0.0.1. The JPVR panel can read/create sessions and tasks, change session lifecycle, cancel queued tasks, and request a bounded non-mutating symbolic Reasoning Engine preview. Token possession is not a trusted human identity or an exact-action approval. The preview is not natural-language understanding, approval, Task Loop execution, ActionExecutor, or an external tool call. Never label a queued task or proposed plan as executing/completed. Do not add side effects until a trusted identity/approval provider, persistent policy/revocation, and executor-owned durable idempotency/recovery contracts exist. See docs/JARVIS_ANDROID_APP_V0_1.md and docs/JARVIS_ANDROID_RUNTIME_BRIDGE_V1.md.
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

## 12. Mandatory change log — record every meaningful task

**Every agent must leave a durable repository record of the work.** Do not rely on chat history, commit messages, terminal scrollback, or memory as the only record.

For every coherent task or batch that changes code, tests, architecture, configuration, research protocols, documentation, or project status:

1. Append an entry to `docs/DEVELOPMENT_LOG.md`. The log is append-only: do not rewrite or silently delete earlier entries. Related edits in one coherent batch may share one entry.
2. Record the date, goal, and a clear list of files added, changed, or removed.
3. Describe what was implemented or documented, what behavior changed, and the reason for the change. Distinguish implementation from a proposal or specification.
4. Record tests added, updated, or removed, with their file paths and the behavior they cover.
5. Record every verification command actually executed, its exact scope, environment when known, and the real result (pass/fail, counts, duration if available). Include benchmark profile, seeds, dataset size, and report path for experiments.
6. List relevant checks that were not run, with the reason. Explicitly mark tests as **authored but not run** when that is the truth.
7. Record known limitations, failed/interrupted checks, unresolved questions, and the next meaningful step.
8. Link to the relevant architecture, test protocol, and result document rather than copying large specifications into the log.

Do not omit a meaningful addition, bug fix, refactor, test, experiment, architectural decision, configuration change, or removal. For a documentation-only change, state that no executable tests were applicable and identify any documentation/static checks actually performed. For an unfinished task, record the partial state and remaining work rather than presenting it as complete.

The project status in `README.md` and the authoritative design in `docs/JARVIS_ARCHITECTURE.md` must be updated when the task changes their subject matter. The development log complements these documents; it does not replace them.

## 13. Mandatory test policy

Read `docs/TESTING_POLICY.md` before adding or changing tests. The following rules are mandatory:

- **New behavior requires tests.** Add tests for each meaningful new behavior, invariant, public contract, or failure mode.
- **Bug fixes require regression tests.** Reproduce the old failure in a test when practical, then assert the corrected behavior.
- **Changed behavior requires changed tests.** Update stale assertions and preserve tests for behavior that must not regress.
- **Numerical/learning changes require deterministic evidence.** Cover signs, boundaries, limits, ordering, reset/context isolation, invalid inputs, and reference/golden vectors as relevant. Keep task-level held-out benchmarks separate from unit tests.
- **Test both positive and negative paths.** Include malformed input, no-op/neutral behavior, limits, and failure/recovery paths when those are part of the contract.
- Run the narrow relevant test first, then the full available unit-test suite when practical and permitted. Record the exact commands and actual results.
- If a test cannot be written or run, explain why in the development log and final report. Do not silently skip it.
- Never report a test as passed because it was merely written, inspected, or expected to pass. Never convert a partial test run into a claim that the whole suite passed.
- Do not claim coverage percentages unless a coverage tool actually ran and its measured scope is stated.
- Keep tests deterministic where possible; record random seeds and fixture/dataset details for reproducibility.
- Do not change canonical STARTER values or learning equations merely to make a test or benchmark pass. Any diagnostic perturbation must be named and separated from canonical configuration.

### Verification status vocabulary

Use these labels consistently:

- **PASS** — the stated command completed successfully and all included assertions passed.
- **FAIL** — the command completed and one or more assertions/checks failed.
- **INTERRUPTED** — execution was stopped before completion (for example, Ctrl+C).
- **BLOCKED** — execution could not start or finish because of an external prerequisite or environment limitation.
- **NOT RUN** — the check was not attempted.
- **AUTHORED, NOT RUN** — test code was added or changed, but it has not been executed.

A benchmark result is not a unit-test result. A unit-test pass does not prove task-level usefulness. A successful static inspection does not prove runtime behavior.

## 14. Required completion report

At the end of each task, report in the user-facing response:

- **Added / changed / fixed / removed:** meaningful files and behavior.
- **Tests:** tests added/updated, exact tests executed, and their actual status; separately list unrun or blocked checks.
- **Evidence:** benchmark profile, seeds, sample sizes, metrics, or device/environment when relevant.
- **Limitations:** what remains unverified or incomplete.
- **GitHub:** branch, commit SHA, and commit link when a commit was created.

Keep this summary consistent with `docs/DEVELOPMENT_LOG.md`. Never hide a failed test, interrupted benchmark, or known limitation. Do not claim CI, a build, a device measurement, or a push occurred unless it actually did.

