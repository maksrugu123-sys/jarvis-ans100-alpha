# ANS-100 Concept Activation Adapter V1

## What it does
The adapter connects the neutral lexical input frame to explicit concepts already stored in ANS100ConceptMemory. It compares observed token/bigram feature IDs with the concept's canonical label and explicitly supplied aliases. A single-word alias requires its exact token feature. A multiword alias requires all token and adjacent-bigram features produced by the same bounded encoder. It returns bounded, deterministic concept activations with the matched alias and exact feature IDs.

## Safety and semantics
- It does not invent synonyms, infer translations, or perform embedding-based similarity.
- It does not create or modify concepts or relations.
- Ambiguous aliases activate all matching concepts; the adapter does not silently pick a winner.
- Activation is the minimum observed lexical feature activation for the matched alias. It is not a probability or confidence in semantic correctness.
- Only explicitly stored labels and aliases can ground a concept.
- The result is an experimental intermediate representation and is not wired into production candidate routing or persisted neural assemblies.
- `ANS100ConceptNeuralInputAdapter` can map grounded activations to stable, memory-instance concept input neurons 1062..1199 and merge their presence pulses into a typed hybrid turn batch. The hybrid runtime validates both evidence and concept traces before queueing.
- Concept slots are allocated lazily and remain stable for the lifetime of one in-memory concept store; at most 138 concepts can receive dedicated input neurons in this prototype. Slot exhaustion is explicit.
- The topology's existing general evidence projection carries these pulses into the shared association population. They do not yet have dedicated learned concept assemblies or independent neural readouts.

## Why this stage matters
This creates an auditable bridge from raw user-text observations to concept identities. Later stages can associate concept activations with learned assemblies in the sparse neural runtime, propose relation hypotheses, and evaluate them against evidence. The current implementation is exact lexical grounding only; it is not autonomous concept recognition.

## Next gates
1. Learn concept assemblies from verified experience, not from labels alone.
2. Map concept/relation activations to dedicated neural populations without colliding with persisted topology IDs.
3. Have network activity produce hypothesis candidates and retain premise/evidence provenance.
4. Evaluate ambiguous, paraphrased, multilingual, and held-out concepts separately.
5. Prove transfer on unseen concept IDs and measure false activations, CPU, RAM, and latency before production integration.
