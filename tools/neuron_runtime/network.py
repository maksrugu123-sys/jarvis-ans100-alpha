"""Bounded multi-neuron event-loop reference coordinator.

Composes existing NeuronRuntime instances; all neuron/plasticity equations
remain delegated to the Numerical Oracle. This is not production NCC or a
persistent/concurrent event queue.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field
import hashlib
import heapq
import math
from typing import Mapping, Sequence

from tools.numerical_oracle.jarvis_oracle import OracleInputError

from .runtime import NeuronRuntime, NeuronRuntimeError, RewardUpdate, RuntimeStep, SynapticInput
from .neuron_features import (
    NeuronFeatureError, NeuronFeatureInput, NeuronFeatureState,
    SynapticMemoryState, effective_excitatory_gain,
    effective_synaptic_memory_gain, update_neuron_features, update_synaptic_memory,
)

_U64_MAX = (1 << 64) - 1
_DEFAULT_QUEUE_LIMIT = 4096
_DEFAULT_RUN_LIMIT = 4096
_MAX_LIMIT = 1_000_000

# Hard topology ceilings for the in-memory Python reference coordinator.
MAX_NETWORK_NEURONS = 10_000
MAX_NETWORK_CONNECTIONS = 100_000
MAX_NETWORK_SYNAPSES = 100_000
_INTERNAL_PREFIX = "jarvis-network:"


class NetworkRuntimeError(ValueError):
    """Invalid network topology, event or run configuration."""


class NetworkBackpressureError(NetworkRuntimeError):
    """The bounded event queue cannot accept the next complete batch."""


def _id(name: str, value: object, limit: int = 128) -> str:
    if not isinstance(value, str) or not value.strip() or len(value) > limit:
        raise NetworkRuntimeError(f"{name} must be a non-empty string of at most {limit} characters")
    return value


def _time(value: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= _U64_MAX:
        raise NetworkRuntimeError("time_ns must be an unsigned 64-bit integer")
    return value


def _unit(name: str, value: float) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise NetworkRuntimeError(f"{name} must be a real number")
    value = float(value)
    if not math.isfinite(value) or not 0.0 <= value <= 1.0:
        raise NetworkRuntimeError(f"{name} must be finite and in [0, 1]")
    return value


def _limit(name: str, value: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= _MAX_LIMIT:
        raise NetworkRuntimeError(f"{name} must be an integer in [1, {_MAX_LIMIT}]")
    return value


@dataclass(frozen=True)
class NetworkConnection:
    """Delayed spike route; excitatory routes target a synapse, inhibitory routes target a neuron."""

    connection_id: str
    source_neuron_id: str
    target_neuron_id: str
    target_synapse_id: str | None
    delay_ns: int
    strength: float = 1.0
    attention: float = 0.0
    signal_type: str = "excitatory"

    def validate(self) -> None:
        _id("connection_id", self.connection_id)
        _id("source_neuron_id", self.source_neuron_id)
        _id("target_neuron_id", self.target_neuron_id)
        if self.signal_type not in ("excitatory", "inhibitory"):
            raise NetworkRuntimeError("signal_type must be excitatory or inhibitory")
        if self.signal_type == "excitatory":
            _id("target_synapse_id", self.target_synapse_id)
        elif self.target_synapse_id is not None:
            raise NetworkRuntimeError("inhibitory connections must not target an excitatory synapse")
        if isinstance(self.delay_ns, bool) or not isinstance(self.delay_ns, int) or not 1 <= self.delay_ns <= _U64_MAX:
            raise NetworkRuntimeError("connection delay_ns must be a positive unsigned 64-bit integer")
        _unit("strength", self.strength)
        _unit("attention", self.attention)


@dataclass(frozen=True)
class NetworkSpike:
    neuron_id: str
    time_ns: int
    spike_sequence: int


@dataclass(frozen=True)
class NetworkRunResult:
    processed_events: int
    processed_batches: int
    spikes: tuple[NetworkSpike, ...]
    pending_events: int
    budget_exhausted: bool
    current_time_ns: int


@dataclass(order=True, frozen=True)
class _Event:
    time_ns: int
    event_id: str
    target_neuron_id: str
    target_synapse_id: str | None
    strength: float = field(compare=False)
    attention: float = field(compare=False)
    inhibitory: bool = field(default=False, compare=False)


class NetworkRuntime:
    """Single-threaded, in-memory network event loop with bounded work."""

    def __init__(
        self,
        neurons: Mapping[str, NeuronRuntime],
        connections: Sequence[NetworkConnection] = (),
        *,
        max_pending_events: int = _DEFAULT_QUEUE_LIMIT,
        max_events_per_run: int = _DEFAULT_RUN_LIMIT,
        feature_states: Mapping[str, NeuronFeatureState] | None = None,
        synaptic_memories: Mapping[str, Mapping[str, SynapticMemoryState]] | None = None,
    ) -> None:
        if not isinstance(neurons, Mapping) or not neurons:
            raise NetworkRuntimeError("neurons must be a non-empty mapping")
        if len(neurons) > MAX_NETWORK_NEURONS:
            raise NetworkRuntimeError(f"network exceeds the {MAX_NETWORK_NEURONS}-neuron topology limit")
        if not isinstance(connections, (list, tuple)):
            raise NetworkRuntimeError("connections must be a list or tuple")
        if len(connections) > MAX_NETWORK_CONNECTIONS:
            raise NetworkRuntimeError(f"network exceeds the {MAX_NETWORK_CONNECTIONS}-connection topology limit")
        self._queue_limit = _limit("max_pending_events", max_pending_events)
        self._run_limit = _limit("max_events_per_run", max_events_per_run)
        self._neurons: dict[str, NeuronRuntime] = {}
        total_synapses = 0
        for key, runtime in neurons.items():
            _id("neuron_id", key)
            if not isinstance(runtime, NeuronRuntime):
                raise NetworkRuntimeError("each neuron must be a NeuronRuntime")
            total_synapses += runtime.synapse_count
            if total_synapses > MAX_NETWORK_SYNAPSES:
                raise NetworkRuntimeError(f"network exceeds the {MAX_NETWORK_SYNAPSES}-synapse topology limit")
            self._neurons[key] = deepcopy(runtime)

        if feature_states is None:
            feature_states = {}
        if not isinstance(feature_states, Mapping):
            raise NetworkRuntimeError("feature_states must map neuron IDs to NeuronFeatureState")
        self._feature_states: dict[str, NeuronFeatureState] = {}
        for neuron_id, feature_state in feature_states.items():
            if neuron_id not in self._neurons or not isinstance(feature_state, NeuronFeatureState):
                raise NetworkRuntimeError("feature state references an unknown neuron or invalid state")
            try:
                feature_state.validate()
            except NeuronFeatureError as exc:
                raise NetworkRuntimeError(f"invalid feature state for {neuron_id}: {exc}") from exc
            self._feature_states[neuron_id] = deepcopy(feature_state)

        if synaptic_memories is None:
            synaptic_memories = {}
        if not isinstance(synaptic_memories, Mapping):
            raise NetworkRuntimeError("synaptic_memories must map neuron IDs to synapse traces")
        self._synaptic_memories: dict[str, dict[str, SynapticMemoryState]] = {}
        for neuron_id, traces in synaptic_memories.items():
            runtime = self._neurons.get(neuron_id)
            if runtime is None or not isinstance(traces, Mapping):
                raise NetworkRuntimeError("synaptic memory references an unknown neuron or invalid mapping")
            prepared_traces: dict[str, SynapticMemoryState] = {}
            for synapse_id, trace in traces.items():
                if synapse_id not in runtime.synapses or not isinstance(trace, SynapticMemoryState):
                    raise NetworkRuntimeError("synaptic memory references an unknown synapse or invalid state")
                try:
                    trace.validate()
                except NeuronFeatureError as exc:
                    raise NetworkRuntimeError(f"invalid synaptic memory for {neuron_id}/{synapse_id}: {exc}") from exc
                prepared_traces[synapse_id] = deepcopy(trace)
            self._synaptic_memories[neuron_id] = prepared_traces
            self._feature_states.setdefault(neuron_id, NeuronFeatureState())

        outgoing: dict[str, list[NetworkConnection]] = {key: [] for key in self._neurons}
        owned: set[tuple[str, str]] = set()
        ids: set[str] = set()
        for conn in connections:
            if not isinstance(conn, NetworkConnection):
                raise NetworkRuntimeError("connections must contain NetworkConnection values")
            conn.validate()
            if conn.connection_id in ids:
                raise NetworkRuntimeError("connection IDs must be unique")
            ids.add(conn.connection_id)
            if conn.source_neuron_id not in self._neurons or conn.target_neuron_id not in self._neurons:
                raise NetworkRuntimeError("connection references an unknown neuron")
            if conn.signal_type == "excitatory":
                if conn.target_synapse_id not in self._neurons[conn.target_neuron_id].synapses:
                    raise NetworkRuntimeError("connection references an unknown target synapse")
                key = (conn.target_neuron_id, conn.target_synapse_id)
                if key in owned:
                    raise NetworkRuntimeError("a target synapse may have only one network connection owner")
                owned.add(key)
            outgoing[conn.source_neuron_id].append(conn)
        self._outgoing = {key: tuple(sorted(value, key=lambda c: c.connection_id)) for key, value in outgoing.items()}
        self._connections = tuple(connections)
        self._owned_synapses = owned
        self._queue: list[_Event] = []
        self._pending_ids: set[str] = set()
        self._running = False
        # A delayed reward can advance a synapse's STDP/R-STDP trace clock
        # beyond the neuron's last membrane integration. The network scheduler
        # must not admit events earlier than any persisted state clock.
        state_times = []
        for runtime in self._neurons.values():
            state_times.append(runtime.neuron_state.last_update_model_time_ns)
            for synapse in runtime.synapses.values():
                state_times.append(synapse.trace_last_update_model_time_ns)
                state_times.append(synapse.eligibility_last_update_model_time_ns)
        self._current_time_ns = max(state_times, default=0)
        self._has_processed = False

    @property
    def neuron_ids(self) -> tuple[str, ...]:
        return tuple(sorted(self._neurons))

    @property
    def connections(self) -> tuple[NetworkConnection, ...]:
        return self._connections

    @property
    def feature_states(self) -> Mapping[str, NeuronFeatureState]:
        return dict(self._feature_states)

    @property
    def synaptic_memories(self) -> Mapping[str, Mapping[str, SynapticMemoryState]]:
        return {neuron_id: dict(traces) for neuron_id, traces in self._synaptic_memories.items()}

    @property
    def current_time_ns(self) -> int:
        return self._current_time_ns

    @property
    def pending_events(self) -> int:
        return len(self._queue)

    @property
    def neuron_states(self):
        return {key: runtime.neuron_state for key, runtime in self._neurons.items()}

    def synapse_states(self, neuron_id: str):
        _id("neuron_id", neuron_id)
        try:
            return self._neurons[neuron_id].synapses
        except KeyError as exc:
            raise NetworkRuntimeError(f"unknown neuron: {neuron_id}") from exc

    def schedule_input(self, time_ns: int, neuron_id: str, synapse_id: str,
                       event_id: str, strength: float, attention: float = 0.0) -> None:
        if self._running:
            raise NetworkRuntimeError("cannot schedule external inputs while run() is active")
        time_ns = _time(time_ns)
        _id("neuron_id", neuron_id)
        _id("synapse_id", synapse_id)
        _id("event_id", event_id, 256)
        if event_id.startswith(_INTERNAL_PREFIX):
            raise NetworkRuntimeError("event ID uses a reserved internal prefix")
        strength, attention = _unit("strength", strength), _unit("attention", attention)
        if time_ns < self._current_time_ns or (self._has_processed and time_ns <= self._current_time_ns):
            raise NetworkRuntimeError("external event must be later than the network clock")
        runtime = self._neurons.get(neuron_id)
        if runtime is None or synapse_id not in runtime.synapses:
            raise NetworkRuntimeError("external event references an unknown neuron or synapse")
        if (neuron_id, synapse_id) in self._owned_synapses:
            raise NetworkRuntimeError("external input cannot target a connection-owned synapse")
        if event_id in self._pending_ids:
            raise NetworkRuntimeError("event ID is already pending")
        if len(self._queue) >= self._queue_limit:
            raise NetworkBackpressureError("pending queue is full")
        self._push(_Event(time_ns, event_id, neuron_id, synapse_id, strength, attention))

    def _push(self, event: _Event) -> None:
        heapq.heappush(self._queue, event)
        self._pending_ids.add(event.event_id)

    def _restore(self, events: Sequence[_Event]) -> None:
        for event in events:
            heapq.heappush(self._queue, event)

    def run(self, *, learning_context_id: str | None = None,
            max_events: int | None = None,
            max_timestamps: int | None = None) -> NetworkRunResult:
        """Process queued events deterministically, optionally stopping after complete timestamps.

        max_timestamps is a scheduler boundary, not a partial-batch limit. Every
        selected timestamp is processed as one atomic in-memory candidate batch.
        """
        if self._running:
            raise NetworkRuntimeError("run() is not re-entrant")
        budget = self._run_limit if max_events is None else _limit("max_events", max_events)
        if budget > self._run_limit:
            raise NetworkRuntimeError("max_events exceeds max_events_per_run")
        timestamp_budget = None if max_timestamps is None else _limit("max_timestamps", max_timestamps)
        if self._queue and any(n.learning_rule == "rstdp" for n in self._neurons.values()):
            _id("learning_context_id", learning_context_id)

        processed, batches = 0, 0
        timestamps_processed = 0
        spikes: list[NetworkSpike] = []
        exhausted = False
        self._running = True
        try:
            while self._queue:
                timestamp = self._queue[0].time_ns
                if self._has_processed and timestamp <= self._current_time_ns:
                    raise NetworkRuntimeError("event queue is not strictly later than the network clock")
                group: list[_Event] = []
                while self._queue and self._queue[0].time_ns == timestamp:
                    group.append(heapq.heappop(self._queue))
                if processed + len(group) > budget:
                    self._restore(group)
                    exhausted = True
                    break
                by_node: dict[str, list[_Event]] = {}
                for event in group:
                    by_node.setdefault(event.target_neuron_id, []).append(event)
                reserve = sum(len(self._outgoing[node]) for node in by_node)
                if len(self._queue) + reserve > self._queue_limit:
                    self._restore(group)
                    raise NetworkBackpressureError("timestamp deferred: insufficient queue capacity for worst-case fan-out")
                try:
                    candidates = {node: deepcopy(self._neurons[node]) for node in by_node}
                    steps: dict[str, RuntimeStep] = {}
                    feature_candidates: dict[str, NeuronFeatureState] = {}
                    synaptic_memory_candidates: dict[str, dict[str, SynapticMemoryState]] = {}
                    for node in sorted(by_node):
                        events = sorted(by_node[node], key=lambda e: (e.event_id, e.target_synapse_id or ""))
                        excitatory_events = [event for event in events if not event.inhibitory]
                        inhibitory_events = [event for event in events if event.inhibitory]
                        prior_feature = self._feature_states.get(node)
                        feature_state = prior_feature or (NeuronFeatureState() if inhibitory_events else None)
                        prior_traces = self._synaptic_memories.get(node, {})
                        inhibitory_drive = 1.0
                        for event in inhibitory_events:
                            inhibitory_drive *= 1.0 - event.strength
                        inhibitory_drive = 1.0 - inhibitory_drive
                        effective_inhibition = inhibitory_drive
                        if feature_state is not None:
                            effective_inhibition = 1.0 - (
                                (1.0 - feature_state.inhibition) * (1.0 - inhibitory_drive)
                            )
                        payload = []
                        for event in excitatory_events:
                            memory_gain = effective_synaptic_memory_gain(
                                prior_traces.get(event.target_synapse_id, SynapticMemoryState())
                            )
                            remembered_strength = min(1.0, event.strength * memory_gain)
                            if feature_state is None:
                                effective_strength = remembered_strength
                            else:
                                effective_strength = effective_excitatory_gain(
                                    remembered_strength, effective_inhibition,
                                    feature_state.sensitivity,
                                )
                            payload.append(SynapticInput(
                                event.event_id, event.target_synapse_id,
                                effective_strength, event.attention,
                            ))
                        step = candidates[node].process_batch(
                            timestamp,
                            payload,
                            learning_context_id=learning_context_id,
                            plasticity_scale=(
                                0.5 + feature_state.plasticity
                                if feature_state is not None else 1.0
                            ),
                        )
                        steps[node] = step
                        if feature_state is not None:
                            input_activity = min(1.0, sum(item.strength for item in payload) / max(1, len(payload)))
                            activity = 1.0 if step.spike_emitted else input_activity
                            context_similarity = (
                                sum(item.attention for item in excitatory_events)
                                / max(1, len(excitatory_events))
                            )
                            updated_features = update_neuron_features(
                                feature_state,
                                NeuronFeatureInput(
                                    activity=activity,
                                    predicted_activity=feature_state.memory_trace,
                                    context_similarity=context_similarity,
                                    inhibitory_drive=inhibitory_drive,
                                ),
                            )
                            feature_candidates[node] = updated_features
                            traces = dict(prior_traces)
                            by_synapse: dict[str, list[float]] = {}
                            for event in excitatory_events:
                                by_synapse.setdefault(event.target_synapse_id, []).append(event.strength)
                            for synapse_id, strengths in by_synapse.items():
                                traces[synapse_id] = update_synaptic_memory(
                                    traces.get(synapse_id, SynapticMemoryState()),
                                    coactivation=max(strengths),
                                    prediction_success=1.0 - updated_features.prediction_error,
                                    reward=0.0,
                                )
                            synaptic_memory_candidates[node] = traces
                    generated: list[_Event] = []
                    batch_spikes: list[NetworkSpike] = []
                    for node, step in sorted(steps.items()):
                        if not step.spike_emitted:
                            continue
                        batch_spikes.append(NetworkSpike(node, timestamp, step.spike_sequence))
                        for conn in self._outgoing[node]:
                            if timestamp > _U64_MAX - conn.delay_ns:
                                raise NetworkRuntimeError("propagated event timestamp overflows u64")
                            raw_id = f"{node}\0{step.spike_sequence}\0{conn.connection_id}".encode()
                            event_id = _INTERNAL_PREFIX + hashlib.sha256(raw_id).hexdigest()
                            generated.append(_Event(timestamp + conn.delay_ns, event_id,
                                conn.target_neuron_id, conn.target_synapse_id,
                                float(conn.strength), float(conn.attention),
                                conn.signal_type == "inhibitory"))
                    new_ids = [e.event_id for e in generated]
                    if len(new_ids) != len(set(new_ids)) or any(eid in self._pending_ids for eid in new_ids):
                        raise NetworkRuntimeError("generated event ID collision")
                    if len(self._queue) + len(generated) > self._queue_limit:
                        raise NetworkBackpressureError("generated events exceed queue capacity")
                except (NeuronRuntimeError, OracleInputError, NeuronFeatureError) as exc:
                    self._restore(group)
                    raise NetworkRuntimeError(f"neuron batch rejected: {exc}") from exc
                except Exception:
                    self._restore(group)
                    raise
                self._neurons.update(candidates)
                self._feature_states.update(feature_candidates)
                self._synaptic_memories.update(synaptic_memory_candidates)
                for event in group:
                    self._pending_ids.remove(event.event_id)
                for event in generated:
                    self._push(event)
                processed += len(group)
                batches += len(steps)
                spikes.extend(batch_spikes)
                self._current_time_ns = timestamp
                self._has_processed = True
                timestamps_processed += 1
                if timestamp_budget is not None and timestamps_processed >= timestamp_budget:
                    break
        finally:
            self._running = False
        return NetworkRunResult(processed, batches, tuple(spikes), len(self._queue), exhausted, self._current_time_ns)

    def apply_reward(self, neuron_id: str, synapse_id: str, time_ns: int,
                     reward_value: float, baseline_before: float,
                     learning_context_id: str | None) -> RewardUpdate:
        if self._running:
            raise NetworkRuntimeError("cannot apply rewards while run() is active")
        _id("neuron_id", neuron_id)
        _id("synapse_id", synapse_id)
        time_ns = _time(time_ns)
        if time_ns < self._current_time_ns or any(event.time_ns <= time_ns for event in self._queue):
            raise NetworkRuntimeError("process queued events through reward time before applying reward")
        original = self._neurons.get(neuron_id)
        if original is None:
            raise NetworkRuntimeError(f"unknown neuron: {neuron_id}")
        candidate = deepcopy(original)
        feature_state = self._feature_states.get(neuron_id)
        result = candidate.apply_reward(
            synapse_id, time_ns, reward_value, baseline_before, learning_context_id,
            plasticity_scale=0.5 + feature_state.plasticity if feature_state is not None else 1.0,
        )
        if neuron_id in self._feature_states:
            traces = dict(self._synaptic_memories.get(neuron_id, {}))
            traces[synapse_id] = update_synaptic_memory(
                traces.get(synapse_id, SynapticMemoryState()),
                coactivation=0.0, prediction_success=0.0, reward=reward_value,
            )
            self._synaptic_memories[neuron_id] = traces
        self._neurons[neuron_id] = candidate
        self._current_time_ns = time_ns
        self._has_processed = True
        return result


    def export_checkpoint_state(self) -> dict:
        """Return an isolated, deterministic snapshot of authoritative network state.

        The returned NeuronRuntime instances are deep copies. Pending events are
        sorted by the scheduler's canonical heap order. A running network cannot
        be checkpointed between timestamp batches.
        """
        if self._running:
            raise NetworkRuntimeError("cannot checkpoint while run() is active")
        return {
            "neurons": {key: deepcopy(value) for key, value in self._neurons.items()},
            "feature_states": deepcopy(self._feature_states),
            "synaptic_memories": deepcopy(self._synaptic_memories),
            "connections": tuple(self._connections),
            "pending_events": tuple(sorted(self._queue)),
            "max_pending_events": self._queue_limit,
            "max_events_per_run": self._run_limit,
            "current_time_ns": self._current_time_ns,
            "has_processed": self._has_processed,
        }

    @classmethod
    def restore_checkpoint_state(
        cls,
        neurons: Mapping[str, NeuronRuntime],
        connections: Sequence[NetworkConnection],
        pending_events: Sequence[tuple],
        *,
        max_pending_events: int,
        max_events_per_run: int,
        current_time_ns: int,
        has_processed: bool,
        feature_states: Mapping[str, NeuronFeatureState] | None = None,
        synaptic_memories: Mapping[str, Mapping[str, SynapticMemoryState]] | None = None,
    ) -> "NetworkRuntime":
        """Rebuild a network only after validating its complete scheduler state.

        Pending event tuples use the legacy six-field excitatory form or the
        seven-field form with a trailing inhibitory boolean. Inhibitory events
        have no target synapse and must originate from an inhibitory connection.
        """
        if not isinstance(pending_events, (tuple, list)):
            raise NetworkRuntimeError("pending_events must be a tuple or list")
        if not isinstance(has_processed, bool):
            raise NetworkRuntimeError("has_processed must be bool")
        current_time_ns = _time(current_time_ns)
        result = cls(
            neurons,
            connections,
            max_pending_events=max_pending_events,
            max_events_per_run=max_events_per_run,
            feature_states=feature_states,
            synaptic_memories=synaptic_memories,
        )
        if current_time_ns < result._current_time_ns:
            raise NetworkRuntimeError("network clock precedes a persisted neuron or synapse clock")
        if not has_processed and current_time_ns != result._current_time_ns:
            raise NetworkRuntimeError("unprocessed network clock differs from the initial state clock")
        if len(pending_events) > result._queue_limit:
            raise NetworkBackpressureError("restored pending events exceed the configured queue limit")

        prepared = []
        seen_ids = set()
        for item in pending_events:
            if not isinstance(item, (tuple, list)) or len(item) not in (6, 7):
                raise NetworkRuntimeError("pending event must contain six or seven fields")
            time_ns, event_id, target_neuron_id, target_synapse_id, strength, attention = item[:6]
            inhibitory = item[6] if len(item) == 7 else False
            if not isinstance(inhibitory, bool):
                raise NetworkRuntimeError("pending event inhibitory flag must be bool")
            time_ns = _time(time_ns)
            _id("event_id", event_id, 256)
            _id("target_neuron_id", target_neuron_id)
            if inhibitory:
                if target_synapse_id is not None:
                    raise NetworkRuntimeError("inhibitory event must not target an excitatory synapse")
            else:
                _id("target_synapse_id", target_synapse_id)
            strength = _unit("strength", strength)
            attention = _unit("attention", attention)
            if time_ns < current_time_ns or (has_processed and time_ns <= current_time_ns):
                raise NetworkRuntimeError("restored event is not later than the persisted network clock")
            runtime = result._neurons.get(target_neuron_id)
            if runtime is None or (not inhibitory and target_synapse_id not in runtime.synapses):
                raise NetworkRuntimeError("restored event references an unknown neuron or synapse")
            internal = event_id.startswith(_INTERNAL_PREFIX)
            if inhibitory:
                valid_route = any(
                    conn.signal_type == "inhibitory"
                    and conn.target_neuron_id == target_neuron_id
                    for conn in result._connections
                )
                if not internal or not valid_route:
                    raise NetworkRuntimeError("inhibitory event origin does not match an inhibitory connection")
            else:
                owned = (target_neuron_id, target_synapse_id) in result._owned_synapses
                if internal != owned:
                    raise NetworkRuntimeError("restored event origin does not match synapse ownership")
            if internal:
                suffix = event_id[len(_INTERNAL_PREFIX):]
                if len(suffix) != 64 or any(ch not in "0123456789abcdef" for ch in suffix):
                    raise NetworkRuntimeError("restored internal event ID is not canonical SHA-256 hex")
            if event_id in seen_ids:
                raise NetworkRuntimeError("restored pending event IDs must be unique")
            seen_ids.add(event_id)
            prepared.append(_Event(
                time_ns, event_id, target_neuron_id, target_synapse_id,
                strength, attention, inhibitory,
            ))

        result._queue.clear()
        result._pending_ids.clear()
        for event in prepared:
            result._push(event)
        result._current_time_ns = current_time_ns
        result._has_processed = has_processed
        return result
