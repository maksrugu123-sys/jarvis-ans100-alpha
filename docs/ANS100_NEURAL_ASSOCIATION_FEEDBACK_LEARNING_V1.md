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

## Required next integration

- Add an explicit feedback action to the Android UI/Core path.
- Store candidate eligibility and apply the returned deltas to the SQLite `connections.weight` rows in one bounded transaction.
- Make the persistence operation idempotent per feedback event to prevent duplicate taps/retries from repeatedly training the same candidate.
- Add restart/restore tests and an evaluation that compares trained synapses against shuffled-pair and random-weight baselines on held-out concept pairs.
- Only then connect learned association strength to neural competition; do not let Qwen author rewards or choose the winning candidate.

## Verification

Tests were added for positive/negative reward, untrusted feedback rejection, candidate mismatch, missing weights, and weight bounds. Tests were authored but not run in this stage. No Gradle build, APK, CI, or device inference was run.
