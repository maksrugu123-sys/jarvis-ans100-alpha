# ANS-100 neural association feedback learning V1

## Purpose

This stage adds a bounded learning adapter for the exact concept-to-association synapses that participated in a decoded same-turn co-activation candidate. It builds on the existing `ANS100CandidateFeedbackLearner` rather than introducing a second reward formula.

## Update path

1. The decoder supplies a candidate containing the actual convergent association neurons and activated connection IDs.
2. The caller supplies the current persisted weight for every candidate connection and a feedback record tied to the same turn and candidate.
3. The adapter verifies that every connection ID is one of the topology's reserved concept-input projection edges and that each convergent target has exactly two distinct concept-input edges in the candidate.
4. Only `EXPLICIT_USER` and `VERIFIED_EXTERNAL_OUTCOME` are trusted. Model-generated and implicit-behavior feedback are rejected.
5. Positive reward strengthens and negative reward weakens the candidate's eligible synapses. The existing rule bounds each update to at most 0.01, keeps weights in 0.05..1.0, and scales the delta by the candidate's activation hint.
6. The adapter returns updates; it does not write SQLite or mutate the network.

## Safety and scope

- A feedback record must name the exact candidate ID and a nonblank turn ID.
- Missing current weights reject the whole update; no partial update is returned.
- The candidate's connection IDs must exactly match the real topology projection for its convergent targets. Arbitrary or unrelated synapses cannot be trained through this adapter.
- Maximum six synapses are updated per candidate.
- This is local credit assignment to the edges that participated in a candidate, not proof that the concepts are semantically related.
- It does not learn new topology, create edges, persist weights, restore weights, or alter the active dialogue decision.
- The caller must still persist returned weights atomically and expose a deliberate user feedback action. Until that integration exists, this is a tested learning kernel, not an active production learning loop.

## Android SQLite and explicit feedback integration

The Android integration now provides a separate, concept-specific learning path:

- The conversation UI displays a separate question asking whether the two concepts are related. This is deliberately distinct from the generic "helpful answer" rating; answer quality must not be misattributed to an unrelated co-activation pair.
- Only the first bounded neural association candidate is offered for feedback in V1.
- The candidate's exact activated concept-to-association synapses and activation hint are saved as a short-lived eligibility trace.
- The user action receives a stable feedback event ID before the write is attempted. SQLite inserts that ID and updates the affected `connections.weight` rows in one transaction. A duplicate event ID is ignored, so retrying the same action cannot train twice.
- Learned weights live in the existing `connections` table and are naturally restored when the network database is reopened.
- Schema version 4 adds bounded eligibility and feedback-event tables. The v3-to-v4 migration leaves topology and unrelated synapses intact and gives only reserved concept-input projection edges room for positive learning.
- Concept projection synapses start at 0.75 (instead of the general feed-forward ceiling 1.0); explicit positive feedback can strengthen them and negative feedback can weaken them. Weights remain bounded to 0.05..1.0, with per-event delta at most 0.01.
- The UI explicitly asks about the concept pair. Qwen output and generic answer feedback are not used as labels.

## Remaining research work

- The association candidate still does not participate in final answer/decision competition. The explicit pair label trains only the observed concept-to-association synapses.
- Add held-out evaluation against shuffled-pair and random-weight baselines before using learned strengths to influence decision competition.
- Add multi-turn calibration and check whether lexical concept activation and deterministic projection collisions produce false associations.
- Do not claim semantic understanding from this feedback mechanism alone.

## Verification

Instrumented Android tests were added for persistent weights across reopen, duplicate-event idempotency, rejection of model-generated feedback, and v3-to-v4 schema migration without topology rebuild. Topology tests now assert concept-specific initial weights. Tests were authored but not run. No Gradle build, APK, CI, or device inference was run.
