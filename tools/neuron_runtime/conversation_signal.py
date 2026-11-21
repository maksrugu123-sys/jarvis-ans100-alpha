"""Stateful, bounded 10-neuron ANS-100 conversation signal preprocessor.

The network processes transparent request-shape features only. It is not
semantic understanding, a language model, or dialogue-driven synaptic learning.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import re
import threading
import unicodedata
import uuid
from pathlib import Path

from tools.jpvr_reference.storage import JPVRStorage
from tools.numerical_oracle.jarvis_oracle import NeuronParams, effective_threshold
from .network import NetworkConnection, NetworkRuntime
from .network_store import JPVRNetworkCheckpointNotFound, JPVRNetworkCheckpointStore
from .runtime import NeuronRuntime

_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]{3,}", re.UNICODE)
_RUNTIME_VERSION = "JARVIS_ANS100_CONVERSATION_SIGNAL_V2"
_NETWORK_ID = "jarvis-conversation-signal"
_TIME_STEP_NS = 10_000_000
_CONNECTION_DELAY_NS = 1
_MAX_EVENTS = 64
_MAX_PENDING_EVENTS = 64

_SENSOR_IDS = (
    "token_volume_sensor",
    "lexical_diversity_sensor",
    "question_marker_sensor",
    "punctuation_density_sensor",
    "sentence_density_sensor",
    "uppercase_ratio_sensor",
)
_NEURON_IDS = (
    "attention_gate",
    "content_aggregator",
    "lexical_diversity_sensor",
    "punctuation_density_sensor",
    "question_marker_sensor",
    "request_integrator",
    "sentence_density_sensor",
    "structure_aggregator",
    "token_volume_sensor",
    "uppercase_ratio_sensor",
)
_LEGACY_NEURON_IDS = ("attention_gate", "request_integrator")


class ConversationSignalError(ValueError):
    """Invalid prompt or failed bounded neural signal pass."""


@dataclass(frozen=True)
class ConversationSignalResult:
    runtime_version: str
    processed_events: int
    processed_batches: int
    spike_count: int
    active_neuron_count: int
    network_neuron_count: int
    attention_gate_active: bool
    token_volume: float
    lexical_diversity: float
    question_marker: float
    punctuation_density: float
    sentence_density: float
    uppercase_ratio: float
    integrator_adaptation: float
    effective_threshold: float
    model_time_ns: int
    checkpoint_version: int
    learning_enabled: bool = False
    state_persisted: bool = False

    def to_dict(self) -> dict[str, object]:
        return {
            "runtime_version": self.runtime_version,
            "processed_events": self.processed_events,
            "processed_batches": self.processed_batches,
            "spike_count": self.spike_count,
            "active_neuron_count": self.active_neuron_count,
            "network_neuron_count": self.network_neuron_count,
            "attention_gate_active": self.attention_gate_active,
            "features": {
                "token_volume": self.token_volume,
                "lexical_diversity": self.lexical_diversity,
                "question_marker": self.question_marker,
                "punctuation_density": self.punctuation_density,
                "sentence_density": self.sentence_density,
                "uppercase_ratio": self.uppercase_ratio,
                "integrator_adaptation": self.integrator_adaptation,
                "effective_threshold": self.effective_threshold,
            },
            "model_time_ns": self.model_time_ns,
            "checkpoint_version": self.checkpoint_version,
            "learning_enabled": self.learning_enabled,
            "state_persisted": self.state_persisted,
            "scope": "low_level_signal_preprocessing_only",
        }


_SENSOR_PARAMS = NeuronParams(
    theta_0=0.05, theta_min=0.05, theta_max=0.05,
    delta_a_spike=0.0, tau_a_ns=100_000_000, k_a=0.0,
)
_AGGREGATOR_PARAMS = NeuronParams(
    theta_0=1.0, theta_min=1.0, theta_max=1.0,
    delta_a_spike=0.0, tau_a_ns=100_000_000, k_a=0.0,
)
_REQUEST_PARAMS = NeuronParams(
    theta_0=1.5, theta_min=1.5, theta_max=2.0,
    delta_a_spike=0.2, tau_a_ns=100_000_000, k_a=0.5,
)
_ATTENTION_PARAMS = NeuronParams(
    theta_0=1.0, theta_min=1.0, theta_max=1.0,
    delta_a_spike=0.0, tau_a_ns=100_000_000, k_a=0.0,
)


def _new_network() -> NetworkRuntime:
    """Build six feature sensors, two aggregators, an integrator and an output gate."""
    neurons = {
        name: NeuronRuntime(
            {"feature_input": 1.0},
            neuron_params=_SENSOR_PARAMS,
            learning_rule="none",
        )
        for name in _SENSOR_IDS
    }
    neurons["content_aggregator"] = NeuronRuntime(
        {"token_volume_input": 1.0, "lexical_diversity_input": 1.0},
        neuron_params=_AGGREGATOR_PARAMS, learning_rule="none",
    )
    neurons["structure_aggregator"] = NeuronRuntime(
        {
            "question_marker_input": 1.0,
            "punctuation_density_input": 1.0,
            "sentence_density_input": 1.0,
            "uppercase_ratio_input": 1.0,
        },
        neuron_params=_AGGREGATOR_PARAMS, learning_rule="none",
    )
    neurons["request_integrator"] = NeuronRuntime(
        {"content_signal": 1.0, "structure_signal": 1.0},
        neuron_params=_REQUEST_PARAMS, learning_rule="none",
    )
    neurons["attention_gate"] = NeuronRuntime(
        {"request_signal": 1.0},
        neuron_params=_ATTENTION_PARAMS, learning_rule="none",
    )

    routes = (
        ("token-volume-to-content", "token_volume_sensor", "content_aggregator", "token_volume_input"),
        ("lexical-to-content", "lexical_diversity_sensor", "content_aggregator", "lexical_diversity_input"),
        ("question-to-structure", "question_marker_sensor", "structure_aggregator", "question_marker_input"),
        ("punctuation-to-structure", "punctuation_density_sensor", "structure_aggregator", "punctuation_density_input"),
        ("sentence-to-structure", "sentence_density_sensor", "structure_aggregator", "sentence_density_input"),
        ("uppercase-to-structure", "uppercase_ratio_sensor", "structure_aggregator", "uppercase_ratio_input"),
        ("content-to-integrator", "content_aggregator", "request_integrator", "content_signal"),
        ("structure-to-integrator", "structure_aggregator", "request_integrator", "structure_signal"),
        ("integrator-to-attention", "request_integrator", "attention_gate", "request_signal"),
    )
    connections = tuple(
        NetworkConnection(
            connection_id=cid, source_neuron_id=source, target_neuron_id=target,
            target_synapse_id=synapse, delay_ns=_CONNECTION_DELAY_NS, strength=1.0,
        )
        for cid, source, target, synapse in routes
    )
    return NetworkRuntime(
        neurons, connections,
        max_pending_events=_MAX_PENDING_EVENTS,
        max_events_per_run=_MAX_EVENTS,
    )


def _upgrade_legacy_network(network: NetworkRuntime) -> NetworkRuntime:
    """Migrate V1's two-neuron checkpoint while preserving adaptive state and clocks."""
    expected = _new_network()
    if network.neuron_ids == expected.neuron_ids and network.connections == expected.connections:
        return network
    if network.neuron_ids != _LEGACY_NEURON_IDS:
        raise ConversationSignalError("ANS-100 checkpoint has an unsupported network topology")

    previous = network.export_checkpoint_state()
    if previous["pending_events"]:
        raise ConversationSignalError(
            "legacy ANS-100 checkpoint contains pending events and cannot be migrated safely"
        )
    old_integrator = previous["neurons"]["request_integrator"]
    old_gate = previous["neurons"]["attention_gate"]
    target = expected.export_checkpoint_state()
    neurons = dict(target["neurons"])
    neurons["request_integrator"] = NeuronRuntime(
        {"content_signal": 1.0, "structure_signal": 1.0},
        neuron_state=old_integrator.neuron_state,
        neuron_params=_REQUEST_PARAMS,
        synapse_params=old_integrator.synapse_params,
        reward_params=old_integrator.reward_params,
        learning_rule="none",
        processed_batches=old_integrator.processed_batches,
    )
    neurons["attention_gate"] = NeuronRuntime(
        {"request_signal": 1.0},
        neuron_state=old_gate.neuron_state,
        neuron_params=_ATTENTION_PARAMS,
        synapse_params=old_gate.synapse_params,
        reward_params=old_gate.reward_params,
        learning_rule="none",
        processed_batches=old_gate.processed_batches,
    )
    try:
        return NetworkRuntime.restore_checkpoint_state(
            neurons, target["connections"], (),
            max_pending_events=_MAX_PENDING_EVENTS,
            max_events_per_run=_MAX_EVENTS,
            current_time_ns=previous["current_time_ns"],
            has_processed=previous["has_processed"],
        )
    except Exception as exc:
        raise ConversationSignalError(
            "legacy ANS-100 adaptive state could not be migrated safely"
        ) from exc


def _extract_features(prompt: str) -> dict[str, float]:
    tokens = _TOKEN.findall(prompt)
    letters = [character for character in prompt if character.isalpha()]
    punctuation_count = sum(
        1 for character in prompt
        if unicodedata.category(character).startswith("P")
    )
    sentence_count = sum(1 for character in prompt if character in ".!?…。！？")
    return {
        "token_volume": min(len(tokens) / 12.0, 1.0),
        "lexical_diversity": (
            len({token.casefold() for token in tokens}) / len(tokens) if tokens else 0.0
        ),
        "question_marker": 0.35 if "?" in prompt else 0.0,
        "punctuation_density": min(punctuation_count / 4.0, 1.0),
        "sentence_density": min(sentence_count / 4.0, 1.0),
        "uppercase_ratio": (
            sum(1 for character in letters if character.isupper()) / len(letters)
            if letters else 0.0
        ),
    }


class ConversationSignalRuntime:
    """Stateful ten-neuron ALIF-DT pre-pass with optional durable JPVR checkpointing.

    Six request-shape sensors feed content/structure aggregators, then an
    adaptive request integrator and output gate. Each turn advances model time
    by 10 ms. Dialogue-driven learning remains disabled.
    """

    def __init__(self, checkpoint_path: str | Path | None = None) -> None:
        self._checkpoint_path = Path(checkpoint_path).expanduser() if checkpoint_path is not None else None
        self._lock = threading.RLock()
        self._network: NetworkRuntime | None = None
        self._clock_epoch: bytes | None = None
        self._storage: JPVRStorage | None = None
        self._store: JPVRNetworkCheckpointStore | None = None
        self._checkpoint_version = 0
        self._closed = False

    @property
    def persistent_state_enabled(self) -> bool:
        return self._checkpoint_path is not None

    @property
    def neuron_count(self) -> int:
        return len(_NEURON_IDS)

    @property
    def checkpoint_version(self) -> int:
        with self._lock:
            return self._checkpoint_version

    def _ensure_loaded(self) -> None:
        if self._closed:
            raise ConversationSignalError("ANS-100 signal runtime is closed")
        if self._network is not None:
            return
        if self._checkpoint_path is None:
            self._network = _new_network()
            self._clock_epoch = uuid.uuid4().bytes
            return

        storage: JPVRStorage | None = None
        try:
            self._checkpoint_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            try:
                storage = JPVRStorage.open(self._checkpoint_path, recover_tail=True)
            except FileNotFoundError:
                try:
                    storage = JPVRStorage.create(self._checkpoint_path)
                except FileExistsError:
                    storage = JPVRStorage.open(self._checkpoint_path, recover_tail=True)

            store = JPVRNetworkCheckpointStore(storage)
            try:
                loaded = store.load(_NETWORK_ID)
            except JPVRNetworkCheckpointNotFound:
                network = _new_network()
                epoch = uuid.uuid4().bytes
                version = 0
            else:
                network = _upgrade_legacy_network(loaded.snapshot.runtime)
                epoch = loaded.snapshot.clock_epoch_id
                version = loaded.descriptor.record_version

            self._storage = storage
            self._store = store
            self._network = network
            self._clock_epoch = epoch
            self._checkpoint_version = version
        except Exception as exc:
            if storage is not None:
                try:
                    storage.close()
                except Exception:
                    pass
            raise ConversationSignalError(
                "Не удалось загрузить или создать отдельный JPVR checkpoint ANS-100."
            ) from exc

    def analyze(self, prompt: str) -> ConversationSignalResult:
        if not isinstance(prompt, str):
            raise ConversationSignalError("prompt must be text")
        clean = prompt.strip()
        if not clean or len(clean) > 4_000 or chr(0) in clean:
            raise ConversationSignalError("prompt must contain 1..4000 characters")
        features = _extract_features(clean)

        with self._lock:
            self._ensure_loaded()
            assert self._network is not None
            candidate = deepcopy(self._network)
            if candidate.current_time_ns > ((1 << 64) - 1) - _TIME_STEP_NS - 3:
                raise ConversationSignalError("ANS-100 model clock is exhausted")
            model_time = candidate.current_time_ns + _TIME_STEP_NS
            for sensor_id, feature_name in zip(_SENSOR_IDS, features):
                value = features[feature_name]
                if value > 0.0:
                    candidate.schedule_input(
                        model_time, sensor_id, "feature_input",
                        f"conversation-{model_time:x}-{feature_name}", value,
                    )

            try:
                run = candidate.run(max_events=_MAX_EVENTS)
                if run.pending_events or run.budget_exhausted or run.processed_events > _MAX_EVENTS:
                    raise ConversationSignalError(
                        "ANS-100 signal pass exceeded its bounded event budget"
                    )
                if run.processed_events and self._store is not None:
                    assert self._clock_epoch is not None
                    saved = self._store.save(
                        _NETWORK_ID, candidate,
                        clock_epoch_id=self._clock_epoch,
                        expected_record_version=self._checkpoint_version,
                    )
                    self._checkpoint_version = saved.record_version
                if run.processed_events:
                    self._network = candidate
            except ConversationSignalError:
                raise
            except Exception as exc:
                raise ConversationSignalError(
                    "ANS-100 signal pass or checkpoint commit failed"
                ) from exc

            state = candidate.export_checkpoint_state()["neurons"]["request_integrator"]
            neuron_state = state.neuron_state
            spikes = tuple(run.spikes)
            active_ids = {item.neuron_id for item in spikes}
            return ConversationSignalResult(
                runtime_version=_RUNTIME_VERSION,
                processed_events=run.processed_events,
                processed_batches=run.processed_batches,
                spike_count=len(spikes),
                active_neuron_count=len(active_ids),
                network_neuron_count=self.neuron_count,
                attention_gate_active="attention_gate" in active_ids,
                token_volume=features["token_volume"],
                lexical_diversity=features["lexical_diversity"],
                question_marker=features["question_marker"],
                punctuation_density=features["punctuation_density"],
                sentence_density=features["sentence_density"],
                uppercase_ratio=features["uppercase_ratio"],
                integrator_adaptation=neuron_state.a,
                effective_threshold=effective_threshold(neuron_state, state.neuron_params),
                model_time_ns=candidate.current_time_ns,
                checkpoint_version=self._checkpoint_version,
                state_persisted=self._store is not None and self._checkpoint_version > 0,
            )

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._closed = True
            storage = self._storage
            self._storage = None
            self._store = None
            self._network = None
            self._clock_epoch = None
            if storage is not None:
                storage.close()
