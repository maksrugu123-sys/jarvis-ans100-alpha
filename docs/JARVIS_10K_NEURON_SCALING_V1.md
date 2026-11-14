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

- **No Android 10,000-neuron runtime:** the native Kotlin conversation signal pass has ten logical neurons. The Python network coordinator is available to desktop/Termux-side code only; the optional Termux bridge does not make the native Android app depend on Termux.
- **Partial JNVM working-set prototype:** NeuronWorkingSet now provides per-neuron compact F64 residency, LRU eviction and JPVR write-back through sharded COW indexes. It is not integrated into NetworkRuntime, and per-neuron commits are not atomic across one network timestamp. The existing whole-network checkpoint remains a separate complete snapshot. Flash storage is persistence, not active RAM.
- **Compact resident representation is partial:** the separate NeuronWorkingSet packs one neuron's mutable state as lossless F64 and uses bounded LRU residency with JPVR write-back. It is not connected to NetworkRuntime, does not provide a network-wide timestamp transaction, and has no measured memory reduction. The Python NetworkRuntime still retains its full object graph.
- **No production scheduler/NCC:** the coordinator is single-threaded, in-memory, and has no concurrent producer protocol.
- **No demonstrated intelligence:** the benchmark checks deterministic signal propagation only. Dialogue learning, semantic understanding and STDP/R-STDP training from ordinary conversation remain disabled.

## 5. Required next gates

1. Run smoke and pilot on a PC, inspect all correctness and resource results, and fix regressions.
2. Run the 10,000-node full profile only after smaller profiles are acceptable; record the machine, Python version, memory and timing.
3. Implement the single-container group-commit design in docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md; do not combine per-neuron commits across the 128-shard prototype.
4. The ten-neuron Kotlin contract and shared Python/Kotlin golden-vector tests are now authored; execute them only after the user separately authorizes test runs, then add device resource measurements.
5. Measure Android RAM, CPU, latency, battery and thermal behavior before enabling a larger network in the phone app.

## 6. Network-level persistence design

The required next architecture is specified in docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md: one JPVR container per network, neuron and scheduler indexes, and one COMMIT covering every changed neuron plus the pending-event queue and network clock for a timestamp. The current 128-shard per-neuron working set cannot provide this cross-neuron atomicity and must remain an isolated persistence prototype until the group transaction store exists.

## 7. Verification status

The topology-limit tests and sparse-chain benchmark from the previous increment remain **AUTHORED, NOT RUN**. The compact-codec and working-set tests are also **AUTHORED, NOT RUN**. The working-set prototype is source-level only and is not connected to the network scheduler. The uncertain-commit mutation guard has been added at source level; its focused regression test is AUTHORED, NOT RUN. No Python test, benchmark, import/syntax check, Android/PC runtime, Gradle build, APK/AAB, CI or device measurement has been performed for this increment.


## 8. Conversation signal parity and Android local memory update

Android's standalone dialogue preprocessor is now ten logical neurons, not two. A shared TSV fixture under app/src/test/resources/ans100/conversation-v2.tsv is consumed by both Kotlin and Python tests to compare the same input features, event counts, output-gate state, ALIF adaptation/threshold and model time. These tests are AUTHORED, NOT RUN; parity must not be described as verified yet.

Android also has a bounded app-private approved-memory store that works without Termux. It is separate from the 10,000-node Python capacity ceiling and does not provide Android 10,000-neuron execution or native JPVR paging.
