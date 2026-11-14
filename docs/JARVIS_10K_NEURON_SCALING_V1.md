# JARVIS 10,000-Neuron Scaling V1

**Status:** the Python reference network has source-level topology ceilings for 10,000 neurons. The scale benchmark and boundary tests are authored but **NOT RUN**. This is not a verified 10,000-neuron device configuration.

## 1. Target and staged acceptance

The requested long-term network capacity is 10,000 logical neurons. Scale acceptance remains staged:

1. 1 neuron
2. 10 neurons
3. 100 neurons
4. 1,000 neurons
5. 10,000 neurons

A larger stage is not promoted to a default until the preceding stages have passed correctness, stability and resource checks on the intended environment. The existing V5 full resource profile already creates 10,000 Oracle-shaped reference items; that measurement is not a multi-neuron network test.

## 2. Python reference topology limits

`tools/neuron_runtime/network.py` now enforces:

| Resource | Hard source-level ceiling |
|---|---:|
| Logical neurons | 10,000 |
| Directed connections | 100,000 |
| Total synapses | 100,000 |
| Pending events | Existing configurable bounded queue |
| Events per run | Existing configurable bounded run budget |

The network uses explicit directed `NetworkConnection` records and does not allocate a dense N×N adjacency matrix. A sparse 10,000-node chain has 9,999 directed connections. The limits are safety ceilings, not performance targets or proof that every allowed topology is suitable for a phone.

The constructor still deep-copies and retains all neuron runtimes in process memory. It processes timestamp batches transactionally in memory and copies only the affected nodes for each batch. It does not lazily page neurons from storage.

## 3. Reproducible scaling benchmark

Run from the repository root:

```bash
python3 -m tools.neuron_runtime.network_scaling_benchmark --profile smoke
python3 -m tools.neuron_runtime.network_scaling_benchmark --profile pilot
python3 -m tools.neuron_runtime.network_scaling_benchmark --profile full
```

- `smoke`: 1 and 10 neurons.
- `pilot`: 1, 10, 100 and 1,000 neurons.
- `full`: 1, 10, 100, 1,000 and 10,000 neurons.

Each sample constructs a deterministic sparse chain, injects one event, and requires the spike to propagate through every node within the configured run budget. It records construction time, propagation time, total time, and tracemalloc current/peak bytes. The full profile is intentionally separate from the normal unit-test suite because it is a larger resource experiment.

The boundary tests are in `tools/neuron_runtime/test_network_limits.py`. They cover the exact 10,000-neuron ceiling and rejection of excessive neuron, connection and synapse counts.

## 4. What this does not yet provide

- **No Android 10,000-neuron runtime:** the native Kotlin signal pass remains two neurons. The Python network coordinator is available to desktop/Termux-side code only; the optional Termux bridge does not make the native Android app depend on Termux.
- **No true JNVM paging:** `JPVRNetworkCheckpointStore` writes a complete network snapshot as a BLOB plus CHECKPOINT commit. It does not evict inactive neuron state from RAM, fault it in on demand, or provide an LRU working set. Flash storage is persistence, not active RAM.
- **No packed representation:** the Python object graph remains the reference implementation. No compact array/struct-of-arrays candidate or memory reduction is claimed.
- **No production scheduler/NCC:** the coordinator is single-threaded, in-memory, and has no concurrent producer protocol.
- **No demonstrated intelligence:** the benchmark checks deterministic signal propagation only. Dialogue learning, semantic understanding and STDP/R-STDP training from ordinary conversation remain disabled.

## 5. Required next gates

1. Run smoke and pilot on a PC, inspect all correctness and resource results, and fix regressions.
2. Run the 10,000-node full profile only after smaller profiles are acceptable; record the machine, Python version, memory and timing.
3. Design packed neuron/synapse state and a bounded JNVM working set based on those measurements; do not claim disk-backed state is equivalent to RAM.
4. Implement the same versioned numerical contract in native Kotlin, then add Python/Kotlin golden-vector parity tests.
5. Measure Android RAM, CPU, latency, battery and thermal behavior before enabling a larger network in the phone app.

## 6. Verification status

The code and documentation were reviewed through GitHub source readback. The new tests and benchmark are **AUTHORED, NOT RUN**. No Python test, benchmark, import/syntax check, Android/PC runtime, Gradle build, APK/AAB, CI or device measurement has been performed for this increment.
