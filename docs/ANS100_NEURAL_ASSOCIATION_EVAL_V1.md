# ANS-100 neural association held-out evaluation V1

## Status and purpose

**Status: AUTHORED / NOT RUN. Promotion gate: CLOSED.**

This protocol evaluates whether the current explicit-feedback update can improve a bounded concept-association signal beyond fixed topology, shuffled-label feedback, and random weights. It is a research gate, not a claim that ANS-100 understands concepts.

The evaluator is deliberately separate from the Android conversation path. It does not change the network, live decisions, Qwen handoff, or persisted user data.

## Input dataset

The evaluator accepts UTF-8 JSON Lines. Each non-empty line is one unique unordered concept pair:

\`\`\`json
{"concept_a":"concept:dog","concept_b":"concept:animal","related":true,"source":"explicit_user","evidence_ref":"feedback:opaque-reference"}
{"concept_a":"concept:router","concept_b":"concept:banana","related":false,"source":"explicit_user","evidence_ref":"feedback:opaque-reference"}
\`\`\`

Allowed label sources are only \`explicit_user\` and \`verified_external_outcome\`. Model-generated, inferred, and implicit-behavior labels are rejected. Both positive and negative labels are required. Duplicate unordered pairs, conflicting/duplicate rows, self-pairs, malformed IDs and more than 138 concepts are rejected. The evidence reference is an opaque bounded provenance pointer; private message text is not needed.

No real user-labeled dataset is bundled. The test fixture uses synthetic labels only to test validation and determinism; it is not benchmark evidence.

## Splits and controls

- \`pair_disjoint\`: stratified deterministic split; the same unordered pair cannot occur in training and held-out data. Concepts may recur across splits.
- \`concept_disjoint\`: concept-level split; held-out concepts do not appear in training. Cross-partition pairs are excluded and counted. Both classes must remain in each partition or the run is blocked.
- Thresholds are selected on training data only and frozen before held-out scoring.
- Seeds are fixed and reported.

Four matched conditions are evaluated:
1. **learned** — initial 0.75 concept projection weights updated by the source rule using training labels only.
2. **fixed** — unchanged initial topology weights.
3. **shuffled_labels** — same training pairs and update rule, but training labels are deterministically shuffled.
4. **random_weights** — deterministic random weights with no feedback.

Metrics include accuracy, balanced accuracy, precision, recall, specificity, F1, confusion matrix, sample counts, split sizes and class counts. No probability calibration is claimed.

## Faithfulness and limits

The script mirrors the Android v4 concept projection formula (three outgoing edges, multiplier 37, slot step 113, salt 11, 1,000 association targets) and the bounded reward update (learning rate 0.02, per-event delta cap 0.01, weight range 0.05..1.0). Its sorted concept-ID slot assignment models a fresh V5 registry populated from the complete approved concept inventory. Android now persists mappings across later inventory changes; this offline proxy does not replay historical SQLite allocation chronology, so reports remain topology/weight proxies rather than exact database replays.

The pair score is an **offline topology/weight proxy**: the average weight of both input synapses for shared association targets, normalized by the three possible targets. It is not the Android LIF membrane equation, actual spike scheduling, SQLite feedback transaction, app's concept activation, or on-device runtime. Results cannot be presented as Android performance or semantic understanding.

The Android decoder currently reports co-activation candidates, but learned weights are not used to rank or select live decisions. Keep that gate closed. A future promotion requires real user-verified data, adequate held-out sample sizes, repeated seeds, clear improvement over all controls, false-association analysis, and a separate replay/ablation against the Android runtime. If the concept-disjoint split cannot retain both labels, report BLOCKED rather than weakening the split.

## Commands

From repository root:

\`\`\`sh
python3 -m unittest tools.task_validation.test_neural_association_eval_v1 -v
python3 -m tools.task_validation.neural_association_eval_v1 --dataset path/to/verified_pairs.jsonl --split pair_disjoint --seeds 104729,130363,155921,181081 --report neural_association_eval_v1_report.json
python3 -m tools.task_validation.neural_association_eval_v1 --dataset path/to/verified_pairs.jsonl --split concept_disjoint --seeds 104729,130363,155921,181081 --report neural_association_concept_disjoint_report.json
\`\`\`

These are Python-only checks/experiments. They do not build Android/Windows packages or launch GitHub Actions.
