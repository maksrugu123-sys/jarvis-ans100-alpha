"""Desktop ANS-100 V4 topology matching the Android sparse graph.

This module constructs the same 10,000-node, 25,500-edge seven-population
network. It is a source-level desktop runtime foundation; typed Core evidence
routing, durable checkpointing and Android/Python numerical parity remain
separate integration gates.
"""
from __future__ import annotations

from dataclasses import dataclass

from tools.neuron_runtime.network import NetworkConnection, NetworkRuntime
from tools.neuron_runtime.runtime import NeuronRuntime
from tools.numerical_oracle.jarvis_oracle import NeuronParams

TOPOLOGY_ID = "ans100-hybrid-v2"
TOPOLOGY_VERSION = 4
NEURON_COUNT = 10_000
EXPECTED_CONNECTION_COUNT = 25_500
MAX_EVENTS_PER_RUN = 50_000
MAX_PENDING_EVENTS = 100_000
CONNECTION_DELAY_NS = 1_000_000
INPUT_SYNAPSE_ID = "external_evidence"

@dataclass(frozen=True)
class Population:
    id: str
    start: int
    end: int

    @property
    def size(self) -> int:
        return self.end - self.start

    def contains(self, neuron_id: int) -> bool:
        return self.start <= neuron_id < self.end

POPULATIONS = (
    Population("evidence_input", 0, 1_200),
    Population("association", 1_200, 4_000),
    Population("working_state", 4_000, 5_800),
    Population("candidate", 5_800, 7_300),
    Population("critic_uncertainty", 7_300, 8_500),
    Population("goal_planning", 8_500, 9_400),
    Population("decision_readout", 9_400, 10_000),
)

SLOTS = ("answer", "clarification", "abstain")
POLARITIES = ("support", "conflict")

def _lane(population: Population, slot_index: int, polarity_index: int,
          lane_size: int, shared_prefix: int = 0) -> Population:
    index = slot_index * 2 + polarity_index
    start = population.start + shared_prefix + index * lane_size
    return Population(f"{population.id}_{SLOTS[slot_index]}_{POLARITIES[polarity_index]}",
                      start, start + lane_size)

def _project(edges: list[NetworkConnection], kind: str, source: Population,
             target: Population, fan_out: int, salt: int, recurrent: bool = False,
             excluded: frozenset[int] = frozenset()) -> None:
    for source_offset in range(source.size):
        source_id = source.start + source_offset
        if source_id in excluded:
            continue
        used: set[int] = set()
        for slot in range(fan_out):
            target_offset = (source_offset * 37 + slot * 113 + salt) % target.size
            target_id = target.start + target_offset
            if recurrent and target_id == source_id:
                target_offset = (target_offset + 1) % target.size
                target_id = target.start + target_offset
            while target_id in used:
                target_offset = (target_offset + 1) % target.size
                target_id = target.start + target_offset
            used.add(target_id)
            connection_id = f"ans100-v4-{kind}-{source_id}-{target_id}"
            edges.append(NetworkConnection(
                connection_id=connection_id,
                source_neuron_id=str(source_id),
                target_neuron_id=str(target_id),
                target_synapse_id=connection_id,
                delay_ns=CONNECTION_DELAY_NS,
                strength=1.0,
            ))

def build_connections() -> tuple[NetworkConnection, ...]:
    evidence, association, working, _candidate, _critic, goal, _readout = POPULATIONS
    general_association = Population("association_general", 1_200, 2_200)
    general_working = Population("working_general", 4_000, 4_300)
    excluded = frozenset(range(1_056, 1_062))
    edges: list[NetworkConnection] = []
    _project(edges, "evidence-general-association", evidence, general_association, 3, 11,
             excluded=excluded)
    for slot_index, slot in enumerate(SLOTS):
        for polarity_index, polarity in enumerate(POLARITIES):
            input_id = 1_056 + slot_index * 2 + polarity_index
            _project(edges, f"candidate-input-{slot}-{polarity}",
                     Population("candidate_input", input_id, input_id + 1),
                     _lane(association, slot_index, polarity_index, 300, 1_000), 3, 17)

    _project(edges, "association-general-recurrent", general_association,
             general_association, 1, 29, recurrent=True)
    _project(edges, "working-general-recurrent", general_working,
             general_working, 1, 61, recurrent=True)

    for slot_index, slot in enumerate(SLOTS):
        for polarity_index, polarity in enumerate(POLARITIES):
            assoc = _lane(association, slot_index, polarity_index, 300, 1_000)
            work = _lane(working, slot_index, polarity_index, 250, 300)
            candidate = _lane(POPULATIONS[3], slot_index, polarity_index, 250)
            critic = _lane(POPULATIONS[4], slot_index, polarity_index, 200)
            readout_start = 9_400 + slot_index * 200 + polarity_index * 100
            readout = Population(f"readout_{slot}_{polarity}", readout_start, readout_start + 100)
            _project(edges, f"association-{slot}-{polarity}-recurrent", assoc, assoc, 1, 29, recurrent=True)
            _project(edges, f"association-working-{slot}-{polarity}", assoc, work, 2, 43)
            _project(edges, f"working-{slot}-{polarity}-recurrent", work, work, 1, 61, recurrent=True)
            _project(edges, f"working-candidate-{slot}-{polarity}", work, candidate, 2, 79)
            _project(edges, f"candidate-critic-{slot}-{polarity}", candidate, critic, 2, 113)
            _project(edges, f"critic-candidate-{slot}-{polarity}", critic, candidate, 1, 131, recurrent=True)
            _project(edges, f"candidate-readout-{slot}-{polarity}", candidate, readout, 1, 149)
            _project(edges, f"critic-readout-{slot}-{polarity}", critic, readout, 1, 167)

    _project(edges, "association-general-working", general_association, general_working, 2, 43)
    _project(edges, "working-to-goal", working, goal, 1, 97)
    if len(edges) != EXPECTED_CONNECTION_COUNT or len({edge.connection_id for edge in edges}) != len(edges):
        raise ValueError(f"ANS-100 V4 topology mismatch: {len(edges)} connections")
    return tuple(edges)

def build_network() -> NetworkRuntime:
    """Build the complete in-memory desktop graph with bounded event processing."""
    connections = build_connections()
    incoming: dict[int, dict[str, float]] = {node_id: {} for node_id in range(NEURON_COUNT)}
    for edge in connections:
        incoming[int(edge.target_neuron_id)][edge.target_synapse_id] = (
            0.18 if ("-recurrent-" in edge.connection_id or "-critic-candidate-" in edge.connection_id) else 1.0
        )
    for node_id in range(1_200):
        incoming[node_id][INPUT_SYNAPSE_ID] = 1.0

    params = NeuronParams(
        theta_0=1.0, theta_min=1.0, theta_max=1.5,
        delta_a_spike=0.2, tau_a_ns=100_000_000, k_a=0.5,
    )
    neurons = {
        str(node_id): NeuronRuntime(
            synapses=incoming[node_id],
            neuron_params=params,
            learning_rule="none",
        )
        for node_id in range(NEURON_COUNT)
    }
    return NetworkRuntime(
        neurons, connections,
        max_pending_events=MAX_PENDING_EVENTS,
        max_events_per_run=MAX_EVENTS_PER_RUN,
    )

def validate_input_ids(neuron_ids: object) -> tuple[int, ...]:
    if not isinstance(neuron_ids, (list, tuple)) or not neuron_ids or len(neuron_ids) > 128:
        raise ValueError("one to 128 active evidence neuron IDs are required")
    result = tuple(neuron_ids)
    if any(isinstance(item, bool) or not isinstance(item, int) or not 0 <= item < 1_200
           for item in result):
        raise ValueError("evidence inputs must target the 1,200-neuron input population")
    if len(set(result)) != len(result):
        raise ValueError("evidence input neuron IDs must be unique")
    return result
