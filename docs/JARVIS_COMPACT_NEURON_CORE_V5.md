# JARVIS Compact Adaptive Neuron Core V5
## Proposal: maximum useful capability per byte and per operation

**Status:** PROPOSED — architecture research only; not implemented or verified  
**Date:** 7 November 2026  
**Scope:** first Adaptive LIF-DT neuron, synapse-state representation, event path, and future multi-neuron memory organization  
**Compatibility rule:** preserve the current Numerical Oracle, V4/V4.1/V4.2 protocols, canonical STARTER values, and simultaneous-event semantics until a separate experiment authorizes a behavioral change.

## 1. Executive decision

The first major V5 direction is to make the *representation and execution path* compact before making the learning algorithm more complicated.

The current repository has a carefully specified Adaptive LIF-DT model and separate neuron/synapse records. Its Python Numerical Oracle is intentionally a correctness reference, not a production runtime. The Oracle uses Python dataclasses and a generic SynapseState containing fields for both STDP and R-STDP. That is convenient for research correctness, but it is not a justified memory layout for thousands of runtime neurons and synapses.

V5 therefore proposes a **Compact Event-State Core (CESC)** with five coordinated ideas:

1. Immutable parameter sets are shared; a neuron does not copy its model constants.
2. Hot mutable numerical state is separated from cold identity, schema, topology, diagnostics and history metadata.
3. State fields are selected by the active plasticity rule: NONE, STDP or R-STDP. Non-plastic synapses do not carry learning traces.
4. Redundant values are derived where an invariant proves that this is safe; timestamps and context handles use compact fixed-width representations.
5. Events use compact numeric handles and batches instead of allocating repeated string identifiers and copying connection metadata into every signal.

The initial V5 candidate keeps the exact existing float64 equations. The first optimization target is storage and object overhead, not quantization or removal of useful learning mechanisms.

This is a proposal, not a claim that the current Python implementation is already memory-heavy on the target device or that the proposed byte layouts have been measured.

## 2. Evidence-based diagnosis of the current design

### 2.1 What already helps

The existing neuron already uses analytical event-driven decay. An inactive neuron does not need a periodic update every millisecond: its potential and adaptation can be calculated from the last confirmed update time when a new event arrives. This remains a foundational V5 property.

The architecture also already separates immutable parameter-set versions from per-neuron state at the logical level, and separates individual synapse records from neuron records. V5 should make those separations efficient in the actual hot path rather than inventing another model.

### 2.2 Where overhead can appear

The reference Oracle currently represents:
- NeuronState as a Python dataclass with potential, adaptation, timestamps, spike sequence and optional last-spike time;
- SynapseState as a generic Python dataclass with weight, pre/post traces, two timestamps, eligibility, an optional string context ID and an optional context-start timestamp;
- InputSignal as a Python dataclass carrying a source string, signed weight, strength and attention.

These are clear reference interfaces. They are not evidence that a production implementation should allocate one Python object and repeated strings for every signal or synapse.

The larger risk is **per-object duplication**: repeated parameter values, schema/identity metadata, strings and optional learning fields can dominate the actual numerical state when the network becomes large.

### 2.3 What is not yet known

No target-device measurement has established:
- actual bytes per active neuron or synapse;
- Python object overhead versus a packed alternative;
- steady-state and peak RSS/PSS;
- serialized bytes per record;
- event latency or throughput;
- whether a packed representation improves the total runtime, rather than only its theoretical payload size.

These measurements must be produced before claiming a compactness gain.

## 3. V5 architecture: hot state, shared parameters and cold metadata

### 3.1 Shared immutable Parameter Bank

Store each immutable parameter set once and let many neurons or synapses refer to it by a compact in-memory handle.

A parameter-set entry contains:
- model/rule identifier and semantic version;
- numeric coefficients and bounds;
- timestamp units and numerical policy;
- a stable persistent parameter-set ID/version.

Neuron and synapse hot-state records must not copy tau values, learning rates, weight bounds, thresholds or other immutable coefficients.

Different neurons may reference different parameter sets. Sharing is allowed only when the parameter-set identity and version match exactly. A parameter update creates a new immutable version and uses an explicit migration; it does not silently mutate the meaning of already persisted state.

### 3.2 Compact neuron hot-state payload

Proposed F64 payload, little-endian:

| Field | Type | Bytes |
|---|---:|---:|
| membrane potential u | F64 | 8 |
| adaptation a | F64 | 8 |
| last_update_model_time_ns | U64 | 8 |
| refractory_until_model_time_ns | U64 | 8 |
| spike_sequence | U64 | 8 |
| **Total hot numerical payload** | | **40 bytes** |

The current logical NeuronState also carries last_spike_model_time_ns. Under the existing invariant, that value is derivable from the immutable parameter version: when spike_sequence > 0, last_spike_time = refractory_until_model_time_ns - refractory_ns. When spike_sequence is zero, no spike has occurred. V5 proposes not storing the same timestamp twice in the hot payload.

This derivation is valid only while the recorded refractory end was created by the documented spike transition and the parameter-set version is known. If that invariant cannot be established during recovery or migration, the state must be rejected or migrated explicitly; it must not be guessed.

The 40-byte number is the proposed numerical payload only. It excludes the persistent object envelope, IDs, commit/version metadata, integrity fields, page alignment, allocator overhead and event history. It must never be advertised as the full byte cost of a neuron.

Derived values such as effective threshold, attention gain, refractory-active flag and transient input sums are not authoritative state. Compute them when needed or keep them only in a discardable cache.

### 3.3 Rule-specific synapse state

V5 should stop allocating a universal learning-state shape for every synapse. The immutable rule ID selects one versioned state layout.

| Rule | Proposed hot payload | Fields | Bytes |
|---|---|---|---:|
| NONE | F64 | weight magnitude | 8 |
| EXCITATORY_PAIR_EXP_STDP_V1 | F64,F64,F64,U64 | weight, pre trace, post trace, last update time | 32 |
| EXCITATORY_PAIR_EXP_R_STDP_V1 | F64,F64,F64,U64,F64,U64,U32 | weight, pre trace, post trace, shared update time, eligibility, context start time, context token | 52 |

The R-STDP layout proposes one shared timestamp for pair traces and eligibility. The existing update_rstdp and apply_reward paths both advance these clocks to the same model time. V5 must add explicit invariant tests before relying on this property. A state with mismatched old timestamps cannot be silently packed: it must be rejected, replayed or migrated with a documented rule.

The current string learning_context_id is represented in hot memory by a U32 context token scoped to a runtime epoch. Token zero means no active context. Tokens must not be reused while any state refers to them; exhaustion or a stale token is a hard error. Durable records retain a stable context mapping sufficient for recovery. This changes representation, not the reward rule or eligibility mathematics.

The 52-byte R-STDP figure excludes alignment and any durable identity envelope. An aligned in-memory stride may be 56 bytes. Actual memory measurements decide the deployed stride.

A synapse with plasticity NONE must not allocate pre/post traces or eligibility. A rule transition is a versioned migration, never an in-place reinterpretation of old bytes.

### 3.4 Hot/cold split

**Hot path**
- compact neuron dynamics;
- active synapse values and only the traces required by that rule;
- numeric indices/handles;
- one shared parameter-set handle;
- timestamps and bounded counters necessary for the next calculation.

**Cold sidecar / durable metadata**
- stable UUIDs and external endpoint names;
- schema/model/parameter-set versions;
- lifecycle and commit versions;
- topology metadata and indexes;
- diagnostic histories, example traces and telemetry;
- local-memory references and long-term experience;
- audit/recovery data.

The split does not weaken the NCC single-writer commit rule. Durable authority, idempotency, commit confirmation and JPVR recovery remain above the packed numerical payload. No dirty state may be evicted before the required durable confirmation.

### 3.5 Compact event representation

The current InputSignal reference object includes source_id and signed_weight on every signal. A production event should instead reference a stable synapse/edge handle and carry only the event-specific information.

Proposed event batch:
- one timestamp and runtime epoch per batch;
- a bounded event count;
- compact synapse handles (U32 while the validated network limit permits it);
- event strength;
- optional attention only when it differs from the default;
- a stable ordering key or index mapping that preserves the Oracle's deterministic source order.

The signed weight and polarity are resolved from the correct versioned synapse snapshot. A queued event must never accidentally read a newer weight than the one belonging to its processing/commit order. If a snapshot cannot be pinned or identified, the required version must be included in the event reference.

External strings are resolved at the system boundary, not allocated repeatedly in the per-spike hot loop. Index widths are explicit and checked; U32 must fail safely before overflow rather than wrap.

### 3.6 Topology layout

For a tiny network, a compact fixed record or small edge list may be cheaper than a general graph index. For larger populations, evaluate compressed sparse row (CSR) adjacency or another contiguous sparse layout.

Do not assume CSR is best for one neuron. Benchmark at 1, 10, 100, 1,000 and 10,000 neurons/synapses where the device budget allows. The chosen layout may differ by population size, but the observable event order and synapse ownership contract must remain stable.

## 4. Preserve behaviour before reducing precision

### 4.1 F64 compact candidate first

The first compact implementation uses the same F64 values, integer timestamps, exact analytical decay, canonical simultaneous-event batching, stable ordering and math.fsum aggregation as the Numerical Oracle.

Do not change:
- Adaptive LIF-DT equations or STARTER values;
- STDP/R-STDP equations, rates, signs or reward baseline;
- equal-timestamp batch semantics;
- refractory input retention;
- event ordering;
- task labels, datasets or decision deadlines.

This isolates representation improvements from learning-rule changes. V4.2 remains the required diagnostic before any future change to causal-credit or event-order semantics.

### 4.2 F32 as a separate candidate

Only after the F64 compact representation is equivalent should a float32 candidate be considered.

The existing Oracle tolerance (absolute + relative 1e-5) is a preliminary numeric comparison, not a guarantee of identical neuron behaviour. The candidate must additionally preserve:
- every spike/no-spike decision;
- exact spike sequence and model timestamps;
- eligibility and reward-update signs;
- weight bounds and context isolation;
- held-out task metrics within a pre-registered statistical tolerance.

A promising research hypothesis is **threshold-margin precision escalation**: run ordinary events in F32, but recompute a decision in F64 whenever the estimated numerical-error envelope overlaps the distance to threshold. Formally, if m = |u_candidate - theta_candidate| and the validated bound epsilon covers errors in both quantities, only m > epsilon certifies that the decision is numerically robust; m <= epsilon triggers the reference-precision path. The error bound must include decay, summation, clipping and adaptation. This is a hypothesis for an isolated experiment, not a current implementation or a claim of guaranteed exactness.

INT8/INT4 and aggressive weight quantization are deferred. They may be considered only after the simpler exact-layout and F32 experiments establish a baseline.

## 5. Optional shared slow context for multi-neuron temporal memory

A separate V5 research track will evaluate a **shared low-dimensional slow-context state** for a group/layer, rather than giving every neuron a duplicate long history.

A recent 2026 Nature Machine Intelligence paper introduced a dual-memory-pathway SNN: fast sparse spiking plus a compact shared slow state. It reports competitive long-sequence results with 40–60% fewer parameters than selected comparable SNNs; its experiments use task-specific architectures and hardware co-design, so the reported reduction is not a predicted JARVIS result. The paper describes the slow state as a low-dimensional vector, typically about 5–10% of hidden width, that summarizes recent activity and modulates fast spiking dynamics.

For JARVIS, investigate this only after a multi-neuron baseline exists. At N=1, a shared slow vector cannot create a useful d much smaller than N and may simply add overhead. At larger N, test whether a small group state can replace per-neuron temporal buffers or dense recurrence while preserving held-out sequence performance.

The proposal must define:
- which group owns the slow state;
- how spikes are compressed into its input;
- stable state-space dynamics and bounds;
- how the context modulates each neuron;
- update frequency and event-driven scheduling;
- memory/compute cost as a function of N and d;
- ablations against no slow path and per-neuron memory;
- failure/reset/recovery semantics.

A separate 2026 ICLR paper reports a training-memory pipeline using layer-wise gradient checkpointing and lossless spike compression, with up to 8x memory-efficiency gains, up to 20% speed reduction and no accuracy loss in its tested settings. It addresses deep SNN training through BPTT. JARVIS currently uses online STDP/R-STDP reference updates and has no BPTT training tape, so this method is a future option only if that training regime is added; it is not part of the first-neuron hot path.

## 6. Measurement protocol

The baseline must be captured before an implementation is accepted.

For every population size and rule mix, compare:
1. current reference-object representation;
2. F64 compact record representation;
3. future F32 candidate, only after F64 equivalence passes.

Record:
- logical payload bytes per neuron and per synapse;
- total serialized bytes including envelope, indexes and integrity data;
- Python object/allocator overhead where applicable;
- steady-state RSS and peak RSS (PSS too when the Android environment exposes it);
- total and peak RAM for 1/10/100/1,000/10,000 elements, subject to a safe phone profile;
- median/p95/p99 event latency and events per second;
- CPU time and allocation counts where available;
- bytes read/written, checkpoint cost and recovery time when persistence is implemented;
- task accuracy, balanced accuracy, macro-F1, confusion counts and learning stability.

Never compare only the compact payload with the full old object graph, or only the model file with total runtime memory. Report like-for-like totals and state exactly what is included.

Use small deterministic smoke, phone-friendly pilot and separately declared full profiles. Estimate resource cost before running a large profile on Android Termux. Do not trigger builds or CI.

## 7. Acceptance gates

### Gate A — layout correctness
- all valid records round-trip without value changes;
- malformed, truncated, overflowed, unknown-version and corrupt records are rejected;
- omitted/derived last-spike reconstruction matches the reference invariant;
- NONE/STDP/R-STDP layouts cannot be confused;
- R-STDP shared-clock and context-token invariants are tested;
- persistent metadata, parameter-set versions and recovery identity are preserved.

### Gate B — F64 behavioural equivalence
- same inputs and ordering produce exactly the same spike/no-spike decisions, spike sequence, timestamps and learning-update signs;
- numerical state and weights match the reference exactly where operation ordering is unchanged, otherwise every deviation is explained and bounded;
- V4/V4.2 historical protocols remain unchanged;
- no change to canonical STARTER.

### Gate C — measured resource benefit
- measured total memory and/or event-path allocation cost is lower at a stated population size;
- the result includes metadata and indexes, not payload-only estimates;
- latency does not regress beyond a pre-registered bound;
- one-neuron and larger-network results are reported separately.

### Gate D — F32 or algorithmic optimization
- separate pre-registration, seeds and held-out data;
- spike decisions and learning signs remain correct under the declared contract;
- task-level quality is statistically non-inferior within a tolerance chosen before the run;
- any trade-off is disclosed; otherwise reject the candidate.

A compact representation that saves bytes but changes the learning trajectory is not accepted as a lossless V5 replacement. A smaller model that loses task quality is not accepted merely because its parameter count is lower.

## 8. Staged implementation plan

1. **V5 specification (this document):** architecture and acceptance gates; no runtime changes.
2. **V4.2 diagnostic:** user-run focused tests and smoke/pilot; record actual per-pulse evidence before modifying learning semantics.
3. **Resource baseline harness:** measure current reference-object and serialized-state costs on Android Termux; do not guess actual RAM.
4. **F64 compact-state prototype:** versioned encode/decode and rule-specific records, preserving Oracle equations.
5. **Differential verification:** golden vectors, deterministic generated event streams, boundaries, restart/context cases and exact spike-train comparison.
6. **Device profiling:** compare total memory, allocations and latency under safe population profiles.
7. **Precision research:** only then evaluate F32 and threshold-margin escalation.
8. **Network-scale temporal memory:** only after the compact single-neuron core is verified; test shared slow context against matched baselines.
9. **Scale gate:** move 1 → 10 → 100 → 1,000 → 10,000 only when correctness, quality and resource evidence support the next step.

## 9. Research references

1. Pengfei Sun et al., “Algorithm–hardware co-design of neuromorphic networks with dual memory pathways,” Nature Machine Intelligence 8, 901–912 (16 June 2026), DOI: 10.1038/s42256-026-01255-3. Full text: https://pmc.ncbi.nlm.nih.gov/articles/PMC13286998/
2. Yifan Huang, Wei Fang, Zecheng Hao, Zhengyu Ma, Yonghong Tian, “Towards Lossless Memory-efficient Training of Spiking Neural Networks via Gradient Checkpointing and Spike Compression,” ICLR 2026. Official proceedings: https://proceedings.iclr.cc/paper_files/paper/2026/hash/6d44b1617ca7001a5ba7ea4f28039d41-Abstract-Conference.html
3. Xi Cheng et al., “An FPGA-Based Event-Driven SNN Accelerator for DVS Applications With Structured Sparsity and Early-Stop,” IEEE Transactions on Circuits and Systems I (July 2025), DOI: 10.1109/TCSI.2025.3560666. This is hardware/task-specific evidence for structured sparsity, not a directly transferable JARVIS result.

## 10. Current status and next decision

**PROPOSED / NOT IMPLEMENTED / NOT MEASURED.**

This specification does not change the Oracle, neuron equations, plasticity rules, STARTER, JPVR format or production architecture. No tests or benchmarks are claimed.

The next technical implementation should be a small resource-baseline and F64 packed-state prototype, preceded by the V4.2 smoke/pilot evidence supplied by the user. Do not implement new temporal-memory or quantization mechanisms in the same change; first isolate the representation gain and prove behavioural equivalence.
