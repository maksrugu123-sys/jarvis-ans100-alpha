"""Bounded ANS-100 neural signal pass for JARVIS conversation requests.

This is a real ALIF-DT/NetworkRuntime invocation over transparent, hand-designed
text-shape features. It is not semantic understanding, learned language cognition,
or a persistent network checkpoint.
"""
from __future__ import annotations

from dataclasses import dataclass
import re

from tools.numerical_oracle.jarvis_oracle import NeuronParams
from .network import NetworkConnection, NetworkRuntime
from .runtime import NeuronRuntime

_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]{3,}", re.UNICODE)
_RUNTIME_VERSION = "JARVIS_ANS100_CONVERSATION_SIGNAL_V1"


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
            },
            "learning_enabled": self.learning_enabled,
            "state_persisted": self.state_persisted,
            "scope": "low_level_signal_preprocessing_only",
        }


class ConversationSignalRuntime:
    """One bounded two-neuron ALIF-DT pass; each request starts from fresh state."""

    def analyze(self, prompt: str) -> ConversationSignalResult:
        if not isinstance(prompt, str):
            raise ConversationSignalError("prompt must be text")
        clean = prompt.strip()
        if not clean or len(clean) > 4_000 or chr(0) in clean:
            raise ConversationSignalError("prompt must contain 1..4000 characters")

        tokens = _TOKEN.findall(clean)
        token_volume = min(len(tokens) / 12.0, 1.0)
        lexical_diversity = len({token.casefold() for token in tokens}) / len(tokens) if tokens else 0.0
        question_marker = 0.35 if "?" in clean else 0.0

        input_params = NeuronParams(
            theta_0=1.3, theta_min=1.3, theta_max=1.3, delta_a_spike=0.0,
        )
        output_params = NeuronParams(
            theta_0=1.0, theta_min=1.0, theta_max=1.0, delta_a_spike=0.0,
        )
        network = NetworkRuntime(
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
                    "request-to-attention", "request_integrator",
                    "attention_gate", "request_signal",
                    delay_ns=1, strength=1.0,
                ),
            ),
            max_pending_events=8,
            max_events_per_run=8,
        )
        for name, value in (
            ("token_volume", token_volume),
            ("lexical_diversity", lexical_diversity),
            ("question_marker", question_marker),
        ):
            if value > 0.0:
                network.schedule_input(
                    0, "request_integrator", name, "request-feature-" + name, value,
                )
        try:
            run = network.run(max_events=8)
        except Exception as exc:
            raise ConversationSignalError("ANS-100 signal pass failed") from exc
        spikes = tuple(run.spikes)
        active = any(item.neuron_id == "attention_gate" for item in spikes)
        return ConversationSignalResult(
            runtime_version=_RUNTIME_VERSION,
            processed_events=run.processed_events,
            processed_batches=run.processed_batches,
            spike_count=len(spikes),
            attention_gate_active=active,
            token_volume=token_volume,
            lexical_diversity=lexical_diversity,
            question_marker=question_marker,
        )
