# ANS-100 Neutral Neural Input Frame V1

## Purpose

Introduce a narrow, testable input boundary for ANS-100 that does not encode a
Core-selected intent, answer, action, candidate winner, support signal, or
conflict signal.

## V1 contract

- Input: one bounded raw user message and a bounded turn identifier.
- Encoder: the existing deterministic lexical encoder.
- Feature data: stable lexical feature ID, TOKEN/BIGRAM kind, activation in
  0..1, and source marker USER_TEXT_LEXICAL.
- Limits: inherited 2,048-character input, 96 tokens, and 128 output features.
- Determinism: same turn ID and message produce the same frame.
- No model-generated output is accepted as a learning signal by this adapter.

## Important limitation

This is a neutral lexical feature frame, not semantic understanding and not a
trained neural reasoning system. Token and bigram features do not establish
meaning. The frame is deliberately not connected to production routing. The
existing TypedEvidenceBundle and hybrid candidate controller still contain
Core-generated heuristic intent/plan/candidate signals; those must be separated
in a later migration before ANS-100 can independently form and evaluate
hypotheses.

## Validation

Unit tests cover deterministic output, bounds, turn identity, lexical source
provenance, and absence of preselected decision/candidate labels. Android CI
must run before considering integration.
