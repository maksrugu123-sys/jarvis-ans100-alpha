# JARVIS Development Log

This is the append-only record of meaningful implementation, test, experiment, architecture, and documentation work.

**Mandatory for all contributors and agents:** follow `AGENTS.md` and `docs/TESTING_POLICY.md`. Chat history and commit messages are not substitutes for this log.

## Entry format

Each entry should include:

- Date and objective.
- Added, changed, fixed, or removed files/behavior.
- Architectural or technical decisions and their rationale.
- Tests added/updated, with paths and the behavior they cover.
- Exact verification commands, actual status, environment, counts, and duration when available.
- Checks not run and why.
- Benchmark profiles, seeds, train/held-out sizes, result metrics, and report paths where applicable.
- Remaining limitations and the next meaningful step.

Use the status vocabulary from `AGENTS.md`: PASS, FAIL, INTERRUPTED, BLOCKED, NOT RUN, AUTHORED, NOT RUN.

Do not edit old entries to make a result look better. Add a correction entry if an earlier record needs correction. Local JSON reports should be named and described, but should not be committed unless they are intentionally selected as durable project artifacts.

---

## 2026-11-07 — Require durable task records and test evidence

**Objective:** make it mandatory for future agents and contributors to record additions, modifications, tests, results, and limitations in the repository itself.

**Added:**
- `docs/TESTING_POLICY.md` — project-wide test requirements, test quality expectations, verification commands, evidence labels, and interpretation limits.
- `docs/DEVELOPMENT_LOG.md` — this append-only task and verification history.

**Changed:**
- `AGENTS.md` — mandatory development-log entries, test requirements, verification status vocabulary, and completion-report requirements added to the agent instructions.
- `README.md` — contributor-policy and development-log documents added to the repository map.

**Tests added or changed:** none. This is a documentation/process-only change; no source behavior or test code was changed.

**Verification:**
- Repository files were fetched from GitHub `main` before editing.
- Post-commit fetch verification is required and will be recorded after all writes complete.
- Executable tests: **NOT RUN — not applicable to this documentation-only policy change**.

**Builds / CI / GitHub Actions:** NOT RUN. No build or workflow was triggered.

**Limitations:** this policy is enforced through repository instructions and contributor workflow; no automated GitHub branch protection or CI enforcement was configured.

**Next step:** all future meaningful tasks must append their own evidence-backed entry here.

## 2026-11-07 — Verify contributor policy publication

**Verification:** fetched the final files again from GitHub `main` after the documentation commits. Confirmed that:
- `AGENTS.md` contains the mandatory change-log, testing, status-vocabulary, and completion-report rules.
- `docs/TESTING_POLICY.md` contains the required test matrix, execution guidance, and evidence-recording rules.
- `docs/DEVELOPMENT_LOG.md` contains the append-only entry format and the initial policy-change record.
- `README.md` links to the agent instructions, test policy, and development log without a duplicate agent-policy row.

**Git commits created for this policy change:**
- `991e6ab0aa92c34c1caa6d8665035fffe2e8e6f0` — agent requirements.
- `af8a3e8e2b228d51c4ee7220277113d25a16deea` — testing policy.
- `bad5ccd73b6d9184d51e37f9ba82170ca640a8a7` — development log.
- `568bcc40b2df1f3f7fb0b171ea47c8241993eeb8` — initial README links.
- `ccd73c43bc2aae03c9013494a8ff90512fd19346` — remove duplicate README map entry.

**Executable tests:** NOT RUN — documentation-only contributor-policy change.  
**Builds / CI / GitHub Actions:** NOT RUN.
