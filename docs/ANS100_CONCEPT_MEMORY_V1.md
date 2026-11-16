# ANS-100 Concept Memory V1 (experimental)

## Purpose
This is the first concept-level memory substrate, distinct from lexical co-occurrence. It stores stable concept identities, labels/aliases, typed relations, and provenance. A concept is not a token: labels and aliases can refer to one stable concept ID; relation types express links between concept IDs.

## Current capabilities
- Bounded concept and relation storage.
- Typed relations: IS_A, PART_OF, CAUSES, ENABLES, PREVENTS, HAS_PROPERTY, USED_FOR.
- Learning accepts only explicit user confirmation or verified tool evidence.
- Deterministic bounded relation recall.
- Candidate hypotheses from two-hop IS_A and PART_OF paths, retaining premise IDs and evidence references.
- Input-scoped hypothesis generation (`generateHypothesesFor`) starts only from concept IDs activated in the current turn; unknown IDs are rejected and unrelated stored concepts are excluded.
- Android persists concept-to-input-neuron assignments in SQLite; the concept graph object itself is still reconstructed from approved workspace records each turn.
- Verification against direct trusted evidence: SUPPORTED, REFUTED, CONFLICTING, or UNRESOLVED.
- CAUSES is deliberately not transitively chained; causal claims require intervention/counterfactual evaluation, not naïve graph composition.

## Limitations
This is a symbolic concept-graph memory experiment, not proof of semantic understanding and not yet neural concept formation. Hypothesis generation currently uses a bounded explicit relation-composition rule. The neural network does not yet learn concepts or generate these hypotheses from its own activity. No persistence, cross-session retrieval, ontology import, embeddings, or production dialogue routing is added.

## Integration gates
1. Represent concept nodes and relation activations in the neural runtime without Core-preselected conclusions.
2. Let the network activate candidate relations from raw/approved evidence and concept memory.
3. Make candidate generation a network readout; retain symbolic generation only as a baseline.
4. Verify candidates with provenance-aware evidence, counterevidence, and uncertainty.
5. Test transfer by learning a relation pattern in one domain and evaluating unseen concept IDs in another.
6. Measure false inference rate, recall, latency, RAM, and deterministic replay before production routing.

Do not describe this component as autonomous reasoning or causal understanding.
