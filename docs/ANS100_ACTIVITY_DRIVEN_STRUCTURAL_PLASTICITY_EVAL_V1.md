# ANS-100 activity-driven structural-plasticity evaluation V1

## Status

**IMPLEMENTED as an offline Python reference harness; tests AUTHORED / NOT RUN. Promotion gate: CLOSED.**

The harness is separate from the Android conversation pipeline. It does not enable automatic topology growth, alter persisted user data, change live decisions, or give Qwen a reasoning role.

## Question being tested

Does the current activity-driven rule create a stable shared association target when the same pair of concept inputs co-activates on three distinct turns? How often does the same rule also create links for repeated but explicitly labelled negative pairs?

This is a test of a temporal co-activation heuristic. It is not a test of semantic understanding or generalization.

## Deterministic temporal dataset

The built-in fixture contains eight unordered concept pairs and sixteen distinct concepts:

- four labelled positive pairs;
- four labelled negative pairs;
- two of the negative pairs are deliberately difficult controls: they also co-activate on a third held-out turn;
- the other two negative pairs have only two observations.

For every pair, the first two distinct co-activations occur in the training-time portion. The held-out portion contains the third co-activation for all four positives and the two hard negatives. Pair order is deterministically varied by seed. The labels are used only for final scoring; they are not passed to the learner.

This temporal design checks whether the implementation respects the three-distinct-turn threshold and exposes the core limitation: repeated co-activation can also create false associations for hard negatives.

## Compared conditions

1. **fixed_topology** — only the three deterministic concept-projection targets from AndroidHybridTopologyV2; a pair is a structural candidate if it shares at least one target.
2. **grown_topology** — fixed projection plus pairs receiving a new shared target from the activity-driven rule after three distinct co-activation turns.
3. **shuffled_activity_control** — same event count, with B-side concept identities rotated between temporal rounds. This breaks stable pair identity while preserving the number of events and per-round activity shape.

The fixed shared-target test is only a necessary-condition upper bound for decoder candidates. It does not simulate synapse activation or an association-neuron spike.

## Faithfulness to Android implementation

The reference harness mirrors these current Android constants and rules:

- concept input slots: 1062–1199 (138 slots);
- general association targets: 1200–2199 (1,000 targets);
- fixed projection fan-out: 3;
- projection formula: (sourceNeuronId * 37 + slot * 113 + 11) % 1000;
- growth eligibility: 3 distinct turns within a 16-turn observation window;
- default maximum grown pairs: 256;
- default maximum grown edges per concept: 4;
- default target incoming-edge cap: 12;
- deterministic SHA-256 starting target, then least-loaded target selection;
- each proposal adds exactly two synapses to one shared target.

The script models fixed incoming target loads from all concepts in the fixture before growth. It updates source target sets and target loads after each proposal, so later proposals cannot reuse an existing source-target path or ignore the new incoming load.

## Metrics and resource accounting

Each condition reports accuracy, precision, recall, specificity, F1, TP/FP/TN/FN, false-association rate (FP / (FP + TN)), candidate-pair count, proposals and new synapses, pair-comparison operations, target-scan steps, peak pending pair traces, maximum target in-degree, maximum per-concept grown degree, and estimated logical payload bytes.

The payload estimate is deterministic bookkeeping, **not** measured RAM, Python heap size, SQLite database size, Android storage use, CPU time, or latency. Operation counts are algorithmic proxies, not wall-clock benchmarks.

Four fixed seeds are included by default. The JSON report preserves per-seed results and mean metrics. Seeds alter deterministic event order; they do not create independent real-world datasets.

## Important limitations

- All labels and activity streams are synthetic. No real user or external evidence is included.
- The harness does not run Android LIF membrane dynamics, event queues, actual spike propagation, decoder execution, SQLite transactions, app-level concept activation, or device inference.
- The simulator is a focused reference for the bounded structural proposal policy, not an exact replay of the Android runtime.
- Co-activation does not prove similarity, typed relation, causality, truth, or usefulness. The hard-negative cases are intentionally included to show this.
- The fixture is small and constructed. Its metrics must not be represented as a meaningful estimate of real-world accuracy.
- STRUCTURAL_PLASTICITY_AUTO_GROWTH_ENABLED must remain false. The result does not authorize production activation.

## Commands

From the repository root:

```sh
python3 -m unittest tools.task_validation.test_structural_plasticity_eval_v1 -v
python3 -m tools.task_validation.structural_plasticity_eval_v1
python3 -m tools.task_validation.structural_plasticity_eval_v1 --seeds 104729,130363,155921,181081 --report structural_plasticity_eval_v1_report.json
```

These are Python-only checks and reference experiments. They do not build Android or Windows packages and do not dispatch GitHub Actions.
