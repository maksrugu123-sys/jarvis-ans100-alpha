"""Per-turn execution adapter for the desktop ANS-100 V4 sparse graph.

Core supplies bounded, typed structural hints. The graph propagates those hints
through the 10,000-neuron topology and returns advisory candidate readouts. It
is not a semantic model, a learned policy, or an autonomous decision authority.
"""
from __future__ import annotations

import hashlib
import re
from collections.abc import Mapping
from typing import Any

from .hybrid_ans100 import (
    MAX_EVENTS_PER_RUN,
    NEURON_COUNT,
    POPULATIONS,
    build_network,
    validate_input_ids,
)

_TOKEN = re.compile(r"[A-Za-zА-Яа-яЁё0-9]{2,}", re.UNICODE)
_MAX_TOKEN_FEATURES = 80
_MAX_INPUTS = 128
_DEFAULT_MAX_EVENTS = 10_000
_DEFAULT_MAX_TIMESTAMPS = 8

_CANDIDATE_INPUTS = {
    ("answer", "support"): 1_056,
    ("answer", "conflict"): 1_057,
    ("clarification", "support"): 1_058,
    ("clarification", "conflict"): 1_059,
    ("abstain", "support"): 1_060,
    ("abstain", "conflict"): 1_061,
}
_READOUT_RANGES = {
    ("answer", "support"): (9_400, 9_500),
    ("answer", "conflict"): (9_500, 9_600),
    ("clarification", "support"): (9_600, 9_700),
    ("clarification", "conflict"): (9_700, 9_800),
    ("abstain", "support"): (9_800, 9_900),
    ("abstain", "conflict"): (9_900, 10_000),
}
_CANDIDATES = ("answer", "clarification", "abstain")


def _bounded_confidence(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return 0.0
    result = float(value)
    return result if 0.0 <= result <= 1.0 else 0.0


def _candidate_hints(
    understanding: Mapping[str, object],
    external_context: Mapping[str, object] | None,
) -> dict[tuple[str, str], bool]:
    """Translate Core-owned heuristic metadata into explicit candidate hints."""
    confidence = _bounded_confidence(understanding.get("intent_confidence"))
    intent = understanding.get("intent")
    known_intent = isinstance(intent, str) and intent not in {
        "", "unknown", "unclear", "other", "unclassified",
    }
    plan = understanding.get("reasoning_plan")
    requires_clarification = (
        isinstance(plan, Mapping) and plan.get("requires_clarification") is True
    )
    reference = understanding.get("context_reference")
    reference_status = reference.get("status") if isinstance(reference, Mapping) else "unavailable"
    refers_to_previous = understanding.get("refers_to_previous_context") is True
    ambiguous_reference = refers_to_previous and reference_status in {"ambiguous", "unavailable"}
    has_recent_context = understanding.get("has_recent_user_context") is True
    has_external_page = isinstance(external_context, Mapping) and bool(external_context.get("text"))

    return {
        ("answer", "support"): confidence >= 0.45 and known_intent and not requires_clarification and not ambiguous_reference,
        ("answer", "conflict"): requires_clarification or ambiguous_reference,
        ("clarification", "support"): requires_clarification or ambiguous_reference or confidence < 0.35,
        ("clarification", "conflict"): confidence >= 0.70 and not requires_clarification and not ambiguous_reference,
        ("abstain", "support"): (
            confidence < 0.20
            or (ambiguous_reference and not has_recent_context)
            or (has_external_page and confidence < 0.25)
        ),
        ("abstain", "conflict"): confidence >= 0.65 and known_intent and not requires_clarification and not ambiguous_reference,
    }


def _feature_neuron_ids(
    prompt: str,
    understanding: Mapping[str, object],
    reasoning_context: Mapping[str, object] | None,
    external_context: Mapping[str, object] | None,
) -> tuple[int, ...]:
    """Encode bounded structural features into the general evidence population."""
    ids: set[int] = {1_024}  # non-empty user message
    tokens = []
    for token in _TOKEN.findall(prompt.casefold()):
        if token not in tokens:
            tokens.append(token)
        if len(tokens) >= _MAX_TOKEN_FEATURES:
            break
    for token in tokens:
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        ids.add(int.from_bytes(digest[:4], "big") % 1_024)

    flags = (
        (1_025, "?" in prompt),
        (1_026, "!" in prompt),
        (1_027, "\n" in prompt),
        (1_028, understanding.get("contains_negation") is True),
        (1_029, understanding.get("refers_to_previous_context") is True),
        (1_030, understanding.get("has_recent_user_context") is True),
        (1_031, isinstance(external_context, Mapping)),
        (1_032, isinstance(reasoning_context, Mapping)),
        (1_033, len(prompt) >= 240),
        (1_034, understanding.get("asks_for_explanation") is True),
        (1_035, isinstance(understanding.get("entities"), list) and bool(understanding.get("entities"))),
        (1_036, isinstance(understanding.get("key_terms"), list) and bool(understanding.get("key_terms"))),
    )
    ids.update(neuron_id for neuron_id, enabled in flags if enabled)
    reference = understanding.get("context_reference")
    if isinstance(reference, Mapping):
        status = reference.get("status")
        ids.add({"selected": 1_037, "ambiguous": 1_038, "unavailable": 1_039}.get(status, 1_040))
    confidence = _bounded_confidence(understanding.get("intent_confidence"))
    ids.add(1_041 if confidence >= 0.70 else 1_042 if confidence >= 0.35 else 1_043)
    return tuple(sorted(ids))


class HybridANS100Runtime:
    """Build and run a fresh bounded V4 graph for each desktop chat turn."""

    VERSION = "JARVIS_ANS100_HYBRID_V4"
    TOPOLOGY_ID = "ans100-hybrid-v2"
    NEURON_COUNT = NEURON_COUNT
    CONNECTION_COUNT = 25_500

    def __init__(
        self,
        *,
        max_events: int = _DEFAULT_MAX_EVENTS,
        max_timestamps: int = _DEFAULT_MAX_TIMESTAMPS,
    ) -> None:
        if isinstance(max_events, bool) or not isinstance(max_events, int) or not 1 <= max_events <= MAX_EVENTS_PER_RUN:
            raise ValueError("max_events must be in 1..MAX_EVENTS_PER_RUN")
        if isinstance(max_timestamps, bool) or not isinstance(max_timestamps, int) or not 1 <= max_timestamps <= 32:
            raise ValueError("max_timestamps must be in 1..32")
        self._max_events = max_events
        self._max_timestamps = max_timestamps

    def status(self) -> dict[str, object]:
        return {
            "runtime_version": self.VERSION,
            "topology_id": self.TOPOLOGY_ID,
            "neuron_count": NEURON_COUNT,
            "connection_count": self.CONNECTION_COUNT,
            "population_count": len(POPULATIONS),
            "active_in_chat": True,
            "runtime_mode": "fresh_in_memory_graph_per_turn",
            "learning_enabled": False,
            "persistent_state": False,
            "decision_authority": "advisory_only",
            "scope": "bounded_structural_signal_routing_not_semantic_reasoning",
        }

    def analyze(
        self,
        prompt: str,
        understanding_context: Mapping[str, object],
        reasoning_context: Mapping[str, object] | None = None,
        external_context: Mapping[str, object] | None = None,
    ) -> dict[str, object]:
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 900:
            raise ValueError("prompt must contain 1..900 characters")
        if not isinstance(understanding_context, Mapping):
            raise ValueError("understanding_context must be a mapping")
        if reasoning_context is not None and not isinstance(reasoning_context, Mapping):
            raise ValueError("reasoning_context must be a mapping")
        if external_context is not None and not isinstance(external_context, Mapping):
            raise ValueError("external_context must be a mapping")

        hints = _candidate_hints(understanding_context, external_context)
        input_ids = set(_feature_neuron_ids(
            prompt, understanding_context, reasoning_context, external_context,
        ))
        active_lanes = [
            lane for lane, enabled in hints.items() if enabled
        ]
        input_ids.update(_CANDIDATE_INPUTS[lane] for lane in active_lanes)
        validated_ids = validate_input_ids(sorted(input_ids))
        if len(validated_ids) > _MAX_INPUTS:
            raise ValueError("encoded ANS-100 evidence exceeds the per-turn input budget")

        network = build_network()
        for index, neuron_id in enumerate(validated_ids):
            network.schedule_input(
                time_ns=1,
                neuron_id=str(neuron_id),
                synapse_id="external_evidence",
                event_id=f"ans100-turn-input-{index:03d}",
                strength=1.0,
                attention=0.0,
            )
        run = network.run(
            max_events=self._max_events,
            max_timestamps=self._max_timestamps,
        )

        readout_counts: dict[str, dict[str, int]] = {
            candidate: {"support": 0, "conflict": 0}
            for candidate in _CANDIDATES
        }
        population_counts = {population.id: 0 for population in POPULATIONS}
        for spike in run.spikes:
            neuron_id = int(spike.neuron_id)
            for candidate in _CANDIDATES:
                for polarity in ("support", "conflict"):
                    start, end = _READOUT_RANGES[(candidate, polarity)]
                    if start <= neuron_id < end:
                        readout_counts[candidate][polarity] += 1
            for population in POPULATIONS:
                if population.contains(neuron_id):
                    population_counts[population.id] += 1
                    break

        scores = {
            candidate: readout_counts[candidate]["support"] - readout_counts[candidate]["conflict"]
            for candidate in _CANDIDATES
        }
        readout_spike_total = sum(
            counts["support"] + counts["conflict"]
            for counts in readout_counts.values()
        )
        readout_complete = readout_spike_total > 0 and not run.budget_exhausted
        best_score = max(scores.values(), default=0)
        leaders = [candidate for candidate, score in scores.items() if score == best_score and score > 0]
        if not readout_complete or not leaders:
            selected = "abstain"
            decision_reason = "readout_incomplete_or_no_positive_lane"
        elif len(leaders) == 1:
            selected = leaders[0]
            decision_reason = "highest_positive_readout_score"
        elif "clarification" in leaders:
            selected = "clarification"
            decision_reason = "tie_resolved_toward_clarification"
        else:
            selected = "abstain"
            decision_reason = "unresolved_candidate_tie"

        return {
            "runtime_version": self.VERSION,
            "topology_id": self.TOPOLOGY_ID,
            "neuron_count": NEURON_COUNT,
            "connection_count": self.CONNECTION_COUNT,
            "processed_events": run.processed_events,
            "processed_batches": run.processed_batches,
            "spike_count": len(run.spikes),
            "input_neuron_count": len(validated_ids),
            "active_candidate_lanes": [
                f"{candidate}_{polarity}" for candidate, polarity in active_lanes
            ],
            "candidate_hints": {
                f"{candidate}_{polarity}": enabled
                for (candidate, polarity), enabled in hints.items()
            },
            "readout_spike_counts": readout_counts,
            "readout_spike_total": readout_spike_total,
            "readout_complete": readout_complete,
            "candidate_signal_scores": scores,
            "selected_candidate": selected,
            "decision_reason": decision_reason,
            "pending_events_at_budget_boundary": run.pending_events,
            "turn_boundary_events_discarded": run.pending_events > 0,
            "event_budget_exhausted": run.budget_exhausted,
            "population_spike_counts": population_counts,
            "learning_enabled": False,
            "persistent_state": False,
            "decision_authority": "advisory_only",
            "scope": "bounded_structural_signal_routing_not_semantic_reasoning",
        }

    def close(self) -> None:
        return None
