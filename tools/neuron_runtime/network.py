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

_U64_MAX = (1 << 64) - 1
_DEFAULT_QUEUE_LIMIT = 4096
_DEFAULT_RUN_LIMIT = 4096
_MAX_LIMIT = 1_000_000
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
    """Directed output-spike route; weight lives on the target synapse."""

    connection_id: str
    source_neuron_id: str
    target_neuron_id: str
    target_synapse_id: str
    delay_ns: int
    strength: float = 1.0
    attention: float = 0.0

    def validate(self) -> None:
        _id("connection_id", self.connection_id)
        _id("source_neuron_id", self.source_neuron_id)
        _id("target_neuron_id", self.target_neuron_id)
        _id("target_synapse_id", self.target_synapse_id)
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
    target_synapse_id: str
    strength: float = field(compare=False)
    attention: float = field(compare=False)


class NetworkRuntime:
    """Single-threaded, in-memory network event loop with bounded work."""

    def __init__(
        self,
        neurons: Mapping[str, NeuronRuntime],
        connections: Sequence[NetworkConnection] = (),
        *,
        max_pending_events: int = _DEFAULT_QUEUE_LIMIT,
        max_events_per_run: int = _DEFAULT_RUN_LIMIT,
    ) -> None:
        if not isinstance(neurons, Mapping) or not neurons:
            raise NetworkRuntimeError("neurons must be a non-empty mapping")
        if not isinstance(connections, (list, tuple)):
            raise NetworkRuntimeError("connections must be a list or tuple")
        self._queue_limit = _limit("max_pending_events", max_pending_events)
        self._run_limit = _limit("max_events_per_run", max_events_per_run)
        self._neurons: dict[str, NeuronRuntime] = {}
        for key, runtime in neurons.items():
            _id("neuron_id", key)
            if not isinstance(runtime, NeuronRuntime):
                raise NetworkRuntimeError("each neuron must be a NeuronRuntime")
            self._neurons[key] = deepcopy(runtime)

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
        self._current_time_ns = max(n.neuron_state.last_update_model_time_ns for n in self._neurons.values())
        self._has_processed = False

    @property
    def neuron_ids(self) -> tuple[str, ...]:
        return tuple(sorted(self._neurons))

    @property
    def connections(self) -> tuple[NetworkConnection, ...]:
        return self._connections

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
            max_events: int | None = None) -> NetworkRunResult:
        if self._running:
            raise NetworkRuntimeError("run() is not re-entrant")
        budget = self._run_limit if max_events is None else _limit("max_events", max_events)
        if budget > self._run_limit:
            raise NetworkRuntimeError("max_events exceeds max_events_per_run")
        if self._queue and any(n.learning_rule == "rstdp" for n in self._neurons.values()):
            _id("learning_context_id", learning_context_id)

        processed, batches = 0, 0
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
                    for node in sorted(by_node):
                        events = sorted(by_node[node], key=lambda e: (e.event_id, e.target_synapse_id))
                        payload = [SynapticInput(e.event_id, e.target_synapse_id, e.strength, e.attention) for e in events]
                        steps[node] = candidates[node].process_batch(timestamp, payload,
                            learning_context_id=learning_context_id)
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
                                float(conn.strength), float(conn.attention)))
                    new_ids = [e.event_id for e in generated]
                    if len(new_ids) != len(set(new_ids)) or any(eid in self._pending_ids for eid in new_ids):
                        raise NetworkRuntimeError("generated event ID collision")
                    if len(self._queue) + len(generated) > self._queue_limit:
                        raise NetworkBackpressureError("generated events exceed queue capacity")
                except (NeuronRuntimeError, OracleInputError) as exc:
                    self._restore(group)
                    raise NetworkRuntimeError(f"neuron batch rejected: {exc}") from exc
                except Exception:
                    self._restore(group)
                    raise
                self._neurons.update(candidates)
                for event in group:
                    self._pending_ids.remove(event.event_id)
                for event in generated:
                    self._push(event)
                processed += len(group)
                batches += len(steps)
                spikes.extend(batch_spikes)
                self._current_time_ns = timestamp
                self._has_processed = True
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
        result = candidate.apply_reward(synapse_id, time_ns, reward_value, baseline_before, learning_context_id)
        self._neurons[neuron_id] = candidate
        self._current_time_ns = time_ns
        self._has_processed = True
        return result
