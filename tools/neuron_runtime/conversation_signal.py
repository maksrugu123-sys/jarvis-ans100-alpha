"""Stateful, bounded ANS-100 ALIF-DT signal preprocessor for JARVIS.

A tiny NetworkRuntime is retained between turns and can be checkpointed to a
separate JPVR container. The network processes transparent text-shape features;
it does not understand language or train synapses from dialogue.
"""
from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass
import re
import threading
import uuid
from pathlib import Path

from tools.jpvr_reference.storage import JPVRStorage
from tools.numerical_oracle.jarvis_oracle import NeuronParams, effective_threshold
from .network import NetworkConnection, NetworkRuntime
from .network_store import JPVRNetworkCheckpointNotFound, JPVRNetworkCheckpointStore
from .runtime import NeuronRuntime

_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]{3,}", re.UNICODE)
_RUNTIME_VERSION = "JARVIS_ANS100_CONVERSATION_SIGNAL_V1"
_NETWORK_ID = "jarvis-conversation-signal"
_TIME_STEP_NS = 10_000_000
_MAX_EVENTS = 8


class ConversationSignalError(ValueError):
    """Invalid prompt or failed bounded neural signal pass."""


@dataclass(frozen=True)
class ConversationSignalResult:
    runtime_version: str
    processed_events: int
    processed_batches: int
    spike_count: int
    attention_gate_active: bool
    token_volume: float
    lexical_diversity: float
    question_marker: float
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
            "attention_gate_active": self.attention_gate_active,
            "features": {
                "token_volume": self.token_volume,
                "lexical_diversity": self.lexical_diversity,
                "question_marker": self.question_marker,
                "integrator_adaptation": self.integrator_adaptation,
                "effective_threshold": self.effective_threshold,
            },
            "model_time_ns": self.model_time_ns,
            "checkpoint_version": self.checkpoint_version,
            "learning_enabled": self.learning_enabled,
            "state_persisted": self.state_persisted,
            "scope": "low_level_signal_preprocessing_only",
        }


def _new_network() -> NetworkRuntime:
    input_params = NeuronParams(
        theta_0=1.0,
        theta_min=1.0,
        theta_max=1.5,
        delta_a_spike=0.2,
        tau_a_ns=100_000_000,
        k_a=0.5,
    )
    output_params = NeuronParams(
        theta_0=1.0,
        theta_min=1.0,
        theta_max=1.0,
        delta_a_spike=0.0,
    )
    return NetworkRuntime(
        {
            "request_integrator": NeuronRuntime(
                {
                    "token_volume": 1.0,
                    "lexical_diversity": 1.0,
                    "question_marker": 1.0,
                },
                neuron_params=input_params,
                learning_rule="none",
            ),
            "attention_gate": NeuronRuntime(
                {"request_signal": 1.0},
                neuron_params=output_params,
                learning_rule="none",
            ),
        },
        (
            NetworkConnection(
                "request-to-attention",
                "request_integrator",
                "attention_gate",
                "request_signal",
                delay_ns=1,
                strength=1.0,
            ),
        ),
        max_pending_events=_MAX_EVENTS,
        max_events_per_run=_MAX_EVENTS,
    )


class ConversationSignalRuntime:
    """Stateful two-neuron ALIF-DT pass with optional durable JPVR checkpointing.

    Each user turn advances model time by 10 ms. The ALIF adaptation variable
    survives between turns. With checkpoint_path set, the complete network
    snapshot is committed to its own JPVR file after each processed turn.
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
                # Keep a fresh network in memory until its first real input event.
                # The first successful turn then creates checkpoint version 1.
                network = _new_network()
                epoch = uuid.uuid4().bytes
                version = 0
            else:
                network = loaded.snapshot.runtime
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

        tokens = _TOKEN.findall(clean)
        token_volume = min(len(tokens) / 12.0, 1.0)
        lexical_diversity = (
            len({token.casefold() for token in tokens}) / len(tokens)
            if tokens else 0.0
        )
        question_marker = 0.35 if "?" in clean else 0.0

        with self._lock:
            self._ensure_loaded()
            assert self._network is not None
            candidate = deepcopy(self._network)
            model_time = candidate.current_time_ns + _TIME_STEP_NS
            for name, value in (
                ("token_volume", token_volume),
                ("lexical_diversity", lexical_diversity),
                ("question_marker", question_marker),
            ):
                if value > 0.0:
                    candidate.schedule_input(
                        model_time,
                        "request_integrator",
                        name,
                        f"conversation-{model_time:x}-{name}",
                        value,
                    )

            try:
                run = candidate.run(max_events=_MAX_EVENTS)
                if run.pending_events or run.budget_exhausted:
                    raise ConversationSignalError("ANS-100 signal pass exceeded its bounded event budget")
                if run.processed_events and self._store is not None:
                    assert self._clock_epoch is not None
                    saved = self._store.save(
                        _NETWORK_ID,
                        candidate,
                        clock_epoch_id=self._clock_epoch,
                        expected_record_version=self._checkpoint_version,
                    )
                    self._checkpoint_version = saved.record_version
                if run.processed_events:
                    self._network = candidate
            except ConversationSignalError:
                raise
            except Exception as exc:
                raise ConversationSignalError("ANS-100 signal pass or checkpoint commit failed") from exc

            state = candidate.export_checkpoint_state()["neurons"]["request_integrator"]
            neuron_state = state.neuron_state
            threshold = effective_threshold(neuron_state, state.neuron_params)
            spikes = tuple(run.spikes)
            return ConversationSignalResult(
                runtime_version=_RUNTIME_VERSION,
                processed_events=run.processed_events,
                processed_batches=run.processed_batches,
                spike_count=len(spikes),
                attention_gate_active=any(item.neuron_id == "attention_gate" for item in spikes),
                token_volume=token_volume,
                lexical_diversity=lexical_diversity,
                question_marker=question_marker,
                integrator_adaptation=neuron_state.a,
                effective_threshold=threshold,
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
