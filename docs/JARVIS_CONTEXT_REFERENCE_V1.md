# JARVIS Context Reference V1

JARVIS Core may select a recent dialogue turn as a **context candidate** before language generation.

## Contract

- Only the last four history messages are considered.
- User messages are candidate turns; the adjacent assistant reply is retained as supporting dialogue context.
- A short affirmative follow-through such as `Давай` targets the adjacent assistant offer when a recent user turn exists.
- An explicit topic anchor is matched against prior user and assistant text using bounded lexical overlap. User-message matches receive greater weight.
- A unique best match is selected. Equal-scoring candidates, unsupported topic anchors, or multiple unanchored recent turns are marked ambiguous and require clarification.
- A single recent user turn may be selected by adjacency when the follow-up contains no independent topic anchor.
- The selected excerpt and confidence are heuristic hints, not semantic proof. The model must verify them against the original conversation and must not infer missing details.
- Selection is never execution authority. External actions remain separately gated.

## Status values

| Status | Meaning |
| --- | --- |
| `not_requested` | The current message does not refer back to dialogue context. |
| `selected` | One recent dialogue turn was selected as the best candidate. |
| `ambiguous` | More than one candidate remains plausible, or the available evidence is insufficient. |
| `unavailable` | No recent user turn is available. |

## Verification boundary

Python and Android implementations are intended to follow the same deterministic candidate-selection rules. Tests are authored alongside the implementation; they are not claimed as passing until run.
