# ANS-100 activity-driven structural plasticity V1

## Status

**Status: PROTOTYPE AUTHORED / TESTS NOT RUN / NOT IN LIVE DIALOGUE.**

This stage adds a bounded mechanism that can propose new concept-to-association synapses from repeated network activity. It does not use a predefined concept-pair catalogue and does not ask Qwen to generate associations.

## Why it is needed

The existing concept projection gives each concept input neuron exactly three deterministic outgoing edges into the 1,000-neuron general association slice. Two concepts with no shared target can never become a co-activation candidate through weight updates alone.

Structural plasticity addresses this by creating a new shared target after repeated co-activation. It changes the graph structure rather than only adjusting existing weights.

## Mechanism

ANS100ActivityDrivenStructuralPlasticity receives completed-turn observations containing:
- stable concept IDs and their persistent input-neuron IDs;
- the labels and provenance references attached to the current activation;
- the existing outgoing association targets for each active concept neuron;
- current incoming-edge counts for association targets.

A pair is considered only when both concepts were active in the same completed turn and currently have no shared association target.

The pair must co-activate in **three distinct turn IDs** inside a rolling **16-observation window**. Replaying the same turn ID is ignored. Repeated calls for one turn therefore cannot manufacture eligibility.

After the threshold is reached:
1. The allocator excludes every target already used by either source concept.
2. It scans the 1,000-neuron general association population using a deterministic pair-hash starting offset. The hash only breaks ties in the search order; it does not encode a fixed target.
3. A least-loaded target wins. Targets at the incoming-edge cap are ineligible.
4. Two new synapses are proposed: each concept input neuron connects to the same newly selected association target.
5. Initial weight is deliberately conservative (0.25); no relation truth or semantic confidence is assigned.

The proposal contains both concept identities, current labels, provenance references, supporting distinct-turn count, target neuron and the exact two AndroidNeuronConnection records. It can be audited before any caller applies it.

## Bounds

Default limits:
- 64 active concepts per turn (at most 2,016 unordered pairs considered);
- 2,048 tracked pending pair traces;
- three distinct turns required within 16 observations;
- at most 256 grown pairs in one learner instance;
- at most four grown outgoing edges per concept;
- at most 12 incoming edges per association target;
- at most 256 remembered turn IDs for replay protection;
- hard ceilings: 4,096 tracked pairs, 512 grown pairs, eight grown edges per concept, 16 incoming edges per target;
- new synapse weight 0.25, allowed configuration range 0.05..0.50.

The learner prunes the oldest pending traces when its pair table reaches its configured bound. It does not prune or rewrite existing network connections.

## Important boundaries

- This is structural growth from repeated co-activation, not proof that the two concepts are semantically related.
- Repeated co-activation can be incidental (for example, concepts that often appear in the same topic). A future stage must add independent evidence/reward and false-association analysis.
- Eligibility and the set of already-grown pairs are currently in-memory state. They do **not** survive process restart yet.
- The class returns proposals; it does not insert them into SQLite or mutate the live topology.
- It is not wired into JarvisViewModel, the live decoder, or decision competition. The existing three-edge projection remains the only production path.
- It does not change Qwen's role. Qwen remains a language realizer, not an association generator.
- No live learned weights or newly proposed connections are used to choose an answer.

## Tests authored

ANS100ActivityDrivenStructuralPlasticityTest covers:
- no growth before three distinct turns;
- new shared target and two matching synapses after the threshold;
- duplicate turn replay;
- already-shared target suppression;
- exclusion of either source's existing targets;
- least-loaded target competition;
- saturated-target rejection;
- eligibility expiry outside the observation window;
- per-concept growth-degree limits;
- deterministic output;
- duplicate concept identity rejection.

Tests are **AUTHORED / NOT RUN**. No Android build, APK, CI, GitHub Actions workflow, or device inference was run.

## Next integration gate

Before production use:
1. Persist pair eligibility and grown-pair identity in SQLite with transactional idempotency.
2. Add a bounded atomic API to validate and insert exactly the two proposed concept-input synapses, including endpoint, degree, target-load and connection-ID checks.
3. Extend the decoder to recognize approved grown synapses without weakening validation of the original V4 projection.
4. Add restart/replay/migration tests and ensure schema migration preserves unrelated evidence weights and topology.
5. Replay proposals through the actual Android LIF event runtime.
6. Evaluate on human-verified pair-disjoint and concept-disjoint datasets against fixed, shuffled-label and random-growth controls.
7. Keep the promotion gate closed until held-out performance, false-association rate, CPU, RAM, and latency are measured.

No general understanding or autonomous reasoning claim is supported by this prototype.


## V1.1 — persistent synapse lifecycle API

The Android sparse-network SQLite store now has schema V6 and a
\`structural_synapse_registry\` for grown synapses. The network exposes a
separate structural-growth API, rather than routing these through unrestricted
generic \`connect()\`.

- \`addStructuralGrowthProposal\` inserts both directed edges and both registry
  records in one SQLite transaction. It validates the concept-input and
  association neuron ranges, the generated connection-ID namespace, source
  neuron existence, total connection capacity, per-target in-degree, and
  per-concept grown-edge degree. Replaying an already registered proposal is
  idempotent.
- \`adjustStructuralSynapseWeights\` changes only registered grown synapses,
  clamps weights to [0.05, 1.0], limits each update to ±0.10, and requires
  explicit-user or verified-external-outcome feedback. Both edges belonging
  to a proposal must be supplied together, so one side of an association
  cannot silently drift away from the other.
- \`removeWeakStructuralSynapses\` removes a proposal only when both of its
  synapses are supplied and both weights are at or below the caller's
  conservative threshold (maximum 0.20). It removes both edges together.
- \`structuralSynapseWeights\` reads weights only for registered grown edges.

The generic runtime's ordinary fixed topology and existing learned weights are
not rewritten by this API. The registry persists grown-edge ownership and
proposal identity across process restarts.

**Still not integrated:** the activity learner's in-memory observation traces
are not persisted yet; there is no production call site observing completed
neural turns and applying proposals; no evidence-verification workflow currently
calls the reward API; the decoder does not yet enumerate grown targets. The
new API therefore enables safe persistence and controlled mutation, but does
not yet make live ANS-100 behavior change automatically.


### Safety refinements in V1.1

- The SQLite store is schema V6. A registry associates every grown edge
  with its proposal and concept IDs; a separate feedback ledger records
  feedback ID, proposal ID, delta, source and timestamp.
- Weight adjustment requires a unique feedback ID. Duplicate feedback IDs
  are ignored transactionally, preventing replay from applying reinforcement
  twice.
- Reinforcement/penalty accepts only \`EXPLICIT_USER\` or
  \`VERIFIED_EXTERNAL_OUTCOME\`; model-generated and implicit behavior are
  rejected at the public network API.
- Both edges of a proposal must be supplied together for weight changes.
  Weak-edge removal also requires both edges and removes the pair only when
  both weights are below the threshold. This prevents half-associations.
- The initial insertion and subsequent pairwise mutations are SQLite
  transactions. New links are visible to the ordinary sparse runtime because
  it reads outgoing connections from the same connections table.

This still does not mean that a new association is automatically decoded as a
semantic fact. The co-activation decoder and production turn pipeline need
explicit support for registered grown proposals, and independent validation
must precede any influence on reasoning.
