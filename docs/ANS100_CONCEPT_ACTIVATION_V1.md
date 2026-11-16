# ANS-100 Concept Activation Adapter V1

## What it does
The adapter connects the neutral lexical input frame to explicit concepts already stored in ANS100ConceptMemory. It compares observed token/bigram feature IDs with the concept's canonical label and explicitly supplied aliases. A single-word alias requires its exact token feature. A multiword alias requires all token and adjacent-bigram features produced by the same bounded encoder. It returns bounded, deterministic concept activations with the matched alias and exact feature IDs.

## Safety and semantics
- It does not invent synonyms, infer translations, or perform embedding-based similarity.
- It does not create or modify concepts or relations.
- Ambiguous aliases activate all matching concepts; the adapter does not silently pick a winner.
- Activation is the minimum observed lexical feature activation for the matched alias. It is not a probability or confidence in semantic correctness.
- Only explicitly stored labels and aliases can ground a concept.
- The result is an experimental intermediate representation. It now enters the live hybrid dialogue input path, but it does not control candidate selection or constitute persisted neural assemblies.
- `ANS100ConceptNeuralInputAdapter` can map grounded activations to stable, memory-instance concept input neurons 1062..1199 and merge their presence pulses into a typed hybrid turn batch. The hybrid runtime validates both evidence and concept traces before queueing.
- Concept slots are allocated lazily and remain stable for the lifetime of one in-memory concept store; at most 138 concepts can receive dedicated input neurons in that store. Slot exhaustion is explicit. The live workspace bridge rebuilds its concept store from the approved-memory snapshot on each turn, so slot assignments are not yet a persistent cross-turn identity contract.
- The topology's existing general evidence projection carries these pulses into the shared association population. They do not yet have dedicated learned concept assemblies or independent neural readouts.

## Live dialogue integration

The Android ViewModel now invokes `ANS100WorkspaceConceptInputBridge` during `runHybridDialoguePass`, after the ordinary typed-evidence batch is created and before `beginHybridTurn`. The bridge merges concept pulses into that same timestamped batch; the existing runtime validates the evidence trace and concept trace together.

Only user-approved workspace records whose category is exactly `concept` or `понятие` (case-insensitive) are eligible. Their content is treated as one explicit canonical label, limited to 96 characters. Ordinary notes, unapproved entries, multiline content, and oversized labels are excluded. Matching remains exact token/adjacent-bigram grounding; no synonyms or inferred meanings are added. Messages longer than the lexical encoder's 2,048-character limit keep the evidence-only path rather than being silently truncated.

The network telemetry reports eligible approved concepts, active concept inputs, and skipped approved memory entries. A zero activation count is valid and leaves the original evidence batch unchanged. This is production-path wiring of a bounded experimental adapter, not proof that the network understands the concept or reasons independently.

## Why this stage matters
This creates an auditable bridge from raw user-text observations to concept identities. Later stages can associate concept activations with learned assemblies in the sparse neural runtime, propose relation hypotheses, and evaluate them against evidence. The current implementation is exact lexical grounding only; it is not autonomous concept recognition.

## Next gates
1. Learn concept assemblies from verified experience, not from labels alone.
2. Map concept/relation activations to dedicated neural populations without colliding with persisted topology IDs.
3. Have network activity produce hypothesis candidates and retain premise/evidence provenance.
4. Evaluate ambiguous, paraphrased, multilingual, and held-out concepts separately.
5. Prove transfer on unseen concept IDs and measure false activations, CPU, RAM, and latency before broad deployment.

## Explicit associative relation memory (workspace bridge)

Approved workspace entries in category `relation` or `связь` are parsed only in the strict three-field form:

```text
Собака | IS_A | Млекопитающее
Млекопитающее | IS_A | Животное
```

The relation type must exactly match an `ANS100ConceptMemory.RelationType` enum name. Both endpoints must resolve to exactly one approved concept record (category `concept` or `понятие`); missing and ambiguous labels are rejected. The relation's source and evidence reference remain attached to the stored relation. No relation is extracted from ordinary prose, and no relation is accepted from model-generated or implicit behavior.

During a dialogue turn, only hypotheses whose starting concept is lexically activated by that turn are considered. The current bounded composition rule chains two `IS_A` relations or two `PART_OF` relations; causal and other relation types are deliberately not transitively chained. Each candidate retains both premise relation IDs and their evidence references. The verifier reports `UNRESOLVED` unless a separate trusted direct observation supports or refutes the proposed relation. A chain of two stored relations is therefore a hypothesis, not silently promoted to a fact.

The live route currently loads the explicit relation graph and computes auditable symbolic candidates alongside the hybrid network pass. Relation edges and hypothesis candidates are not yet independent neural outputs; the next stage must move candidate generation into network activity and measure it separately. This stage does not claim semantic understanding or autonomous reasoning.


## Hypothesis handoff boundary (V1)

The live Android route passes at most 12 bounded concept-memory hypothesis reports into the ANS-100-to-Qwen handoff. Each report includes concept labels, typed relation, verification status, and premise evidence references. The report is generated by the concept-memory symbolic graph, not by an independent neural readout. The handoff marks these candidates as hypotheses; an `UNRESOLVED` chain must not be verbalized as a fact, and two premises are not independent confirmation. This improves traceability and prevents the language model from silently upgrading symbolic candidates into established knowledge. It does not yet make hypotheses part of the network's own candidate competition.
