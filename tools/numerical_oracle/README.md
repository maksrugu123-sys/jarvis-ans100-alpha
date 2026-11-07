# JARVIS Numerical Oracle (reference only)

This folder contains a deterministic, pure-Python **float64 reference oracle** for the first-neuron equations in architecture sections 3.16, 3.19 and 3.20.

It is deliberately separate from the future JARVIS runtime. Python was selected only because the repository did not yet contain a runtime language or source/test framework. The production language remains undecided. The oracle uses only the Python standard library and performs no storage, network, threading, scheduling or persistence operations.

## Run the checks

From the repository root:

\`\`\`sh
python3 -m unittest discover -s tools -t . -v
\`\`\`

This is a test command, not a build command. It does not compile or package JARVIS.

## Scope

- Adaptive LIF-DT: analytical potential/adaptation decay, bounded Attention gain, deterministic simultaneous input batches, threshold check, one spike per batch, refractory period and retention of input accumulated during refractoriness.
- Pair-based excitatory STDP: pre/post exponential traces, old-trace batch equation, bounded local update, hard weight bounds.
- Reward-modulated STDP: eligibility accumulation/decay, one active learning context, maximum context age, reward prediction error, soft weight bounds and task baseline update.
- Input validation: finite floats, bounded normalized inputs, monotonic unsigned 64-bit model time and late-event rejection.
- Float32 candidate comparison helpers: explicit IEEE-754 binary32 round-trip and absolute/relative error reports against reference vectors.

The implementation is a numerical reference, not a complete event-delivery or transaction system. CQE idempotency, durable feedback sequence ordering, duplicate prevention, commit, outbox, synapse polarity authorization, journaling and JPVR persistence remain outside this module. Inhibitory plasticity is not implemented; the first prototype keeps inhibitory synapses at \`NONE\`.

## Numerical interpretation

Python \`float\` is used as the float64 reference representation. Time is integer \`model_time_ns\`; no wall clock is read. The caller must group all signals with the same model timestamp before calling \`process_neuron_batch\`. The function sorts unique \`source_id\` values and sums contributions using \`math.fsum\` to make input order irrelevant.

The comparison helper uses the starter combined tolerance rule \`abs(error) <= abs_tol + rel_tol * abs(reference)\`. The initial absolute/relative tolerances are test controls, not a claim that float32 has passed. Spike/no-spike decisions and spike sequence IDs must be compared separately because a small numeric error near threshold can change discrete output.

The oracle does not prove model quality, biological fidelity, network stability, device performance or float32 suitability. It provides a fixed comparator against which a future runtime can be checked.

## Known contract clarification

An eligibility trace's maximum age is measured from the start of its learning context, not from its last decay update. The logical `SynapseRecord v1` already defines `eligibility_started_model_time_ns` alongside the context ID and current trace timestamp (architecture section 3.18.2). The Oracle uses this field; the future JPVR serializer must preserve it and its semantics rather than introducing a duplicate logical field. It is distinct from durable reward deduplication metadata. In the reference rule, starting, consuming or expiring an R-STDP context also clears pair traces so that pre/post activity from one episode cannot leak into another episode's eligibility.
