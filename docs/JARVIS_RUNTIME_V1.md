# JARVIS Runtime V1 — first executable neuron coordinator

**Status:** implemented as a Python reference orchestration slice; not a production runtime, not JPVR-001 complete, and not device-verified.

## Scope

tools/neuron_runtime adds an in-memory coordinator for one Adaptive LIF-DT output neuron and one or more incoming excitatory synapses. It calls the existing tools.numerical_oracle.jarvis_oracle functions. It does not reimplement or change the neuron's or plasticity's numerical equations.

## Runtime contract

- Inputs sharing a model timestamp are submitted together with NeuronRuntime.process_batch(time_ns, inputs).
- Each input identifies a synapse, a unique event ID, normalized strength and optional attention.
- The neuron is updated once per timestamp by the Numerical Oracle.
- In STDP/R-STDP modes, every configured synapse receives the same postsynaptic spike count for that timestamp; its own presynaptic count is the number of input events routed through that synapse. Thus an output spike can update a synapse that had no input at that particular timestamp.
- R-STDP batches require one stable learning_context_id for the episode. Local eligibility accumulates without changing weight; a reward operation consumes the eligibility and may update the weight.
- baseline_before is supplied by the external Reward Baseline Manager. Runtime returns baseline_after but does not store or advance task baseline.
- A timestamp batch is calculated into candidate immutable states and only committed to this in-memory coordinator after all synapse updates succeed.

## Deliberate limitations

This one-neuron coordinator remains a Python reference around the Oracle, not an independent production neuron engine, language model or reasoning engine. Multi-neuron scheduling is provided separately by NetworkRuntime. The project now has JNVM/JPVR composite checkpointing and a candidate-state multi-synapse reward transaction, but it still has no CQE integration, NCC authorization/commit service, durable inbox/outbox/session protocol, concurrent scheduler, topology learning, inhibitory synapse type/iSTDP, Android UI or device performance measurements. The direct NeuronRuntime.apply_reward() method still changes one synapse; use process_reward_feedback() to coordinate a selected multi-synapse batch with baseline advancement and one composite JPVR publication.

## Verification

tools/neuron_runtime/test_runtime.py adds 15 contract tests for simultaneous input ordering, Oracle delegation, spike reporting, per-synapse pre/post routing, STDP updates, delayed R-STDP reward, caller-owned baseline, context validation, time ordering and atomic failure of an in-memory batch.

The authoring check ran Python syntax compilation and an isolated test harness with an API-compatible stand-in for the Oracle. This passed 15/15 in the harness, but is not a run against the repository real tools.numerical_oracle.jarvis_oracle. The 15 tests remain AUTHORED, NOT RUN against the real repository Oracle. Run these commands from the repository root in Termux:

    python3 -m unittest tools.neuron_runtime.test_runtime -v
    python3 -m unittest discover -s tools -t . -v

Do not describe this module as integrated into JARVIS or JPVR-001 until the focused and full repository suites pass against the real Oracle and the remaining state ownership/persistence interfaces are implemented.


## Reward Baseline Manager V1

`RewardBaselineManager` now provides the external baseline owner used by R-STDP orchestration. A caller prepares feedback against the previously committed task baseline, processes every relevant synapse, then commits the baseline only after terminal success or an approved no-op. The manager enforces per-task feedback sequence, exact replay within a bounded receipt window, pending reservation reuse and a caller-supplied clock epoch.

The manager remains a separately owned reference component. The direct `NeuronRuntime.apply_reward()` call still takes `baseline_before` from its caller and changes one synapse in memory. The new `process_reward_feedback()` coordinator clones both NetworkRuntime and manager, applies all target rewards, advances the candidate baseline only after terminal outcomes, and saves both through one JRCM commit. Use a dedicated JRCM container; do not mix it with standalone JNCP/JRCP checkpoint pointers.

Contract: `docs/JNVM_REWARD_BASELINE_CHECKPOINT_STORE_V1.md`. Tests: `tools/neuron_runtime/test_reward_baseline.py` (26) and `tools/neuron_runtime/test_reward_baseline_store.py` (15), **AUTHORED, NOT RUN**.


## Composite persistence boundary

`JPVRRuntimeCheckpointStore` publishes `NetworkRuntime` and `RewardBaselineManager` snapshots through one JRCM V1 CHECKPOINT descriptor and one COMMIT. Both component records must share one clock epoch and source transaction/generation. This prevents a crash from publishing a new network checkpoint while retaining an older baseline checkpoint (or vice versa). It does not itself coordinate feedback processing. The separate `process_reward_feedback()` API now provides candidate-state synapse updates plus baseline advancement and one JRCM publication. The store is a dedicated-container Python reference implementation, not production NCC.


## Atomic R-STDP feedback transaction V1

`process_reward_feedback()` combines a prepared per-task reward baseline with candidate synapse updates across one or more NetworkRuntime neurons. All targets are calculated on a detached network copy; an error in any target discards every candidate update. Only terminal Oracle results allow the baseline receipt to advance. A single JRCM save then publishes network and manager state together using a required expected runtime version.

The function returns new network and manager instances. It does not mutate its input references; callers replace both references only after success. Exact committed replay is reconciled against the active JPVR receipt and does not reapply weights. Contract: `docs/JNVM_ATOMIC_REWARD_FEEDBACK_TRANSACTION_V1.md`. Tests: `tools.neuron_runtime.test_feedback_transaction` (10), **AUTHORED, NOT RUN**.

Remaining limitations: caller-owned clock epoch and event draining; single writer; bounded manager replay history; no persisted target-set digest, CQE inbox/outbox or authorization/session provenance; no external side effects, NCC or Android durability evidence.
