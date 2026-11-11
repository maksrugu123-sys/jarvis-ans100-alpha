# JARVIS Network Runtime V1 — bounded multi-neuron event loop

**Status:** Python reference coordinator implemented; 15 focused tests authored, not yet run against the repository's real Numerical Oracle. Not production NCC, durable, concurrent, or device-verified.

## Scope and contracts

`NetworkRuntime` composes existing `NeuronRuntime` instances. It does not add or alter Adaptive LIF-DT, STDP, or R-STDP equations.

- A `NetworkConnection` is a directed route from one source neuron's output spike to exactly one incoming synapse on a target neuron.
- The authoritative synaptic weight remains in the target neuron's `NeuronRuntime`; a connection stores only route metadata, delay, normalized strength, and attention.
- Propagation delay must be a positive integer number of model nanoseconds. Zero-delay edges are rejected to prevent same-timestamp recursive propagation.
- External inputs are inserted with `schedule_input`. All events at one timestamp are processed as complete batches; each affected neuron receives at most one `process_batch` call for that timestamp.
- An output spike schedules one event per outgoing connection at `spike_time + delay_ns`. The queue is deterministic by timestamp and stable event ID.
- The pending queue and each `run()` call have explicit limits. Complete same-timestamp batches are never split; remaining work stays pending when the run budget is reached.
- Before a timestamp batch is committed, the coordinator reserves worst-case outgoing fan-out capacity. Backpressure defers the batch without changing neuron state.
- Candidate neuron coordinators are copied and all affected nodes are evaluated before the timestamp is committed in memory. A failed candidate leaves its events pending and commits no neuron state for that timestamp.
- R-STDP nodes receive the caller's `learning_context_id`; reward baseline remains external and can be applied through `NetworkRuntime.apply_reward`.

## Ownership and limitations

The constructor copies supplied `NeuronRuntime` instances. The network then owns its coordinator state; callers inspect neuron and synapse snapshots through read-only/copy-returning accessors.

This is a single-threaded Python reference event loop, not a production scheduler or Neural Core Controller. It has no durable queue, CQE authorization/idempotency, cross-timestamp transaction, crash recovery, persistence, concurrent producers, topology learning, inhibitory synapse type/iSTDP, device integration, or resource measurements. External event IDs are checked for uniqueness while pending, not retained forever as durable idempotency keys. Cyclic topologies are allowed only with positive delays and bounded by queue/run budgets; they are not evidence of stable recurrent dynamics.

## Verification

`tools/neuron_runtime/test_network.py` now contains 15 contract tests for topology validation, delayed propagation across multiple neurons, deterministic same-timestamp batching, bounded queue/run behavior, fan-out backpressure, runtime ownership isolation, bounded recurrent routing, and scheduler time initialization from per-synapse trace/reward clocks.

The original 14 contract tests had a historical isolated-harness result of 14/14 using an API-compatible Runtime/Oracle stand-in; this was not an integration run against the repository's actual Numerical Oracle. The fifteenth test (restored per-synapse clock boundary) has been added and is AUTHORED, NOT RUN. The user's earlier Termux full-suite result of 216/216 predates this module and does not cover it.

Run from the repository root:

    python3 -m unittest tools.neuron_runtime.test_network -v
    python3 -m unittest discover -s tools -t . -v

No build, CI, APK/AAB, Android resource measurement, or production integration is part of this increment.
