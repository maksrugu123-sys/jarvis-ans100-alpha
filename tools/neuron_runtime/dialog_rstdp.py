"""Persistent explicit-feedback ALIF-DT/R-STDP dialogue readout experiment.

Four output neurons classify four bounded intent labels. Hashed text features
arrive before a separate decision pulse, creating a temporal eligibility trace.
Only an explicit user label produces a reward. Raw utterances are never stored.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
from pathlib import Path
import re
import threading
import unicodedata
import uuid
from typing import Any

from tools.jpvr_reference.storage import JPVRStorage
from tools.numerical_oracle.jarvis_oracle import NeuronParams, SynapseState
from .network import NetworkRuntime
from .network_jnvm_coordinator import NetworkJNVMCoordinator, NetworkInput, NetworkRewardInput
from .network_jnvm_store import JPVRNetworkJNVMStore
from .reward_baseline import RewardBaselineManager
from .reward_baseline_store import JPVRRewardBaselineNotFound, JPVRRewardBaselineStore
from .runtime import NeuronRuntime

LABELS = ("greeting", "question", "thanks", "farewell")
_NETWORK_ID = "dialog-rstdp-network-v1"
_BASELINE_MANAGER_ID = "dialog-rstdp-baselines-v1"
_FEATURE_COUNT = 256
_MAX_ACTIVE_FEATURES = 40
_MAX_TEXT_CHARS = 900
_MAX_TOKENS = 128
_FEATURE_WEIGHT_INITIAL = 0.5
_FEATURE_INPUT_SCALE = 0.001
_FEATURE_THRESHOLD = 0.1
_FEATURE_STEP_NS = 10_000_000
_DECISION_DELAY_NS = 1_000_000
_REWARD_DELAY_NS = 1_000_000
_MAX_EVENTS_PER_TURN = 1024
_U64_MAX = (1 << 64) - 1
_TOKEN_RE = re.compile(r"[^\W_]+", re.UNICODE)


class DialogRstdpError(ValueError):
    """Invalid input, failed learning invariant, or incompatible checkpoint."""


def _feature_synapse_id(index: int) -> str:
    return f"f{index:04d}"


def _extract_features(text: str) -> dict[int, float]:
    if not isinstance(text, str) or not text.strip() or len(text) > _MAX_TEXT_CHARS or "\x00" in text:
        raise DialogRstdpError(f"text must contain 1..{_MAX_TEXT_CHARS} non-NUL characters")
    tokens = _TOKEN_RE.findall(unicodedata.normalize("NFKC", text).casefold())[:_MAX_TOKENS]
    if not tokens:
        raise DialogRstdpError("text contains no usable lexical features")
    raw = [f"w:{token}" for token in tokens]
    raw.extend(f"b:{left}_{right}" for left, right in zip(tokens, tokens[1:]))
    counts: dict[int, float] = {}
    for feature in raw:
        digest = hashlib.blake2b(feature.encode("utf-8"), digest_size=8).digest()
        index = int.from_bytes(digest, "big") % _FEATURE_COUNT
        counts[index] = counts.get(index, 0.0) + 1.0
    bounded = sorted(counts.items(), key=lambda pair: (-pair[1], pair[0]))[:_MAX_ACTIVE_FEATURES]
    norm = math.sqrt(sum(value * value for _, value in bounded))
    if norm <= 0.0 or not math.isfinite(norm):
        raise DialogRstdpError("feature normalization failed")
    return {index: value / norm for index, value in sorted(bounded)}


def _make_network() -> NetworkRuntime:
    params = NeuronParams(
        theta_0=_FEATURE_THRESHOLD,
        theta_min=_FEATURE_THRESHOLD,
        theta_max=_FEATURE_THRESHOLD,
        delta_a_spike=0.0,
    )
    neurons = {}
    for label in LABELS:
        synapses = {
            _feature_synapse_id(index): SynapseState(weight=_FEATURE_WEIGHT_INITIAL)
            for index in range(_FEATURE_COUNT)
        }
        synapses["drive"] = SynapseState(weight=1.0)
        neurons[f"dlg_{label}"] = NeuronRuntime(
            synapses,
            neuron_params=params,
            learning_rule="rstdp",
        )
    return NetworkRuntime(neurons, (), max_pending_events=4096, max_events_per_run=4096)


class DialogRstdpLearningService:
    """Small JPVR-backed R-STDP readout trained only by explicit user labels."""

    def __init__(self, data_directory: str | os.PathLike[str]) -> None:
        self.directory = Path(data_directory).expanduser().absolute()
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        try:
            os.chmod(self.directory, 0o700)
        except OSError:
            pass
        self.network_path = self.directory / "dialog-rstdp-network.jpvr"
        self.baseline_path = self.directory / "dialog-rstdp-baselines.jpvr"
        self._lock = threading.RLock()
        self._closed = False
        self._network_storage = None
        self._baseline_storage = None
        try:
            self._open_network()
            self._open_baselines()
            self._recover_pending_feedback()
            self._recover_incomplete_turn()
        except Exception:
            self._close_stores()
            raise

    def _open_network(self) -> None:
        created = not self.network_path.exists()
        storage = JPVRStorage.create(self.network_path) if created else JPVRStorage.open(self.network_path)
        store = JPVRNetworkJNVMStore(storage)
        try:
            if created:
                epoch = uuid.uuid4().bytes
                runtime = _make_network()
                store.save_timestamp(
                    _NETWORK_ID,
                    runtime,
                    runtime.neuron_ids,
                    clock_epoch_id=epoch,
                    expected_network_record_version=0,
                    expected_neuron_record_versions={item: 0 for item in runtime.neuron_ids},
                )
            restored = store.restore_network(_NETWORK_ID)
            self._network_storage = storage
            self._network_store = store
            self._clock_epoch_id = bytes(restored.clock_epoch_id)
            self._coordinator = NetworkJNVMCoordinator(store, restored)
        except Exception:
            storage.close()
            raise

    def _open_baselines(self) -> None:
        created = not self.baseline_path.exists()
        storage = JPVRStorage.create(self.baseline_path) if created else JPVRStorage.open(self.baseline_path)
        store = JPVRRewardBaselineStore(storage)
        try:
            if created:
                manager = RewardBaselineManager(
                    manager_id=_BASELINE_MANAGER_ID,
                    clock_epoch_id=self._clock_epoch_id,
                    initial_baseline=0.5,
                    beta_b=0.01,
                    max_tasks=16,
                    history_limit=128,
                )
                saved = store.save(manager, expected_record_version=0)
                version = saved.record_version
            else:
                try:
                    loaded = store.load(_BASELINE_MANAGER_ID)
                except JPVRRewardBaselineNotFound:
                    manager = RewardBaselineManager(
                        manager_id=_BASELINE_MANAGER_ID,
                        clock_epoch_id=self._clock_epoch_id,
                        initial_baseline=0.5,
                        beta_b=0.01,
                        max_tasks=16,
                        history_limit=128,
                    )
                    saved = store.save(manager, expected_record_version=0)
                    version = saved.record_version
                else:
                    manager, version = loaded.manager, loaded.descriptor.record_version
            if manager.clock_epoch_id != self._clock_epoch_id:
                raise DialogRstdpError("reward baseline and neural network clock epochs do not match")
            self._baseline_storage = storage
            self._baseline_store = store
            self._baseline_manager = manager
            self._baseline_record_version = version
        except Exception:
            storage.close()
            raise

    def _reopen_network(self) -> None:
        self._network_storage.close()
        storage = JPVRStorage.open(self.network_path, recover_tail=False)
        store = JPVRNetworkJNVMStore(storage)
        restored = store.restore_network(_NETWORK_ID)
        self._network_storage = storage
        self._network_store = store
        self._coordinator = NetworkJNVMCoordinator(store, restored)
        self._clock_epoch_id = bytes(restored.clock_epoch_id)

    def _reload_baselines(self) -> None:
        self._baseline_storage.close()
        storage = JPVRStorage.open(self.baseline_path, recover_tail=False)
        store = JPVRRewardBaselineStore(storage)
        loaded = store.load(_BASELINE_MANAGER_ID)
        if loaded.manager.clock_epoch_id != self._clock_epoch_id:
            storage.close()
            raise DialogRstdpError("reward baseline and neural network clock epochs do not match")
        self._baseline_storage = storage
        self._baseline_store = store
        self._baseline_manager = loaded.manager
        self._baseline_record_version = loaded.descriptor.record_version

    def _ensure_current(self) -> None:
        if self._closed:
            raise DialogRstdpError("dialog R-STDP service is closed")
        status = self._coordinator.status()
        if status.frozen or status.stale:
            self._reopen_network()
        self._reload_baselines()

    def _close_stores(self) -> None:
        for store in (self._network_storage, self._baseline_storage):
            if store is not None:
                try:
                    store.close()
                except Exception:
                    pass
        self._network_storage = None
        self._baseline_storage = None

    def _task_id(self, label: str) -> str:
        return f"dialog-{label}"

    def _save_baselines(self) -> None:
        saved = self._baseline_store.save(
            self._baseline_manager,
            expected_record_version=self._baseline_record_version,
        )
        self._baseline_record_version = saved.record_version

    @staticmethod
    def _targets(label: str, feature_ids: list[int]) -> list[dict[str, str]]:
        neuron_id = f"dlg_{label}"
        targets = [
            {"neuron_id": neuron_id, "synapse_id": _feature_synapse_id(index)}
            for index in feature_ids
        ]
        targets.append({"neuron_id": neuron_id, "synapse_id": "drive"})
        return sorted(targets, key=lambda item: (item["neuron_id"], item["synapse_id"]))

    @staticmethod
    def _feedback_envelope(
        label: str,
        feedback_id: str,
        sequence: int,
        reward: float,
        model_time_ns: int,
        epoch: bytes,
        context_id: str,
        targets: list[dict[str, str]],
    ) -> tuple[str, str]:
        task_id = f"dialog-{label}"
        payload = {
            "reward_task_id": task_id,
            "feedback_id": feedback_id,
            "feedback_sequence": sequence,
            "reward_value": reward,
            "model_time_ns": model_time_ns,
            "clock_epoch_id": epoch.hex(),
            "learning_context_id": context_id,
            "targets": targets,
        }
        context = {"learning_context_id": context_id, "targets": targets}
        context_bytes = json.dumps(
            context, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
        ).encode("utf-8")
        envelope = {
            "protocol_version": 1,
            "message_id": str(uuid.uuid4()),
            "event_id": str(uuid.uuid4()),
            "message_type": "EVENT",
            "schema_id": "jarvis.reward.feedback",
            "schema_version": 1,
            "source_module_id": "dialog-rstdp",
            "target_module_id": "neuron-runtime",
            "commit_id": None,
            "operation_id": None,
            "correlation_id": feedback_id,
            "causation_id": context_id,
            "idempotency_key": feedback_id,
            "expected_state_version": None,
            "deadline_budget_ms": 30_000,
            "requested_priority": "P2",
            "capability_id": "dialog.reward",
            "state_version": None,
            "payload": payload,
            "policy_version": 1,
            "authorization_receipt_id": str(uuid.uuid4()),
        }
        encoded = json.dumps(
            envelope, ensure_ascii=False, allow_nan=False, sort_keys=True, separators=(",", ":")
        )
        return encoded, hashlib.sha256(context_bytes).hexdigest()

    @staticmethod
    def _scores(runtime: NetworkRuntime, features: dict[int, float]) -> dict[str, float]:
        scores = {}
        for label in LABELS:
            synapses = runtime.synapse_states(f"dlg_{label}")
            scores[label] = sum(
                synapses[_feature_synapse_id(index)].weight * value
                for index, value in features.items()
            )
        return scores

    @staticmethod
    def _choose(scores: dict[str, float], trained: int) -> tuple[str | None, float]:
        ranked = sorted(scores, key=lambda label: (-scores[label], label))
        margin = scores[ranked[0]] - scores[ranked[1]]
        return (ranked[0] if trained > 0 and margin >= 0.00001 else None), margin

    def _inputs(self, features: dict[int, float], turn_hex: str, start_ns: int) -> list[NetworkInput]:
        prefix = f"dialog-{turn_hex}"
        decision_ns = start_ns + _DECISION_DELAY_NS
        inputs = []
        for label in LABELS:
            neuron_id = f"dlg_{label}"
            for index, value in features.items():
                inputs.append(NetworkInput(
                    start_ns, neuron_id, _feature_synapse_id(index),
                    f"{prefix}-{label}-f{index:04d}",
                    value * _FEATURE_INPUT_SCALE, 0.0,
                ))
            # Prime the drive synapse before the later pulse to create a real
            # pre-before-post interval for the R-STDP eligibility trace.
            inputs.append(NetworkInput(
                start_ns, neuron_id, "drive",
                f"{prefix}-{label}-drive-trace", 0.00001, 0.0,
            ))
            inputs.append(NetworkInput(
                decision_ns, neuron_id, "drive",
                f"{prefix}-{label}-drive-pulse", 1.0, 0.0,
            ))
        return inputs

    def _run_candidate(self, runtime: NetworkRuntime, features: dict[int, float], turn_hex: str, start_ns: int):
        for item in self._inputs(features, turn_hex, start_ns):
            runtime.schedule_input(
                item.time_ns, item.neuron_id, item.synapse_id,
                item.event_id, item.strength, item.attention,
            )
        result = runtime.run(
            learning_context_id=f"dialog-{turn_hex}",
            max_events=_MAX_EVENTS_PER_TURN,
            max_timestamps=2,
        )
        spikes = tuple(sorted({spike.neuron_id for spike in result.spikes}))
        return runtime, spikes

    def _pending_proposals(self):
        return [
            (label, record.pending)
            for label in LABELS
            if (record := self._baseline_manager.get_record(self._task_id(label))).pending is not None
        ]

    def _recover_pending_feedback(self) -> None:
        pending = self._pending_proposals()
        if not pending:
            return
        if len(pending) != len(LABELS):
            raise DialogRstdpError("incomplete multi-label reward reservation; refusing partial recovery")
        parsed = []
        context_ids, model_times = set(), set()
        for label, proposal in pending:
            if proposal.cqe_envelope_json is None:
                raise DialogRstdpError("pending reward has no durable target envelope")
            payload = json.loads(proposal.cqe_envelope_json)["payload"]
            if payload["reward_task_id"] != self._task_id(label):
                raise DialogRstdpError("pending reward task identity mismatch")
            context_id, targets = payload["learning_context_id"], payload["targets"]
            context_ids.add(context_id)
            model_times.add(proposal.model_time_ns)
            parsed.append((label, proposal, context_id, targets))
        if len(context_ids) != 1 or len(model_times) != 1:
            raise DialogRstdpError("pending proposals do not describe one atomic dialogue turn")
        context_id = next(iter(context_ids))
        runtime = self._coordinator.runtime_snapshot()
        contexts = [
            runtime.synapse_states(target["neuron_id"])[target["synapse_id"]].eligibility_context_id
            for _, _, _, targets in parsed
            for target in targets
        ]
        if contexts and all(value == context_id for value in contexts):
            rewards = []
            for _, proposal, _, targets in parsed:
                for target in targets:
                    value = proposal.baseline_before if target["synapse_id"] == "drive" else proposal.reward_value
                    rewards.append(NetworkRewardInput(
                        target["neuron_id"], target["synapse_id"], proposal.model_time_ns,
                        value, proposal.baseline_before, context_id,
                    ))
            self._coordinator.apply_rewards_batch(rewards)
        elif contexts and all(value is None for value in contexts):
            if self._coordinator.status().current_time_ns < next(iter(model_times)):
                raise DialogRstdpError("reward context disappeared before the recorded model timestamp")
        else:
            raise DialogRstdpError("pending reward contexts are mixed; refusing a non-idempotent retry")
        for _, proposal in pending:
            self._baseline_manager.commit_feedback(proposal, processing_status="APPLIED")
        self._save_baselines()

    def _recover_incomplete_turn(self) -> None:
        runtime = self._coordinator.runtime_snapshot()
        checkpoint = runtime.export_checkpoint_state()
        contexts = {
            synapse.eligibility_context_id
            for neuron_id in runtime.neuron_ids
            for synapse in runtime.synapse_states(neuron_id).values()
            if synapse.eligibility_context_id is not None
        }
        for event in checkpoint["pending_events"]:
            match = re.match(r"^(dialog-[0-9a-f]{32})-", event.event_id)
            if match:
                contexts.add(match.group(1))
            else:
                raise DialogRstdpError("orphaned pending event is outside the dialogue-learning namespace")
        if not contexts:
            return
        if len(contexts) != 1:
            raise DialogRstdpError("multiple unfinished dialogue contexts are present")
        context_id = next(iter(contexts))
        while self._coordinator.status().pending_events:
            result = self._coordinator.run_next_timestamp(
                learning_context_id=context_id,
                max_events=_MAX_EVENTS_PER_TURN,
            )
            if result.run.processed_events == 0:
                raise DialogRstdpError("unfinished dialogue event queue cannot make progress")
        runtime = self._coordinator.runtime_snapshot()
        reward_time = self._coordinator.status().current_time_ns + _REWARD_DELAY_NS
        neutral = []
        for label in LABELS:
            neuron_id = f"dlg_{label}"
            baseline = self._baseline_manager.get_baseline(self._task_id(label))
            for synapse_id, synapse in runtime.synapse_states(neuron_id).items():
                if synapse.eligibility_context_id == context_id:
                    neutral.append(NetworkRewardInput(
                        neuron_id, synapse_id, reward_time, baseline, baseline, context_id,
                    ))
        if neutral:
            self._coordinator.apply_rewards_batch(neutral)

    def _trained_count(self) -> int:
        return min(
            self._baseline_manager.get_record(self._task_id(label)).last_committed_feedback_sequence
            for label in LABELS
        )

    def analyze(self, text: str) -> dict[str, Any]:
        with self._lock:
            self._ensure_current()
            self._recover_pending_feedback()
            self._recover_incomplete_turn()
            features = _extract_features(text)
            runtime = self._coordinator.runtime_snapshot()
            turn_id = uuid.uuid4().hex
            start_ns = runtime.current_time_ns + _FEATURE_STEP_NS
            if start_ns > _U64_MAX - _DECISION_DELAY_NS:
                raise DialogRstdpError("model clock is near its unsigned 64-bit limit")
            candidate, spikes = self._run_candidate(runtime, features, turn_id, start_ns)
            scores = self._scores(candidate, features)
            predicted, margin = self._choose(scores, self._trained_count())
            return {
                "predicted_label": predicted,
                "scores": scores,
                "confidence_margin": margin,
                "active_feature_count": len(features),
                "output_spikes": list(spikes),
                "trained_examples": self._trained_count(),
                "feature_neurons": _FEATURE_COUNT,
                "output_neurons": len(LABELS),
                "learning": "explicit_user_label_rstdp_only",
                "raw_utterances_persisted": False,
            }

    def learn(self, text: str, expected_label: str, feedback_id: str | None = None) -> dict[str, Any]:
        if expected_label not in LABELS:
            raise DialogRstdpError("expected_label is not a supported dialogue label")
        feedback_id = feedback_id or str(uuid.uuid4())
        try:
            parsed = str(uuid.UUID(feedback_id))
        except (ValueError, AttributeError, TypeError) as exc:
            raise DialogRstdpError("feedback_id must be a canonical UUID") from exc
        if parsed != feedback_id:
            raise DialogRstdpError("feedback_id must be a lowercase canonical UUID")
        features = _extract_features(text)
        with self._lock:
            self._ensure_current()
            self._recover_pending_feedback()
            self._recover_incomplete_turn()
            seen = [
                any(item.feedback_id == feedback_id for item in self._baseline_manager.get_record(
                    self._task_id(label)
                ).history)
                for label in LABELS
            ]
            if any(seen):
                if not all(seen):
                    raise DialogRstdpError("feedback ID appears in only part of the reward ledger")
                runtime = self._coordinator.runtime_snapshot()
                scores = self._scores(runtime, features)
                predicted, margin = self._choose(scores, self._trained_count())
                return {
                    "status": "DUPLICATE",
                    "feedback_id": feedback_id,
                    "expected_label": expected_label,
                    "predicted_label": predicted,
                    "scores": scores,
                    "confidence_margin": margin,
                    "raw_utterances_persisted": False,
                }

            before = self._scores(self._coordinator.runtime_snapshot(), features)
            start_ns = self._coordinator.status().current_time_ns + _FEATURE_STEP_NS
            if start_ns > _U64_MAX - (_DECISION_DELAY_NS + _REWARD_DELAY_NS):
                raise DialogRstdpError("model clock is near its unsigned 64-bit limit")
            turn_id = uuid.uuid4().hex
            context_id = f"dialog-{turn_id}"
            self._coordinator.schedule_inputs(self._inputs(features, turn_id, start_ns))
            self._coordinator.run_next_timestamp(
                learning_context_id=context_id, max_events=_MAX_EVENTS_PER_TURN,
            )
            second = self._coordinator.run_next_timestamp(
                learning_context_id=context_id, max_events=_MAX_EVENTS_PER_TURN,
            )
            spikes = {item.neuron_id for item in second.run.spikes}
            expected_spikes = {f"dlg_{label}" for label in LABELS}
            if spikes != expected_spikes or self._coordinator.status().pending_events:
                raise DialogRstdpError("decision pulse did not activate every output neuron")

            reward_time = self._coordinator.status().current_time_ns + _REWARD_DELAY_NS
            proposals = []
            for label in LABELS:
                task_id = self._task_id(label)
                record = self._baseline_manager.get_record(task_id)
                sequence = record.last_committed_feedback_sequence + 1
                reward = 1.0 if label == expected_label else 0.0
                targets = self._targets(label, list(features))
                envelope, digest = self._feedback_envelope(
                    label, feedback_id, sequence, reward, reward_time,
                    self._clock_epoch_id, context_id, targets,
                )
                proposal = self._baseline_manager.prepare_feedback(
                    task_id, feedback_id, sequence, reward, reward_time,
                    clock_epoch_id=self._clock_epoch_id,
                    cqe_envelope_json=envelope,
                    feedback_context_digest=digest,
                )
                proposals.append((label, proposal))
            # Persist PREPARED feedback before the one atomic neural reward COMMIT.
            self._save_baselines()

            rewards = []
            for label, proposal in proposals:
                for target in self._targets(label, list(features)):
                    value = proposal.baseline_before if target["synapse_id"] == "drive" else proposal.reward_value
                    rewards.append(NetworkRewardInput(
                        target["neuron_id"], target["synapse_id"], reward_time,
                        value, proposal.baseline_before, context_id,
                    ))
            batch = self._coordinator.apply_rewards_batch(rewards)
            for _, proposal in proposals:
                self._baseline_manager.commit_feedback(proposal, processing_status="APPLIED")
            self._save_baselines()

            after = self._scores(self._coordinator.runtime_snapshot(), features)
            predicted, margin = self._choose(after, self._trained_count())
            return {
                "status": "APPLIED",
                "feedback_id": feedback_id,
                "expected_label": expected_label,
                "predicted_label": predicted,
                "scores_before": before,
                "scores_after": after,
                "confidence_margin": margin,
                "feature_count": len(features),
                "output_spikes": sorted(spikes),
                "network_commit_id": batch.commit.commit_id.hex(),
                "reward_baseline_version": self._baseline_record_version,
                "raw_utterances_persisted": False,
            }

    def status(self) -> dict[str, Any]:
        with self._lock:
            self._ensure_current()
            self._recover_pending_feedback()
            self._recover_incomplete_turn()
            return {
                "state": "experimental_sparse_dialogue_rstdp",
                "input_feature_neurons": _FEATURE_COUNT,
                "output_spiking_neurons": len(LABELS),
                "labels": list(LABELS),
                "trained_examples_per_label": {
                    label: self._baseline_manager.get_record(self._task_id(label)).last_committed_feedback_sequence
                    for label in LABELS
                },
                "network_record_version": self._coordinator.status().network_record_version,
                "baseline_record_version": self._baseline_record_version,
                "learning": "explicit_user_label_rstdp_only",
                "raw_utterances_persisted": False,
            }

    def _close_stores(self) -> None:
        for store in (self._network_storage, self._baseline_storage):
            if store is not None:
                try:
                    store.close()
                except Exception:
                    pass
        self._network_storage = None
        self._baseline_storage = None

    def close(self) -> None:
        with self._lock:
            if self._closed:
                return
            self._close_stores()
            self._closed = True
