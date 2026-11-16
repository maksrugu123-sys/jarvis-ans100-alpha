# ANS-100 independent neural-association assessment V1

## Scope

This stage adds a bounded assessment layer for already decoded association candidates. It does not grow, prune, redirect, or otherwise mutate network topology. It also does not change synaptic weights from assessment outcomes.

## Independent task evidence

Each persisted observation names:
- the stable association candidate ID;
- a unique task ID;
- an independence key identifying the task source/template or evaluation unit;
- one outcome: `REPRODUCED`, `CONTRADICTED`, or `INCONCLUSIVE`;
- evidence origin: `CONTROLLED_BENCHMARK_LABEL` or `VERIFIED_EXTERNAL_OUTCOME`.

SQLite enforces uniqueness for both (candidate, task ID) and (candidate, independence key). A duplicate task or a correlated task presented under the same independence key is ignored rather than counted as another independent reproduction. Observation storage is bounded.

An inconclusive or missing reproduction is not a contradiction. Only decisive reproduced/contradicted outcomes contribute to the stability ratio.

The held-out benchmark adapter accepts only `pair_disjoint`, `concept_disjoint`, or `compositional` splits and direct-pair task families (`relation_status`, `evidence_conflict`). It requires an explicit independence key from the harness. Multi-hop reachability tasks are not treated as direct semantic-pair labels. `YES` plus an observed neural candidate is a reproduction; `NO` plus an observed candidate is a contradiction. If the candidate is absent, or the label is `UNKNOWN`/`CONFLICT`, the outcome is inconclusive.

## Assessment policy

- Fewer than three decisive independent outcomes: `INSUFFICIENT_EVIDENCE`.
- At least three reproductions and a reproduction ratio of at least 0.80: `STABLE`.
- A decisive reproduction ratio of at most 0.40: `UNSTABLE`.
- Other adequately sampled results: `MIXED`.

Explicit positive/negative feedback is compared with the independent assessment. The network reads the latest persisted explicit-user or verified-external feedback event for that candidate; callers do not have to restate the label. The result can be `ALIGNED`, `CONFLICTS`, `NOT_YET_DECIDABLE`, or `NO_FEEDBACK`, and the assessment preserves the feedback source. Feedback disagreement is surfaced; it is not silently resolved in favor of either source.

These thresholds are engineering policy for a first bounded experiment, not calibrated probabilities or proof of semantic truth.

## Weight-change history

Every actual weight change caused by accepted explicit-user or verified-external feedback is recorded in the same SQLite transaction as the synapse update. Each row stores the feedback event ID, turn, candidate, connection ID, previous weight, next weight, reward, source and timestamp. Duplicate feedback event IDs remain idempotent. History is bounded and oldest entries are pruned.

A feedback event that does not change a weight does not create a fictitious change-history row. The pre-existing feedback-event ledger still records the accepted event.

## Safety boundary

- Repeated activation alone is not a teaching signal.
- Independent assessment outcomes do not automatically reward or punish synapses.
- A contradiction is not inferred merely because an association failed to reproduce.
- Only explicit user feedback or a trusted verified external outcome can update weights through the existing production feedback path.
- Model-generated and implicit-behavior feedback remain rejected.
- Automatic structural growth remains disabled: `STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED = false`.

## Remaining integration work

A held-out benchmark adapter now maps direct-pair task outcomes to assessment observations without treating missing activations as negative evidence. The assessment API and SQLite persistence are available to a controlled held-out evaluation harness. The latest explicit feedback is automatically compared with persisted task outcomes. The current conversation path does not yet automatically turn arbitrary live dialogue turns into independent labelled task outcomes. That must be wired only through benchmark tasks with explicit labels and documented independence keys; ordinary model output must never be treated as ground truth.

## Verification state

Pure assessment tests and the database-backed implementation were authored. They have not been run. No Gradle build, Android test, APK, CI, or on-device experiment was run.
