"""Per-turn execution adapter for the Android-compatible ANS-100 V4 graph.

This mirrors Android's TypedEvidenceEncoder and AndroidHybridEvidenceInputAdapter:
stable feature/ordinal slots, typed provenance, six reserved candidate inputs,
and presence pulses. Core supplies the heuristic labels; the network propagates
them and reports advisory readouts. It is not a semantic model or learned policy.
"""
from __future__ import annotations

from collections.abc import Mapping, Sequence
import uuid

from .hybrid_ans100 import (
    EXPECTED_CONNECTION_COUNT,
    MAX_EVENTS_PER_RUN,
    NEURON_COUNT as V4_NEURON_COUNT,
    POPULATIONS,
    TOPOLOGY_ID as V4_TOPOLOGY_ID,
    build_network,
    validate_input_ids,
)

_MAX_RECORDS_PER_TURN = 64
_MAX_ACTIVE_INPUTS_PER_TURN = 64
_SLOT_WIDTH_PER_FEATURE = 48
_MAX_ORDINAL_PER_FEATURE = _SLOT_WIDTH_PER_FEATURE // 2
_MAX_CORE_HINTS = 10
_MAX_PLAN_HINTS = 6
_MAX_EXTERNAL_REFERENCES = 4
_DEFAULT_MAX_EVENTS = 10_000
_DEFAULT_MAX_TIMESTAMPS = 8

# Persisted Android feature order. Append only; never reorder or hash user text.
_FEATURE_ORDER = (
    "user.message.text",
    "message.structure.length_chars",
    "message.structure.has_question_mark",
    "message.structure.has_exclamation_mark",
    "message.structure.has_line_break",
    "core.language_hint",
    "core.intent_hint",
    "core.intent_confidence_hint",
    "core.task_kind_hint",
    "core.key_term_hint",
    "core.entity_hint",
    "core.negation_hint",
    "core.explanation_hint",
    "core.context_reference_status",
    "core.context_reference_method",
    "core.plan_goal_hint",
    "core.plan_step_hint",
    "core.requires_clarification_hint",
    "core.clarification_question_hint",
    "core.evidence_note_hint",
    "memory.approved_reference",
    "tool.verified_result",
)
_FEATURE_INDEX = {feature: index for index, feature in enumerate(_FEATURE_ORDER)}

_CANDIDATE_INPUTS = {
    ("answer", "support"): ("ans100.candidate.answer.support", 1_056),
    ("answer", "conflict"): ("ans100.candidate.answer.conflict", 1_057),
    ("clarification", "support"): ("ans100.candidate.clarification.support", 1_058),
    ("clarification", "conflict"): ("ans100.candidate.clarification.conflict", 1_059),
    ("abstain", "support"): ("ans100.candidate.abstain.support", 1_060),
    ("abstain", "conflict"): ("ans100.candidate.abstain.conflict", 1_061),
}
_CANDIDATE_IDS = {
    "answer": "respond_to_original_request",
    "clarification": "request_clarification",
    "abstain": "abstain_without_supported_candidate",
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


def _text(value: object, maximum: int = 2_048) -> str:
    if not isinstance(value, str):
        return ""
    return value.strip()[:maximum]


def _candidate_hints(understanding: Mapping[str, object]) -> dict[tuple[str, str], bool]:
    """Match Android TypedEvidenceEncoder/ANS100HybridPopulationController rules."""
    confidence = _bounded_confidence(understanding.get("intent_confidence"))
    intent = _text(understanding.get("intent"), 80)
    task_kind = _text(understanding.get("task_kind"), 80)
    plan = understanding.get("reasoning_plan")
    plan = plan if isinstance(plan, Mapping) else {}
    goal = _text(plan.get("goal"), 512)
    requires_clarification = plan.get("requires_clarification") is True
    reference = understanding.get("context_reference")
    reference = reference if isinstance(reference, Mapping) else {}
    reference_status = _text(reference.get("status"), 48)
    ambiguous_context = (
        understanding.get("refers_to_previous_context") is True
        and reference_status in {"ambiguous", "unavailable"}
    )
    needs_clarification = requires_clarification or ambiguous_context
    usable_intent = (
        bool(intent)
        and intent != "statement_or_unknown"
        and bool(task_kind)
        and confidence >= 0.4
    )
    has_goal = usable_intent and bool(goal)
    return {
        ("answer", "support"): has_goal and not needs_clarification,
        ("answer", "conflict"): not has_goal or needs_clarification,
        ("clarification", "support"): needs_clarification,
        ("clarification", "conflict"): not needs_clarification,
        ("abstain", "support"): not has_goal and not needs_clarification,
        ("abstain", "conflict"): has_goal or needs_clarification,
    }


def _typed_evidence_records(
    prompt: str,
    understanding: Mapping[str, object],
    turn_id: str,
    approved_memories: Sequence[Mapping[str, object]] = (),
) -> tuple[dict[str, object], ...]:
    """Port the bounded Android TypedEvidenceEncoder contract to Python."""
    if not isinstance(prompt, str) or len(prompt) > 2_048:
        raise ValueError("raw message exceeds the typed evidence input bound")
    records: list[dict[str, object]] = []

    def add(
        feature: str,
        source: str,
        status: str,
        value: object,
        provenance: str,
        activation: float = 0.5,
        ordinal: int = 0,
    ) -> None:
        if len(records) >= _MAX_RECORDS_PER_TURN:
            return
        if feature not in _FEATURE_INDEX and feature not in {
            item[0] for item in _CANDIDATE_INPUTS.values()
        }:
            raise ValueError("feature is not assigned to the hybrid input population")
        if isinstance(activation, bool) or not isinstance(activation, (int, float)):
            raise ValueError("evidence activation must be numeric")
        activation = float(activation)
        if not 0.0 <= activation <= 1.0:
            raise ValueError("evidence activation must be in [0, 1]")
        if isinstance(ordinal, bool) or not isinstance(ordinal, int) or not 0 <= ordinal < _MAX_ORDINAL_PER_FEATURE:
            raise ValueError("evidence feature ordinal exceeds its reserved input slots")
        records.append({
            "record_id": f"{feature}:{ordinal}",
            "turn_id": turn_id,
            "feature": feature,
            "source": source,
            "status": status,
            "value": value,
            "activation": activation,
            "provenance_ref": _text(provenance, 320),
        })

    message = prompt
    turn_marker = f"turn:{turn_id}"
    add(
        "user.message.text", "USER_MESSAGE", "USER_PROVIDED", message,
        f"{turn_marker}:user_message", activation=1.0,
    )
    add(
        "message.structure.length_chars", "MESSAGE_STRUCTURE", "OBSERVED_STRUCTURE",
        len(message), f"{turn_marker}:message.length",
        activation=min(len(message) / 2_048.0, 1.0),
    )
    add(
        "message.structure.has_question_mark", "MESSAGE_STRUCTURE", "OBSERVED_STRUCTURE",
        "?" in message or "？" in message, f"{turn_marker}:message.question_mark",
    )
    add(
        "message.structure.has_exclamation_mark", "MESSAGE_STRUCTURE", "OBSERVED_STRUCTURE",
        "!" in message or "！" in message, f"{turn_marker}:message.exclamation_mark",
    )
    add(
        "message.structure.has_line_break", "MESSAGE_STRUCTURE", "OBSERVED_STRUCTURE",
        "\n" in message or "\r" in message, f"{turn_marker}:message.line_break",
    )

    confidence = _bounded_confidence(understanding.get("intent_confidence"))

    def core_text(feature: str, value: object, field: str, ordinal: int = 0) -> None:
        clean = _text(value, 2_048)
        if clean:
            add(
                feature, "CORE_UNDERSTANDING", "HEURISTIC", clean[:80],
                f"{turn_marker}:core.{field}", activation=confidence, ordinal=ordinal,
            )

    core_text("core.language_hint", understanding.get("language"), "language")
    core_text("core.intent_hint", understanding.get("intent"), "intent")
    add(
        "core.intent_confidence_hint", "CORE_UNDERSTANDING", "HEURISTIC",
        confidence, f"{turn_marker}:core.intentConfidence", activation=confidence,
    )
    core_text("core.task_kind_hint", understanding.get("task_kind"), "taskKind")

    key_terms = understanding.get("key_terms")
    if isinstance(key_terms, (list, tuple)):
        for index, term in enumerate(key_terms[:_MAX_CORE_HINTS]):
            if _text(term):
                add(
                    "core.key_term_hint", "CORE_UNDERSTANDING", "HEURISTIC",
                    _text(term), f"{turn_marker}:core.keyTerms[{index}]",
                    activation=confidence, ordinal=index,
                )

    entities = understanding.get("entities")
    if isinstance(entities, (list, tuple)):
        for index, entity in enumerate(entities[:_MAX_CORE_HINTS]):
            if _text(entity):
                add(
                    "core.entity_hint", "CORE_UNDERSTANDING", "HEURISTIC",
                    _text(entity), f"{turn_marker}:core.entities[{index}]",
                    activation=confidence, ordinal=index,
                )

    add(
        "core.negation_hint", "CORE_UNDERSTANDING", "HEURISTIC",
        understanding.get("contains_negation") is True,
        f"{turn_marker}:core.containsNegation",
    )
    add(
        "core.explanation_hint", "CORE_UNDERSTANDING", "HEURISTIC",
        understanding.get("asks_for_explanation") is True,
        f"{turn_marker}:core.asksForExplanation",
    )

    reference = understanding.get("context_reference")
    reference = reference if isinstance(reference, Mapping) else {}
    core_text("core.context_reference_status", reference.get("status"), "contextReference.status")
    core_text("core.context_reference_method", reference.get("method"), "contextReference.method")

    plan = understanding.get("reasoning_plan")
    plan = plan if isinstance(plan, Mapping) else {}
    core_text("core.plan_goal_hint", plan.get("goal"), "reasoningPlan.goal")
    steps = plan.get("steps")
    if isinstance(steps, (list, tuple)):
        for index, step in enumerate(steps[:_MAX_PLAN_HINTS]):
            clean = _text(step)
            if clean:
                add(
                    "core.plan_step_hint", "CORE_REASONING_PLAN", "HEURISTIC",
                    clean, f"{turn_marker}:core.reasoningPlan.steps[{index}]",
                    ordinal=index,
                )

    requires_clarification = plan.get("requires_clarification") is True
    add(
        "core.requires_clarification_hint", "CORE_REASONING_PLAN", "HEURISTIC",
        requires_clarification, f"{turn_marker}:core.reasoningPlan.requiresClarification",
    )
    clarification_question = _text(plan.get("clarification_question"))
    if requires_clarification and clarification_question:
        add(
            "core.clarification_question_hint", "CORE_REASONING_PLAN", "HEURISTIC",
            clarification_question, f"{turn_marker}:core.reasoningPlan.clarificationQuestion",
        )

    evidence_notes = understanding.get("evidence")
    if isinstance(evidence_notes, (list, tuple)):
        for index, note in enumerate(evidence_notes[:_MAX_CORE_HINTS]):
            clean = _text(note)
            if clean:
                add(
                    "core.evidence_note_hint", "CORE_UNDERSTANDING", "HEURISTIC",
                    clean, f"{turn_marker}:core.evidence[{index}]", ordinal=index,
                )

    hints = _candidate_hints(understanding)
    for (candidate, polarity), (feature, _neuron_id) in _CANDIDATE_INPUTS.items():
        active = hints[(candidate, polarity)]
        add(
            feature, "CORE_REASONING_PLAN", "HEURISTIC", active,
            f"{turn_marker}:candidate_signal:{feature}",
            activation=1.0 if active else 0.0,
        )

    for index, memory in enumerate(approved_memories[:_MAX_EXTERNAL_REFERENCES]):
        if not isinstance(memory, Mapping):
            continue
        reference_id = _text(memory.get("id"), 320)
        if not reference_id:
            continue
        category = _text(memory.get("category"), 80)
        add(
            "memory.approved_reference", "APPROVED_MEMORY", "USER_APPROVED_REFERENCE",
            reference_id, f"memory:{reference_id}:category={category}", ordinal=index,
        )

    return tuple(records)


def _neuron_id_for(record: Mapping[str, object]) -> int:
    """Mirror AndroidHybridEvidenceInputAdapter.neuronIdFor exactly."""
    feature = record.get("feature")
    if not isinstance(feature, str):
        raise ValueError("typed evidence feature must be a string")
    for (_candidate, _polarity), (candidate_feature, neuron_id) in _CANDIDATE_INPUTS.items():
        if feature == candidate_feature:
            return neuron_id
    feature_index = _FEATURE_INDEX.get(feature)
    if feature_index is None:
        raise ValueError("feature is not assigned to the hybrid input population")
    record_id = record.get("record_id")
    if not isinstance(record_id, str) or ":" not in record_id:
        raise ValueError("evidence record ID must end in its explicit numeric ordinal")
    ordinal_text = record_id.rsplit(":", 1)[1]
    if not ordinal_text.isdigit():
        raise ValueError("evidence record ordinal is invalid")
    ordinal = int(ordinal_text)
    if not 0 <= ordinal < _MAX_ORDINAL_PER_FEATURE:
        raise ValueError("evidence feature ordinal exceeds its reserved input slots")
    value = record.get("value")
    channel = 1 if isinstance(value, bool) and value else 0
    neuron_id = feature_index * _SLOT_WIDTH_PER_FEATURE + ordinal * 2 + channel
    if not 0 <= neuron_id < 1_056:
        raise ValueError("typed evidence maps outside the Android general-input population")
    return neuron_id


def _candidate_competition(
    readout_counts: Mapping[str, Mapping[str, int]],
) -> tuple[dict[str, float], str | None, float]:
    """Port Android ANS100CandidateCompetition's normalized lateral-inhibition rule."""
    net: dict[str, float] = {}
    for candidate in _CANDIDATES:
        support = readout_counts[candidate]["support"] / 100.0
        conflict = readout_counts[candidate]["conflict"] / 100.0
        net[candidate] = max(-1.0, min(1.0, support - conflict))
    activations: dict[str, float] = {}
    for candidate in _CANDIDATES:
        strongest_competitor = max(
            (max(0.0, score) for other, score in net.items() if other != candidate),
            default=0.0,
        )
        inhibited = net[candidate] - 0.25 * strongest_competitor
        activations[candidate] = max(0.0, min(1.0, inhibited))
    ranked = sorted(activations.items(), key=lambda item: (-item[1], _CANDIDATE_IDS[item[0]]))
    top_value = ranked[0][1]
    runner_up = ranked[1][1] if len(ranked) > 1 else 0.0
    margin = max(0.0, min(1.0, top_value - runner_up))
    unique_top = sum(value == top_value for _candidate, value in ranked) == 1
    winner = ranked[0][0] if top_value >= 0.05 and margin >= 0.05 and unique_top else None
    return activations, winner, margin


class HybridANS100Runtime:
    """Build and run a fresh bounded V4 graph for each desktop chat turn."""

    VERSION = "JARVIS_ANS100_HYBRID_V4"
    TOPOLOGY_ID = V4_TOPOLOGY_ID
    NEURON_COUNT = V4_NEURON_COUNT
    CONNECTION_COUNT = EXPECTED_CONNECTION_COUNT

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
            "neuron_count": V4_NEURON_COUNT,
            "connection_count": self.CONNECTION_COUNT,
            "population_count": len(POPULATIONS),
            "active_in_chat": True,
            "runtime_mode": "fresh_in_memory_graph_per_turn",
            "input_contract": "ANDROID_TYPED_EVIDENCE_V1",
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
        approved_memories: Sequence[Mapping[str, object]] = (),
    ) -> dict[str, object]:
        if not isinstance(prompt, str) or not prompt.strip() or len(prompt) > 900:
            raise ValueError("prompt must contain 1..900 characters")
        if not isinstance(understanding_context, Mapping):
            raise ValueError("understanding_context must be a mapping")
        if reasoning_context is not None and not isinstance(reasoning_context, Mapping):
            raise ValueError("reasoning_context must be a mapping")
        if external_context is not None and not isinstance(external_context, Mapping):
            raise ValueError("external_context must be a mapping")
        if not isinstance(approved_memories, (list, tuple)):
            raise ValueError("approved_memories must be a list or tuple")

        turn_id = uuid.uuid4().hex
        records = _typed_evidence_records(prompt, understanding_context, turn_id, approved_memories)
        if not records or len(records) > _MAX_RECORDS_PER_TURN:
            raise ValueError("typed evidence record budget exceeded")

        all_neuron_ids = [_neuron_id_for(record) for record in records]
        if len(all_neuron_ids) != len(set(all_neuron_ids)):
            raise ValueError("two typed evidence records map to the same ANS-100 input neuron")
        active_records = [record for record in records if float(record["activation"]) > 0.0]
        if len(active_records) > _MAX_ACTIVE_INPUTS_PER_TURN:
            raise ValueError("hybrid input active-event budget exceeded")
        active_ids = validate_input_ids(sorted(_neuron_id_for(record) for record in active_records))
        if len(active_ids) > _MAX_ACTIVE_INPUTS_PER_TURN:
            raise ValueError("hybrid input active-event budget exceeded")

        hints = _candidate_hints(understanding_context)
        network = build_network()
        for index, record in enumerate(active_records):
            neuron_id = _neuron_id_for(record)
            network.schedule_input(
                time_ns=1,
                neuron_id=str(neuron_id),
                synapse_id="external_evidence",
                event_id=f"ans100-v4-{index:02d}",
                # Android uses presence pulses; activationHint is trace metadata,
                # not a probability or a fractional synaptic strength.
                strength=1.0,
                attention=0.0,
            )
        run = network.run(
            max_events=self._max_events,
            max_timestamps=self._max_timestamps,
        )

        spiked_neuron_ids = {int(spike.neuron_id) for spike in run.spikes}
        readout_counts: dict[str, dict[str, int]] = {
            candidate: {"support": 0, "conflict": 0}
            for candidate in _CANDIDATES
        }
        for candidate in _CANDIDATES:
            for polarity in ("support", "conflict"):
                start, end = _READOUT_RANGES[(candidate, polarity)]
                readout_counts[candidate][polarity] = sum(
                    1 for neuron_id in spiked_neuron_ids if start <= neuron_id < end
                )

        population_counts = {
            population.id: sum(1 for neuron_id in spiked_neuron_ids if population.contains(neuron_id))
            for population in POPULATIONS
        }
        active_population_ids = [
            population.id for population in POPULATIONS
            if population_counts[population.id] > 0
        ]
        missing_populations = [
            population.id for population in POPULATIONS
            if population_counts[population.id] == 0
        ]
        readout_mismatch_slots = []
        for candidate in _CANDIDATES:
            for polarity in ("support", "conflict"):
                expected_active = hints[(candidate, polarity)]
                observed_active = readout_counts[candidate][polarity] > 0
                if expected_active != observed_active:
                    readout_mismatch_slots.append(f"{candidate}_{polarity}")

        exhausted = run.budget_exhausted or run.pending_events > 0
        activations, winner, winner_margin = _candidate_competition(readout_counts)
        status = (
            "abstained"
            if exhausted or missing_populations or readout_mismatch_slots
            else "selected" if winner == "answer"
            else "needs_clarification" if winner == "clarification"
            else "abstained"
        )
        if exhausted:
            decision_reason = "compute_budget_or_pending_events_exhausted"
            abstention_reason = "Ограниченный вычислительный бюджет исчерпан; обработка запроса не завершена."
        elif missing_populations:
            decision_reason = "required_population_not_reached"
            abstention_reason = "Не подтверждён полный проход по группам ANS-100: " + ", ".join(missing_populations)
        elif readout_mismatch_slots:
            decision_reason = "candidate_readout_does_not_match_typed_input"
            abstention_reason = "Не получен ожидаемый readout для кандидатов: " + ", ".join(readout_mismatch_slots)
        elif status == "needs_clarification":
            plan = understanding_context.get("reasoning_plan")
            question = plan.get("clarification_question") if isinstance(plan, Mapping) else ""
            decision_reason = "clarification_candidate_won"
            abstention_reason = _text(question) or "Нужно уточнить недостающий контекст."
        elif winner == "abstain":
            decision_reason = "abstain_candidate_won"
            abstention_reason = "Нейронный readout поддержал воздержание: недостаточно оснований для прямого ответа."
        elif winner == "answer":
            decision_reason = "answer_candidate_won"
            abstention_reason = None
        else:
            decision_reason = "candidate_tie_or_no_positive_activation"
            abstention_reason = "Нейронные readout-кандидаты дали ничью или не получили положительной поддержки."

        selected_label = {
            "selected": "answer",
            "needs_clarification": "clarification",
            "abstained": "abstain",
        }[status]
        selected_id = _CANDIDATE_IDS[selected_label] if status == "selected" else None
        readout_spike_total = sum(
            counts["support"] + counts["conflict"]
            for counts in readout_counts.values()
        )
        readout_complete = not exhausted and not missing_populations and not readout_mismatch_slots
        uncertainty = ["uncalibrated_model_output"]
        if missing_populations or readout_mismatch_slots:
            uncertainty.append("missing_evidence")
        if hints[("clarification", "support")]:
            uncertainty.append("ambiguous_request")
        if exhausted:
            uncertainty.append("budget_exhausted")
        if winner is None:
            uncertainty.append("conflicting_evidence")

        plan = understanding_context.get("reasoning_plan")
        plan = plan if isinstance(plan, Mapping) else {}
        response_goal = (
            _text(plan.get("goal"), 512) or "Ответить на исходное сообщение пользователя"
            if status == "selected"
            else _text(plan.get("clarification_question"), 512) or "Запросить недостающий контекст"
            if status == "needs_clarification"
            else "Не формировать неподтверждённый ответ"
        )

        return {
            "runtime_version": self.VERSION,
            "topology_id": self.TOPOLOGY_ID,
            "topology_version": 4,
            "neuron_count": V4_NEURON_COUNT,
            "connection_count": self.CONNECTION_COUNT,
            "evidence_schema": "JARVIS_TYPED_EVIDENCE_V1",
            "evidence_record_count": len(records),
            "input_neuron_count": len(active_ids),
            "processed_events": run.processed_events,
            "processed_batches": run.processed_batches,
            "spike_count": len(run.spikes),
            "unique_spiking_neurons": len(spiked_neuron_ids),
            "active_population_ids": active_population_ids,
            "population_spike_counts": population_counts,
            "missing_populations": missing_populations,
            "active_candidate_lanes": [
                f"{candidate}_{polarity}" for (candidate, polarity), active in hints.items() if active
            ],
            "candidate_hints": {
                f"{candidate}_{polarity}": active
                for (candidate, polarity), active in hints.items()
            },
            "readout_spike_counts": readout_counts,
            "readout_spike_total": readout_spike_total,
            "readout_mismatch_slots": readout_mismatch_slots,
            "readout_complete": readout_complete,
            "candidate_signal_scores": activations,
            "winner_candidate_id": _CANDIDATE_IDS[winner] if winner is not None else None,
            "winner_margin": winner_margin,
            "selected_candidate": selected_label,
            "selected_candidate_id": selected_id,
            "decision_status": status,
            "decision_reason": decision_reason,
            "abstention_reason": abstention_reason,
            "uncertainty": uncertainty,
            "response_goal": response_goal,
            "pending_events_at_budget_boundary": run.pending_events,
            "turn_boundary_events_discarded": run.pending_events > 0,
            "event_budget_exhausted": run.budget_exhausted,
            "compute_budget_exhausted": exhausted,
            "learning_enabled": False,
            "persistent_state": False,
            "decision_authority": "advisory_only",
            "scope": "bounded_structural_signal_routing_not_semantic_reasoning",
        }

    def close(self) -> None:
        return None
