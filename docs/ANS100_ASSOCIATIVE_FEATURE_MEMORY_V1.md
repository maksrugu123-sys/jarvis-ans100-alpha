# ANS-100 Associative Feature Memory V1

## Status

Experimental research component. It is not connected to Android chat routing or the production decision controller. It must not be described as semantic understanding.

## Why this stage exists

The current Android typed-evidence path explicitly marks Core interpretation and candidate support/conflict signals as heuristic. The sparse 10,000-neuron topology propagates those labels, but it does not independently derive their meaning. This component starts a separate, measurable experiment instead of disguising those heuristics as learned cognition.

## V1 responsibilities

### 1. Bounded lexical feature extraction

`ANS100AssociativeFeatureEncoder` converts a message into deterministic token and adjacent-token bigram features. It:
- lowercases with `Locale.ROOT`;
- removes a small fixed stop-word list;
- caps input at 2,048 characters, 96 tokens and 128 features;
- emits stable feature IDs and bounded activations;
- does not emit intent, goal, sentiment, truth, or semantic labels.

These are lexical/structural features only. Shared words do not prove that two messages have the same meaning.

### 2. Explicit-feedback association learning

`ANS100AssociativeFeatureMemory` stores a bounded Hebbian-style co-occurrence table:
- maximum 4,096 feature pairs by default;
- initial association weight 0.5;
- weights are clamped to 0.05..1.0;
- default learning rate 0.05;
- positive and negative explicit feedback can strengthen or weaken co-occurrence;
- only `EXPLICIT_USER` and `VERIFIED_EXTERNAL_OUTCOME` feedback sources can update weights;
- model-generated text and implicit behavior are rejected as learning signals.

Recall returns associated lexical feature IDs, not an answer, intent, or action.

## Safety and integration boundary

- The current Android production pipeline and candidate decision controller remain unchanged.
- This V1 component has no persistence, file/network access, tool execution, or authority to trigger actions.
- No unverified model output can teach the association table.
- It is not yet connected to the 10,000-neuron sparse runtime. Integration requires a separate reviewed adapter and migration plan.
- A feature association is not a probability, confidence score, causal explanation, or semantic relation.

## Required evaluation before integration

1. Compare feature extraction on held-out Russian and English messages, including inflections, punctuation, misspellings, and paraphrases.
2. Measure association precision/recall against manually reviewed pairs; split by conversation/topic to prevent leakage.
3. Test negative feedback, conflicting feedback, cold start, pair-budget saturation, and deterministic replay.
4. Measure CPU time, allocations, retained memory, and database impact on representative Android hardware.
5. Compare against a no-learning baseline. Do not claim improvement unless held-out metrics improve without unacceptable memory/latency regressions.

## Next stage

Build a held-out association benchmark and a turn-scoped activation/eligibility trace adapter. Only after the benchmark demonstrates useful generalization should the association memory be connected to ANS-100's neural runtime. The response language model remains a downstream verbalizer; it must not create the decision or learning labels.
