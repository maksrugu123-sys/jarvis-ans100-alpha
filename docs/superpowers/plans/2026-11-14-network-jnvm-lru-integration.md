# Network JNVM LRU Integration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace full in-memory neuron-state ownership in the Python JPVR network coordinator with a bounded lazy LRU working set without weakening atomic timestamp commits.

**Architecture:** Keep one JPVR container per network and add a pinned read-view plus lazy neuron mapping backed by the network neuron INDEX. A candidate scheduler/cache processes one complete timestamp; all touched neuron records and scheduler state publish through the existing single group COMMIT before the new cache becomes authoritative.

**Tech Stack:** Python 3.11+ repository runtime, `NetworkRuntime`, `NetworkJNVMCoordinator`, `JPVRNetworkJNVMStore`, JPCS F64 codec, JPVR typed transactions, unittest.

**Spec:** `docs/superpowers/specs/2026-11-14-network-jnvm-lru-integration-design.md`

## Global Constraints

- Work only on `experiment/jarvis-integrated-prototype-v1`; never update `main`.
- One JPVR container per network remains the authoritative multi-neuron timestamp transaction boundary.
- Do not connect the 128-shard per-neuron `NeuronWorkingSet` to the network scheduler.
- Preserve deterministic same-timestamp batching, queue/run budgets, version CAS, clock epoch checks, and fail-closed uncertain-commit reconciliation.
- Keep Numerical Oracle equations and durable neuron record format unchanged.
- Default Python resident LRU capacity is 64; supported configuration is 1..10,000.
- The timestamp candidate staging set is separate from the resident LRU and bounded by the complete timestamp event budget.
- Do not run Gradle/build/APK/AAB/CI/Actions commands. Python tests require explicit authorization under the current project handoff.
- Label any unexecuted tests AUTHORED, NOT RUN; never claim runtime or measured RAM benefits from static source review.

## Review Focus

- A timestamp touching more unique neurons than LRU capacity must retain every candidate until one group COMMIT without exceeding the documented staging bound.
- A read-view must never mix neuron records from different JPVR generations/commits during one candidate timestamp.
- Invalid or stale external events must fail without dropping or partially publishing the prior pending queue.
- LRU eviction during candidate preparation must never discard a state that is needed for the pending group transaction.
- An uncertain Superblock publication must not allow cache publication or mutation until exact reopen/reconciliation.

---

### Task 1: Pinned network read-view

**Files:**
- Modify: `tools/neuron_runtime/network_jnvm_store.py`
- Test: `tools/neuron_runtime/test_network_jnvm_store.py`

**Interfaces:**
- Produces: `JPVRNetworkReadView`, `JPVRNetworkJNVMStore.open_read_view(network_id, *, max_pages=4096)`, `JPVRNetworkReadView.load_neuron(neuron_id)`, `JPVRNetworkReadView.close()`.
- The view exposes immutable base identity: network ID, clock epoch, scheduler version, generation, commit ID, topology connections, event limits, pending queue and exact neuron record-version manifest.

- [ ] **Step 1: Write failing tests**
  - `test_read_view_loads_only_requested_neuron_from_one_scheduler_snapshot`
  - `test_read_view_rejects_neuron_version_or_epoch_mismatch`
  - Assert requested neuron state round-trips and every loaded locator version equals the scheduler V2 manifest; a mismatched version fails closed.

- [ ] **Step 2: Run focused tests to verify RED**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network_jnvm_store -v`
  - Expected: new API/tests fail because read-view is not present; existing tests remain independently reportable.

- [ ] **Step 3: Implement read-view**
  - Use one active snapshot/catalog/manifest for every lookup on the view.
  - Decode a neuron only on `load_neuron`; verify locator version and clock epoch against that same scheduler snapshot.
  - Do not write or mutate JPVR from the read-view.

- [ ] **Step 4: Run focused tests to verify GREEN**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network_jnvm_store -v`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  - `feat(jnvm): add pinned lazy network read view`

### Task 2: Bounded packed network working set

**Files:**
- Create: `tools/neuron_runtime/network_working_set.py`
- Create: `tools/neuron_runtime/test_network_working_set.py`
- Reuse: `tools/neuron_runtime/compact_state.py`

**Interfaces:**
- Produces: `NetworkNeuronWorkingSet(read_view, capacity=64)`, `get(neuron_id)`, `fork_candidate()`, `resident_ids`, `stage_candidate(neuron_id, runtime)`, `candidate_states`, `publish_after_commit(new_read_view)`.
- Use JPCS packed F64 resident states. No per-neuron save/flush method may be exposed by this network working set.

- [ ] **Step 1: Write failing tests**
  - `test_cold_fault_in_is_lazy_and_lru_is_bounded`
  - `test_fork_candidate_does_not_mutate_authoritative_cache`
  - `test_candidate_states_survive_eviction_until_group_commit`
  - `test_read_view_version_conflict_does_not_publish_cache_entry`
  - Verify state equality, deterministic LRU order, capacity bounds, detached candidate isolation and bounded staging behavior.

- [ ] **Step 2: Run focused tests to verify RED**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network_working_set -v`
  - Expected: new module/API is missing.

- [ ] **Step 3: Implement bounded working set**
  - LRU holds at most `capacity` committed packed states.
  - Candidate staging separately holds only states touched in the current full timestamp; enforce a hard bound derived from the configured event budget.
  - Do not independently commit neuron states.

- [ ] **Step 4: Run focused tests to verify GREEN**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network_working_set -v`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  - `feat(jnvm): add bounded network neuron working set`

### Task 3: Lazy NetworkRuntime adapter

**Files:**
- Modify: `tools/neuron_runtime/network.py`
- Test: `tools/neuron_runtime/test_network.py`

**Interfaces:**
- Produces: lazy mapping support for `NetworkNeuronWorkingSet`, `NetworkRuntime.restore_lazy_checkpoint_state(...)`, `NetworkRuntime.fork_candidate()`, and `NetworkRunResult.changed_neuron_ids`.
- Normal mapping-based `NetworkRuntime` construction must retain current eager behavior and backward compatibility.

- [ ] **Step 1: Write failing tests**
  - `test_lazy_restore_does_not_materialize_all_neuron_runtimes`
  - `test_lazy_runtime_matches_eager_runtime_for_same_timestamp_batches`
  - `test_lazy_runtime_preserves_reward_and_pending_event_validation`
  - Assert only touched states fault in, spikes/weights/clocks/queue equal eager reference, and invalid synapse inputs cannot be durably admitted.

- [ ] **Step 2: Run focused tests to verify RED**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network -v`
  - Expected: lazy restore API and changed-neuron reporting are absent.

- [ ] **Step 3: Implement lazy runtime**
  - Avoid iterating all `Mapping.values()` for R-STDP detection; validate learning context for the actual timestamp targets.
  - Keep topology, event queue and candidate neuron states separately owned.
  - Preserve deterministic sort order, event IDs and full-timestamp atomic in-memory semantics.

- [ ] **Step 4: Run focused tests to verify GREEN**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network -v`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  - `feat(network): support lazy neuron state access`

### Task 4: Atomic coordinator publication through LRU

**Files:**
- Modify: `tools/neuron_runtime/network_jnvm_coordinator.py`
- Modify: `tools/neuron_runtime/network_jnvm_store.py`
- Test: `tools/neuron_runtime/test_network_jnvm_coordinator.py`

**Interfaces:**
- Produces: `NetworkJNVMCoordinator.restore(..., working_set_capacity=64)` and candidate publication that receives exactly the changed neuron IDs and their candidate runtimes.
- Input, reward, timestamp, commit, stale-writer reload and uncertain-commit reconciliation all preserve one-container group transaction semantics.

- [ ] **Step 1: Write failing tests**
  - `test_coordinator_restore_keeps_neuron_state_lazy`
  - `test_timestamp_over_capacity_staging_commits_all_changed_neurons_once`
  - `test_failed_commit_keeps_authoritative_lru_and_scheduler_unchanged`
  - `test_uncertain_commit_does_not_publish_candidate_cache_before_reconciliation`
  - `test_input_and_reward_paths_use_same_lazy_group_commit`
  - Assert one commit ID for scheduler and every changed neuron; no resident cache entry above capacity; full candidate staging retained until COMMIT.

- [ ] **Step 2: Run focused tests to verify RED**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network_jnvm_coordinator -v`
  - Expected: new configuration and semantics fail before implementation.

- [ ] **Step 3: Implement coordinator integration**
  - Replace full `deepcopy(NetworkRuntime)` with a candidate fork that copies scheduler state and bounded LRU while keeping timestamp candidates detached.
  - Commit scheduler and all changed neuron records through one `save_timestamp()`.
  - Publish candidate cache/runtime only after a confirmed COMMIT. Reuse current exact reopen/reconciliation state machine.

- [ ] **Step 4: Run focused store/coordinator tests to verify GREEN**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network_jnvm_store tools.neuron_runtime.test_network_jnvm_coordinator -v`
  - Expected: PASS.

- [ ] **Step 5: Commit**
  - `feat(jnvm): connect lazy LRU to atomic network coordinator`

### Task 5: Differential, failure and scale coverage

**Files:**
- Modify: `tools/neuron_runtime/test_network.py`
- Modify: `tools/neuron_runtime/test_network_jnvm_store.py`
- Modify: `tools/neuron_runtime/test_network_jnvm_coordinator.py`
- Modify: `tools/neuron_runtime/network_scaling_benchmark.py`

- [ ] **Step 1: Add deterministic differential tests**
  - Compare eager and lazy paths for fan-out, same-time multi-target batches, event-budget exhaustion, queue backpressure, R-STDP reward and restart.
  - Inject pre-publication and uncertain-publication failures; assert no partial queue/clock/cache publication.

- [ ] **Step 2: Add scale metrics**
  - Report configured LRU capacity, observed max resident entries, peak timestamp staging neurons, page catalog pages, cold fault-ins, cache hits, page reads, elapsed time and tracemalloc peak.
  - Add or preserve staged 1/10/100/1,000/10,000 profiles; keep large profiles opt-in.

- [ ] **Step 3: Run focused and full Python checks when authorized**
  - Run: `python3 -m unittest tools.neuron_runtime.test_network tools.neuron_runtime.test_network_jnvm_store tools.neuron_runtime.test_network_jnvm_coordinator tools.neuron_runtime.test_network_working_set -v`
  - Then run: `python3 -m tools.run_all_checks`
  - Expected: PASS; record exact counts/duration and any pre-existing failures.
  - These are Python tests, not builds. Do not launch Android/Gradle/CI commands.

- [ ] **Step 4: Commit**
  - `test(jnvm): cover lazy network persistence and bounded residency`

### Task 6: Architecture, project status and final audit

**Files:**
- Modify: `docs/JARVIS_ARCHITECTURE.md`
- Modify: `docs/JARVIS_NETWORK_JNVM_TRANSACTION_V1.md`
- Modify: `docs/JARVIS_JNVM_WORKING_SET_V1.md`
- Modify: `docs/JARVIS_10K_NEURON_SCALING_V1.md`
- Modify: `README.md`
- Modify: `docs/DEVELOPMENT_LOG.md`

- [ ] Update implementation status only after the relevant code and tests exist.
- [ ] Record any tests not run as AUTHORED, NOT RUN; do not claim 10k performance or target-device RAM benefit.
- [ ] Perform static cross-file review for stale V1/V2 claims, scheduler version compatibility and accidental Android/Termux coupling.
- [ ] Commit with `docs(jnvm): record lazy network LRU integration status`.

## Execution gate

The current project handoff says tests must not be run without explicit user permission, while the TDD workflow requires seeing focused tests fail before production code is written. Before Task 1 implementation, obtain permission to run the focused Python unit-test commands above. No Gradle build, APK/AAB, CI or GitHub Actions command is requested by this plan.
