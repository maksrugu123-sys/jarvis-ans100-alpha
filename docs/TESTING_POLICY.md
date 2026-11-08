# JARVIS Testing Policy

**Status:** mandatory project-wide engineering policy  
**Applies to:** every AI agent, automated contributor, and human contributor  
**Authority:** `AGENTS.md`

## 1. Purpose

Tests are part of the implementation, not optional documentation added after the fact. A change is not adequately supported merely because the code looks plausible or because a previous test suite passed.

This policy applies to source code, numerical models, learning rules, storage and recovery, contracts, task benchmarks, configuration, and user-visible behavior.

## 2. What every change must include

| Change type | Required test/evidence |
|---|---|
| New behavior or API | New tests for normal behavior, contract boundaries, and relevant failure paths |
| Bug fix | Regression test that fails on the old behavior and passes on the corrected behavior, where practical |
| Refactor | Existing behavior tests retained; add tests if behavior or invariants could change |
| Numerical formula or learning rule | Deterministic reference/golden vectors, sign and boundary tests, invalid-value tests, and reset/context tests where relevant |
| State, persistence, or recovery | Round-trip, corruption, interrupted-write, replay/idempotency, and recovery tests as applicable |
| Queue, scheduler, or resource limit | Capacity, backpressure, priority, starvation, ordering, and overload tests as applicable |
| Benchmark/protocol change | Protocol tests plus task-level held-out evaluation; report controls, seeds, sample sizes, and metrics |
| Documentation-only change | No executable test is required unless behavior or examples changed; record the review/check actually performed |
| Removal/deprecation | Tests proving the removed path cannot be used where relevant, plus migration/documentation updates |

If a required test is not feasible, the contributor must state why, identify the risk, and record what alternative review or static validation was performed. Do not silently waive a test.

## 3. Test quality requirements

- Prefer deterministic tests with fixed inputs and explicit seeds.
- Keep tests independent of network access and wall-clock timing unless those are the behavior under test.
- Test both success and failure paths.
- Test lower/upper bounds, empty inputs, duplicates, ordering, invalid values, and reset/restart boundaries where relevant.
- Test invariants, not implementation details, unless a low-level numerical contract is intentionally being verified.
- Avoid tests that merely repeat the implementation's own calculation without an independent expected result.
- Keep unit tests fast enough for routine development; keep large experiments as separately named benchmarks.
- A benchmark must define its training/held-out split and prevent held-out data from affecting learning or tuning.
- Controls must receive matched data and conditions when a comparison claims to isolate one mechanism.
- Preserve canonical profiles such as `JARVIS_SINGLE_NEURON_STARTER_V1`; diagnostic profiles must be explicitly named and must not be described as calibration.

## 4. Running tests

For a broad one-command validation of the implemented neuron and the repository, run from the repository root:

    python3 -m tools.neuron_validation.full_exam

The comprehensive exam runs:
1. Python syntax and unittest-discovery integrity checks.
2. The complete discovered Python unit-test suite under tools/.
3. A deterministic noisy temporal-generalization benchmark with FIXED, STDP, R-STDP and neutral-R-STDP controls, disjoint training/held-out signal/noise signatures, matched distractors and frozen held-out weights.
4. The V5.1 Python-reference resource baseline at the selected profile size.
5. A capability matrix that marks proposed-but-unimplemented mechanisms as NOT_IMPLEMENTED rather than passing them.

Profiles are smoke (quick integration), pilot (default, multi-seed exploratory), and full (larger multi-seed experiment). The default report is neuron_full_exam_report.json. Use --json-report PATH to choose another location and -q for compact unit-test output. Read docs/JARVIS_NEURON_FULL_EXAM.md before interpreting research results.

The one-command comprehensive exam is not proof that every planned JARVIS capability exists. It tests implemented reference mechanisms and reports major gaps. The noisy benchmark is observational and has no automatic performance promotion threshold. BTCC is tested as an isolated oracle, not as an integrated learning rule. V5.1 values are measurements of Python reference objects only.

For lightweight unit-only checks without the temporal-noise benchmark or resource profile, run:

    python3 -m tools.run_all_checks

Focused debugging remains supported, for example:

    python3 -m unittest tools.super_neuron_btcc.test_credit_oracle -v

Neither command launches application builds, APK/AAB packaging, CI or GitHub Actions. Device measurements, full research experiments and production-runtime equivalence remain separate evidence gates.

## 5. Evidence recording

For each test or benchmark run, record in `docs/DEVELOPMENT_LOG.md`:

- exact command and working directory;
- date and environment/version when available;
- test module or benchmark/profile;
- result status: PASS, FAIL, INTERRUPTED, BLOCKED, NOT RUN, or AUTHORED, NOT RUN;
- test count, failures/errors, and duration when reported;
- benchmark seed(s), training/held-out sizes, metrics, and report artifact path;
- any warnings, deviations, or limitations that affect interpretation.

Do not record only "tests passed." Identify which tests passed. Do not call a test "verified" when it was not run. Do not claim the full suite passed after running only one module.

If test output is incomplete, preserve that limitation. If a process is interrupted, record INTERRUPTED rather than treating it as a software failure or a pass.

## 6. What test results do and do not prove

- Unit tests prove only the assertions that were executed.
- Numerical reference tests do not prove that a production runtime matches the reference unless equivalence is tested.
- A benchmark's training accuracy does not establish generalization; inspect held-out metrics.
- A single seed or small pilot is preliminary evidence, not a robust general conclusion.
- A weight update does not by itself demonstrate useful learning or error correction.
- Static analysis does not establish runtime correctness.
- Tests do not establish consciousness, subjective experience, biological equivalence, or general intelligence.

## 7. Change log and documentation

Every meaningful task must append a record to `docs/DEVELOPMENT_LOG.md` following the instructions in `AGENTS.md`. Update `docs/JARVIS_ARCHITECTURE.md` for architectural decisions and `README.md` when the project status or contributor workflow changes.

Do not overwrite historical results. Correct an old record only when it is factually wrong, and explain the correction in a new log entry.
