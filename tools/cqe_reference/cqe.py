"""Executable CQE v1 contract reference for JARVIS (not the production runtime).

This module intentionally uses only the Python standard library. Its in-memory
commit coordinator is volatile and MUST NOT be mistaken for JPVR durability.
"""
from __future__ import annotations

from collections import deque
from dataclasses import dataclass, field, replace
from enum import Enum
import math
import json
import time
import uuid
from typing import Any, Callable, Deque, Mapping, Protocol


PROTOCOL_VERSION = 1
MAX_ENVELOPE_BYTES = 8 * 1024
MAX_INLINE_PAYLOAD_BYTES = 4 * 1024
MAX_DESCRIPTOR_BYTES = 1024
MAX_DESCRIPTORS = 16
MAX_QUERY_RESULT_BYTES = 16 * 1024
MAX_QUEUE_TOTAL = 256
QUEUE_QUOTAS = {"P0": 16, "P1": 16, "P2": 64, "P3": 96, "P4": 64}
MAX_PER_SOURCE = 32
MAX_OUTBOX_EVENTS = 256
ORDINARY_OUTBOX_LIMIT = 240  # Keep 16 slots reserved for future P0/P1 deliveries.
MAX_OUTBOX_BYTES = 1024 * 1024
MAX_IDEMPOTENCY_RECORDS = 4096
MAX_EVENT_INBOX_RECORDS = 4096
MAX_MESSAGE_ID_HISTORY = 4096


class MessageType(str, Enum):
    COMMAND = "COMMAND"
    QUERY = "QUERY"
    EVENT = "EVENT"


class Priority(str, Enum):
    P0 = "P0"
    P1 = "P1"
    P2 = "P2"
    P3 = "P3"
    P4 = "P4"


_PRIORITY_ORDER = (Priority.P0, Priority.P1, Priority.P2, Priority.P3, Priority.P4)
_PRIORITY_RANK = {priority: index for index, priority in enumerate(_PRIORITY_ORDER)}


@dataclass(frozen=True)
class MessageEnvelope:
    protocol_version: int
    message_id: str
    message_type: MessageType
    schema_id: str
    schema_version: int
    source_module_id: str
    payload: Mapping[str, Any]
    target_module_id: str | None = None
    event_id: str | None = None
    commit_id: str | None = None
    operation_id: str | None = None
    correlation_id: str | None = None
    causation_id: str | None = None
    idempotency_key: str | None = None
    expected_state_version: int | None = None
    deadline_budget_ms: int | None = None
    requested_priority: Priority | None = None
    capability_id: str | None = None
    state_version: int | None = None


@dataclass(frozen=True)
class Capability:
    capability_id: str
    subject_module_id: str
    allowed_operations: frozenset[str]
    max_priority: Priority
    expires_at_monotonic: float
    policy_version: int


@dataclass(frozen=True)
class EventSpec:
    event_id: str
    schema_id: str
    schema_version: int
    payload: Mapping[str, Any]


@dataclass(frozen=True)
class CommandProposal:
    domain_id: str
    expected_state_version: int
    new_state: Mapping[str, Any]
    requires_durable: bool = False
    events: tuple[EventSpec, ...] = ()


@dataclass(frozen=True)
class CommitReceipt:
    commit_id: str
    state_version: int
    durable: bool


@dataclass(frozen=True)
class OperationResult:
    operation_id: str
    status: str
    code: str | None = None
    value: Any = None
    commit_id: str | None = None
    state_version: int | None = None
    event_ids: tuple[str, ...] = ()


@dataclass(frozen=True)
class SubmissionResult:
    status: str
    code: str | None = None
    operation_id: str | None = None
    prior_result: OperationResult | None = None


@dataclass(frozen=True)
class ModuleSpec:
    module_id: str
    supported_schemas: Mapping[str, frozenset[int]]
    command_handlers: Mapping[str, Callable[[MessageEnvelope], CommandProposal]] = field(default_factory=dict)
    query_handlers: Mapping[str, Callable[[MessageEnvelope], Any]] = field(default_factory=dict)
    event_handlers: Mapping[str, Callable[[MessageEnvelope], None]] = field(default_factory=dict)
    command_priorities: Mapping[str, Priority] = field(default_factory=dict)
    command_deadline_caps_ms: Mapping[str, int] = field(default_factory=dict)
    query_deadline_cap_ms: int = 1_000
    event_deadline_cap_ms: int = 5_000
    published_event_schemas: frozenset[str] = frozenset()


class CQEError(Exception):
    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code


class VersionConflict(CQEError):
    def __init__(self, message: str = "state version conflict"):
        super().__init__("VERSION_CONFLICT", message)


class DurabilityUnavailable(CQEError):
    def __init__(self, message: str = "durable commit backend is unavailable"):
        super().__init__("MODULE_UNAVAILABLE", message)


class CommitStore(Protocol):
    def commit(self, proposal: CommandProposal, operation_id: str) -> CommitReceipt: ...


class InMemoryCommitCoordinator:
    """Volatile reference coordinator; refuses operations requiring durability."""

    def __init__(self) -> None:
        self._domains: dict[str, tuple[int, dict[str, Any]]] = {}

    def register_domain(self, domain_id: str, initial_state: Mapping[str, Any], version: int = 0) -> None:
        if not domain_id or domain_id in self._domains or version < 0:
            raise ValueError("invalid or duplicate domain")
        self._domains[domain_id] = (version, _json_copy(dict(initial_state)))

    def read(self, domain_id: str) -> tuple[int, dict[str, Any]]:
        if domain_id not in self._domains:
            raise KeyError(domain_id)
        version, state = self._domains[domain_id]
        return version, _json_copy(state)

    def commit(self, proposal: CommandProposal, operation_id: str) -> CommitReceipt:
        if proposal.requires_durable:
            raise DurabilityUnavailable("JPVR durable commit adapter has not been implemented")
        if proposal.domain_id not in self._domains:
            raise CQEError("INVALID_MESSAGE", "unknown state domain")
        current_version, _ = self._domains[proposal.domain_id]
        if current_version != proposal.expected_state_version:
            raise VersionConflict()
        new_state = _json_copy(dict(proposal.new_state))
        next_version = current_version + 1
        self._domains[proposal.domain_id] = (next_version, new_state)
        return CommitReceipt(str(uuid.uuid4()), next_version, durable=False)


def _json_bytes(value: Any) -> bytes:
    try:
        encoded = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":"))
        return encoded.encode("utf-8")
    except (TypeError, ValueError, UnicodeEncodeError) as exc:
        raise CQEError("INVALID_MESSAGE", "payload must contain finite JSON values only") from exc


def _json_copy(value: Any) -> Any:
    return json.loads(_json_bytes(value).decode("utf-8"))


def _is_uuid(value: str | None) -> bool:
    if not isinstance(value, str):
        return False
    try:
        uuid.UUID(value)
        return True
    except (ValueError, AttributeError, TypeError):
        return False


@dataclass
class _Queued:
    envelope: MessageEnvelope
    priority: Priority
    received_at: float


class NCCDispatcher:
    """Single-threaded bounded dispatcher implementing the in-process CQE core."""

    def __init__(
        self,
        commit_store: CommitStore,
        *,
        policy_version: int = 1,
        system_modules: frozenset[str] = frozenset({"ncc", "recovery-manager"}),
        clock: Callable[[], float] = time.monotonic,
    ) -> None:
        self._commit_store = commit_store
        self._policy_version = policy_version
        self._system_modules = system_modules
        self._clock = clock
        self._modules: dict[str, ModuleSpec] = {}
        self._capabilities: dict[str, Capability] = {}
        self._queues: dict[Priority, Deque[_Queued]] = {p: deque() for p in _PRIORITY_ORDER}
        self._queued_total = 0
        self._source_counts: dict[str, int] = {}
        self._message_ids: set[str] = set()  # queued IDs plus a bounded recent-completion window
        self._pending_message_ids: set[str] = set()
        self._completed_message_ids: Deque[str] = deque()
        self._idempotency: dict[tuple[str, str], str] = {}
        self._results: dict[str, OperationResult] = {}
        self._published_events: Deque[MessageEnvelope] = deque(maxlen=MAX_MESSAGE_ID_HISTORY)
        self._event_inbox: set[tuple[str, str]] = set()
        self._outbox_bytes = 0
        self._outbox: Deque[tuple[MessageEnvelope, ModuleSpec, int]] = deque()

    @property
    def queued_count(self) -> int:
        return self._queued_total

    @property
    def queue_counts(self) -> dict[str, int]:
        return {p.value: len(q) for p, q in self._queues.items()}

    @property
    def published_events(self) -> tuple[MessageEnvelope, ...]:
        return tuple(self._published_events)

    @property
    def outbox_bytes(self) -> int:
        return self._outbox_bytes

    @property
    def pending_outbox_count(self) -> int:
        return len(self._outbox)

    def register_module(self, spec: ModuleSpec) -> None:
        if not spec.module_id or spec.module_id in self._modules:
            raise ValueError("invalid or duplicate module id")
        self._modules[spec.module_id] = spec

    def register_capability(self, capability: Capability) -> None:
        if (not capability.capability_id or capability.policy_version != self._policy_version
                or not math.isfinite(capability.expires_at_monotonic)):
            raise ValueError("invalid capability or policy version")
        self._capabilities[capability.capability_id] = capability

    def submit(self, message: MessageEnvelope) -> SubmissionResult:
        try:
            priority = self._validate_and_authorize(message)
            # Freeze the queued payload as a JSON snapshot; callers retain no mutable alias.
            message = replace(message, payload=_json_copy(dict(message.payload)))
        except CQEError as exc:
            return SubmissionResult("REJECTED", exc.code, message.operation_id)

        if message.message_type is MessageType.COMMAND:
            idem_key = (message.source_module_id, message.idempotency_key or "")
            prior_operation = self._idempotency.get(idem_key)
            if prior_operation is not None:
                prior = self._results.get(prior_operation)
                if prior_operation != message.operation_id:
                    return SubmissionResult("REJECTED", "INVALID_MESSAGE", message.operation_id)
                return SubmissionResult(prior.status if prior else "ACCEPTED", prior.code if prior else None,
                                        prior_operation, prior)
            if message.operation_id in self._results:
                return SubmissionResult("REJECTED", "INVALID_MESSAGE", message.operation_id)
            if len(self._idempotency) >= MAX_IDEMPOTENCY_RECORDS:
                return SubmissionResult("DEFERRED", "RESOURCE_EXHAUSTED", message.operation_id)
        if message.message_type is MessageType.EVENT:
            recipients = sum(1 for module in self._modules.values()
                            if message.schema_id in module.event_handlers
                            and message.schema_version in module.supported_schemas.get(message.schema_id, frozenset()))
            if len(self._event_inbox) + recipients > MAX_EVENT_INBOX_RECORDS:
                return SubmissionResult("DEFERRED", "RESOURCE_EXHAUSTED", message.operation_id)

        if message.message_id in self._message_ids:
            return SubmissionResult("REJECTED", "INVALID_MESSAGE", message.operation_id)
        if self._queued_total >= MAX_QUEUE_TOTAL or len(self._queues[priority]) >= QUEUE_QUOTAS[priority.value]:
            return SubmissionResult("DEFERRED", "RESOURCE_EXHAUSTED", message.operation_id)
        if priority in (Priority.P2, Priority.P3, Priority.P4):
            count = self._source_counts.get(message.source_module_id, 0)
            if count >= MAX_PER_SOURCE:
                return SubmissionResult("DEFERRED", "RESOURCE_EXHAUSTED", message.operation_id)
            self._source_counts[message.source_module_id] = count + 1

        now = self._clock()
        self._queues[priority].append(_Queued(message, priority, now))
        self._queued_total += 1
        self._message_ids.add(message.message_id)
        self._pending_message_ids.add(message.message_id)
        if message.message_type is MessageType.COMMAND:
            self._idempotency[(message.source_module_id, message.idempotency_key or "")] = message.operation_id or ""
            self._results[message.operation_id or ""] = OperationResult(message.operation_id or "", "ACCEPTED")
        return SubmissionResult("ACCEPTED", operation_id=message.operation_id)

    def _validate_and_authorize(self, message: MessageEnvelope) -> Priority:
        if message.protocol_version != PROTOCOL_VERSION or not _is_uuid(message.message_id):
            raise CQEError("INVALID_MESSAGE", "invalid protocol version or message id")
        if not isinstance(message.message_type, MessageType):
            raise CQEError("INVALID_MESSAGE", "unknown message type")
        if (not isinstance(message.schema_id, str) or not message.schema_id.strip()
                or isinstance(message.schema_version, bool) or not isinstance(message.schema_version, int)
                or message.schema_version < 1 or not isinstance(message.source_module_id, str)
                or not message.source_module_id.strip()):
            raise CQEError("INVALID_MESSAGE", "missing or invalid schema or source")
        if message.requested_priority is not None and not isinstance(message.requested_priority, Priority):
            raise CQEError("INVALID_MESSAGE", "requested priority hint has an invalid value")
        if not isinstance(message.payload, Mapping):
            raise CQEError("INVALID_MESSAGE", "payload must be a mapping")
        source = self._modules.get(message.source_module_id)
        if source is None:
            raise CQEError("UNAUTHORIZED", "source module is not registered")
        payload_bytes = _json_bytes(dict(message.payload))
        if len(payload_bytes) > MAX_INLINE_PAYLOAD_BYTES:
            raise CQEError("RESOURCE_EXHAUSTED", "inline payload exceeds 4 KiB")
        _validate_descriptors(message.payload)
        envelope_bytes = len(_json_bytes(_envelope_json(message)))
        if envelope_bytes > MAX_ENVELOPE_BYTES:
            raise CQEError("RESOURCE_EXHAUSTED", "message envelope exceeds 8 KiB")
        if message.deadline_budget_ms is not None and (
                isinstance(message.deadline_budget_ms, bool)
                or not isinstance(message.deadline_budget_ms, int)
                or not (1 <= message.deadline_budget_ms <= 60_000)):
            raise CQEError("INVALID_MESSAGE", "deadline budget is outside the permitted range")
        if message.expected_state_version is not None and (
                isinstance(message.expected_state_version, bool)
                or not isinstance(message.expected_state_version, int)
                or message.expected_state_version < 0):
            raise CQEError("INVALID_MESSAGE", "expected state version must be non-negative")

        if message.message_type is MessageType.COMMAND:
            if (not message.target_module_id or not _is_uuid(message.operation_id)
                    or not message.idempotency_key):
                raise CQEError("INVALID_MESSAGE", "command requires target, UUID operation_id and idempotency key")
            target = self._modules.get(message.target_module_id)
            if target is None or message.schema_id not in target.command_handlers:
                raise CQEError("MODULE_UNAVAILABLE", "command target or schema handler is unavailable")
            if message.schema_version not in target.supported_schemas.get(message.schema_id, frozenset()):
                raise CQEError("UNSUPPORTED_SCHEMA", "target does not support this command schema")
            priority = target.command_priorities.get(message.schema_id, Priority.P3)
            deadline_cap = target.command_deadline_caps_ms.get(message.schema_id, 5_000)
            if not (1 <= deadline_cap <= 60_000):
                raise CQEError("INVALID_MESSAGE", "registered command deadline cap is invalid")
            if message.deadline_budget_ms is not None and message.deadline_budget_ms > deadline_cap:
                raise CQEError("INVALID_MESSAGE", "command deadline exceeds registered operation budget")
            if priority in (Priority.P0, Priority.P1) and message.source_module_id not in self._system_modules:
                raise CQEError("UNAUTHORIZED", "only trusted system modules may use P0/P1")
            self._check_capability(message, priority)
            return priority

        if message.message_type is MessageType.QUERY:
            if not message.target_module_id or not message.correlation_id:
                raise CQEError("INVALID_MESSAGE", "query requires target and correlation id")
            if message.operation_id is not None or message.idempotency_key is not None:
                raise CQEError("INVALID_MESSAGE", "query must not use operation or idempotency fields")
            target = self._modules.get(message.target_module_id)
            if target is None or message.schema_id not in target.query_handlers:
                raise CQEError("MODULE_UNAVAILABLE", "query target is unavailable")
            if message.schema_version not in target.supported_schemas.get(message.schema_id, frozenset()):
                raise CQEError("UNSUPPORTED_SCHEMA", "target does not support this query schema")
            if not (1 <= target.query_deadline_cap_ms <= 60_000):
                raise CQEError("INVALID_MESSAGE", "registered query deadline cap is invalid")
            if message.deadline_budget_ms is not None and message.deadline_budget_ms > target.query_deadline_cap_ms:
                raise CQEError("INVALID_MESSAGE", "query deadline exceeds registered budget")
            return Priority.P2

        if message.message_type is MessageType.EVENT:
            if not _is_uuid(message.event_id) or message.operation_id is not None:
                raise CQEError("INVALID_MESSAGE", "event requires a UUID event_id and no operation id")
            if message.schema_id not in source.published_event_schemas:
                raise CQEError("UNAUTHORIZED", "source is not registered to publish this event")
            if message.commit_id is not None and not _is_uuid(message.commit_id):
                raise CQEError("INVALID_MESSAGE", "invalid commit id")
            if (message.commit_id is None) != (message.state_version is None):
                raise CQEError("INVALID_MESSAGE", "state-change events require both commit_id and state_version")
            if message.state_version is not None and (
                    isinstance(message.state_version, bool) or not isinstance(message.state_version, int)
                    or message.state_version < 0):
                raise CQEError("INVALID_MESSAGE", "invalid state version")
            event_deadline_cap = source.event_deadline_cap_ms
            if not (1 <= event_deadline_cap <= 60_000):
                raise CQEError("INVALID_MESSAGE", "registered event deadline cap is invalid")
            if message.deadline_budget_ms is not None and message.deadline_budget_ms > event_deadline_cap:
                raise CQEError("INVALID_MESSAGE", "event deadline exceeds registered budget")
            return Priority.P2
        raise CQEError("INVALID_MESSAGE", "unsupported message type")

    def _check_capability(self, message: MessageEnvelope, priority: Priority) -> None:
        capability = self._capabilities.get(message.capability_id or "")
        if capability is None:
            raise CQEError("UNAUTHORIZED", "missing or unknown capability")
        if capability.subject_module_id != message.source_module_id:
            raise CQEError("UNAUTHORIZED", "capability subject mismatch")
        if message.schema_id not in capability.allowed_operations:
            raise CQEError("UNAUTHORIZED", "operation is outside capability scope")
        if _PRIORITY_RANK[priority] < _PRIORITY_RANK[capability.max_priority]:
            raise CQEError("UNAUTHORIZED", "operation priority exceeds capability")
        if capability.expires_at_monotonic <= self._clock() or capability.policy_version != self._policy_version:
            raise CQEError("UNAUTHORIZED", "capability expired or policy changed")

    def drain_one(self) -> OperationResult | None:
        queued: _Queued | None = None
        for priority in _PRIORITY_ORDER:
            if self._queues[priority]:
                queued = self._queues[priority].popleft()
                break
        if queued is None:
            return None
        self._queued_total -= 1
        if queued.priority in (Priority.P2, Priority.P3, Priority.P4):
            self._source_counts[queued.envelope.source_module_id] -= 1
        message = queued.envelope
        self._pending_message_ids.discard(message.message_id)
        self._completed_message_ids.append(message.message_id)
        if len(self._completed_message_ids) > MAX_MESSAGE_ID_HISTORY:
            expired_id = self._completed_message_ids.popleft()
            self._message_ids.discard(expired_id)
        if message.message_type is MessageType.QUERY:
            return self._run_query(message, queued.received_at)
        if message.message_type is MessageType.EVENT:
            return self._run_event(message, queued.received_at)
        return self._run_command(message, queued.received_at)

    def drain_all(self, limit: int = MAX_QUEUE_TOTAL) -> tuple[OperationResult, ...]:
        if limit < 0:
            raise ValueError("limit must be non-negative")
        results: list[OperationResult] = []
        for _ in range(limit):
            result = self.drain_one()
            if result is None:
                break
            results.append(result)
        return tuple(results)

    def get_status(self, operation_id: str) -> OperationResult | None:
        return self._results.get(operation_id)

    def revoke_capability(self, capability_id: str) -> None:
        self._capabilities.pop(capability_id, None)

    def _run_query(self, message: MessageEnvelope, received_at: float) -> OperationResult:
        target = self._modules[message.target_module_id or ""]
        deadline = received_at + (
            message.deadline_budget_ms or target.query_deadline_cap_ms
        ) / 1000.0
        if self._clock() >= deadline:
            return OperationResult(message.correlation_id or "", "FAILED", "DEADLINE_EXCEEDED")
        try:
            value = target.query_handlers[message.schema_id](message)
            raw = _json_bytes(value)
            if len(raw) > MAX_QUERY_RESULT_BYTES:
                raise CQEError("RESOURCE_EXHAUSTED", "query result exceeds 16 KiB")
            return OperationResult(message.correlation_id or "", "COMPLETED", value=_json_copy(value))
        except CQEError as exc:
            return OperationResult(message.correlation_id or "", "FAILED", code=exc.code)
        except Exception:
            return OperationResult(message.correlation_id or "", "FAILED", code="INTERNAL_FAILURE")

    def _run_command(self, message: MessageEnvelope, received_at: float) -> OperationResult:
        operation_id = message.operation_id or ""
        target = self._modules[message.target_module_id or ""]
        deadline = received_at + (
            message.deadline_budget_ms or target.command_deadline_caps_ms.get(message.schema_id, 5_000)
        ) / 1000.0
        if self._clock() >= deadline:
            result = OperationResult(operation_id, "FAILED", "DEADLINE_EXCEEDED")
            self._results[operation_id] = result
            return result

        # Stage 1: produce and validate a proposal. No shared state has changed yet.
        try:
            self._check_capability(message, target.command_priorities.get(message.schema_id, Priority.P3))
            proposal = target.command_handlers[message.schema_id](message)
            if not isinstance(proposal, CommandProposal):
                raise CQEError("INVALID_MESSAGE", "command handler must return CommandProposal")
            if proposal.expected_state_version != message.expected_state_version:
                raise CQEError("VERSION_CONFLICT", "proposal version differs from command expectation")
            self._check_outbox_capacity(proposal.events, target)
            # A handler may consume time while preparing its proposal. Recheck
            # the monotonic deadline before any commit can mutate shared state.
            if self._clock() >= deadline:
                raise CQEError("DEADLINE_EXCEEDED", "command deadline expired before commit")
            self._check_capability(message, target.command_priorities.get(message.schema_id, Priority.P3))
        except CQEError as exc:
            result = OperationResult(operation_id, "FAILED", exc.code)
            self._results[operation_id] = result
            return result
        except Exception:
            result = OperationResult(operation_id, "FAILED", "INTERNAL_FAILURE")
            self._results[operation_id] = result
            return result

        # Stage 2: commit. Unexpected errors here are ambiguous by definition.
        try:
            receipt = self._commit_store.commit(proposal, operation_id)
        except CQEError as exc:
            result = OperationResult(operation_id, "FAILED", exc.code)
            self._results[operation_id] = result
            return result
        except Exception:
            result = OperationResult(operation_id, "COMMIT_UNKNOWN", "COMMIT_UNKNOWN")
            self._results[operation_id] = result
            return result

        if proposal.requires_durable and not receipt.durable:
            result = OperationResult(operation_id, "COMMIT_UNKNOWN", "COMMIT_UNKNOWN")
            self._results[operation_id] = result
            return result

        # Stage 3: post-commit event queueing. The current reference outbox is
        # volatile; a production implementation must persist it atomically with commit.
        event_ids = tuple(event.event_id for event in proposal.events)
        try:
            for event in proposal.events:
                self._append_committed_event(message, event, receipt, target)
            self.deliver_pending_events()
        except Exception:
            # Commit is already confirmed: never misreport it as rolled back.
            result = OperationResult(operation_id, "COMPLETED", commit_id=receipt.commit_id,
                                     state_version=receipt.state_version, event_ids=event_ids)
            self._results[operation_id] = result
            return result
        result = OperationResult(operation_id, "COMPLETED", commit_id=receipt.commit_id,
                                 state_version=receipt.state_version, event_ids=event_ids)
        self._results[operation_id] = result
        return result

    def _check_outbox_capacity(self, events: tuple[EventSpec, ...], publisher: ModuleSpec) -> None:
        delivery_sizes = []
        delivery_count = 0
        for event in events:
            recipients = [m for m in self._modules.values() if event.schema_id in m.event_handlers]
            delivery_count += len(recipients)
            event_size = len(_json_bytes({"event_id": event.event_id, "schema_id": event.schema_id,
                                          "schema_version": event.schema_version, "payload": dict(event.payload)}))
            delivery_sizes.extend([event_size] * len(recipients))
        if (self._outbox_count + delivery_count > ORDINARY_OUTBOX_LIMIT
                or len(self._event_inbox) + self._outbox_count + delivery_count > MAX_EVENT_INBOX_RECORDS
                or self._outbox_bytes + sum(delivery_sizes) > MAX_OUTBOX_BYTES):
            raise CQEError("RESOURCE_EXHAUSTED", "outbox capacity unavailable before commit; critical reserve is protected")
        for event in events:
            if (not _is_uuid(event.event_id) or not event.schema_id or event.schema_version < 1
                    or event.schema_id not in publisher.published_event_schemas):
                raise CQEError("INVALID_MESSAGE", "invalid or unauthorized committed event descriptor")
            if len(_json_bytes(dict(event.payload))) > MAX_INLINE_PAYLOAD_BYTES:
                raise CQEError("RESOURCE_EXHAUSTED", "event inline payload exceeds 4 KiB")
            for module in self._modules.values():
                if event.schema_id in module.event_handlers and event.schema_version not in module.supported_schemas.get(event.schema_id, frozenset()):
                    raise CQEError("UNSUPPORTED_SCHEMA", "an event subscriber does not support this schema version")

    @property
    def _outbox_count(self) -> int:
        return len(self._outbox)

    def _append_committed_event(self, command: MessageEnvelope, event: EventSpec,
                                receipt: CommitReceipt, publisher: ModuleSpec) -> None:
        encoded_size = len(_json_bytes({"event_id": event.event_id, "schema_id": event.schema_id,
                                        "schema_version": event.schema_version, "payload": dict(event.payload)}))
        for module in self._modules.values():
            if event.schema_id not in module.event_handlers:
                continue
            envelope = MessageEnvelope(
                protocol_version=PROTOCOL_VERSION, message_id=str(uuid.uuid4()),
                message_type=MessageType.EVENT, schema_id=event.schema_id,
                schema_version=event.schema_version, source_module_id=publisher.module_id,
                target_module_id=module.module_id, event_id=event.event_id,
                commit_id=receipt.commit_id, causation_id=command.message_id,
                payload=_json_copy(dict(event.payload)), state_version=receipt.state_version,
            )
            self._outbox.append((envelope, module, encoded_size))
            self._outbox_bytes += encoded_size

    def deliver_pending_events(self) -> int:
        """Attempt volatile outbox delivery; failed subscribers remain pending in memory."""
        delivered = 0
        remaining: Deque[tuple[MessageEnvelope, ModuleSpec, int]] = deque()
        while self._outbox:
            envelope, module, size = self._outbox.popleft()
            try:
                fresh = self._deliver_event(module, envelope)
            except Exception:
                remaining.append((envelope, module, size))
                continue
            self._outbox_bytes -= size
            delivered += int(fresh)
        self._outbox = remaining
        return delivered

    def _run_event(self, message: MessageEnvelope, received_at: float) -> OperationResult:
        source = self._modules[message.source_module_id]
        deadline = received_at + (message.deadline_budget_ms or source.event_deadline_cap_ms) / 1000.0
        if self._clock() >= deadline:
            return OperationResult(message.event_id or "", "FAILED", "DEADLINE_EXCEEDED")
        delivered = 0
        for module in self._modules.values():
            handler = module.event_handlers.get(message.schema_id)
            if handler is None or message.schema_version not in module.supported_schemas.get(message.schema_id, frozenset()):
                continue
            if self._clock() >= deadline:
                return OperationResult(message.event_id or "", "FAILED", "DEADLINE_EXCEEDED",
                                       value={"delivered": delivered})
            target_message = MessageEnvelope(**{**message.__dict__, "target_module_id": module.module_id})
            try:
                if self._deliver_event(module, target_message):
                    delivered += 1
            except Exception:
                return OperationResult(message.event_id or "", "FAILED", "INTERNAL_FAILURE",
                                       value={"delivered": delivered})
        return OperationResult(message.event_id or "", "COMPLETED", value={"delivered": delivered})

    def _deliver_event(self, module: ModuleSpec, message: MessageEnvelope) -> bool:
        key = (module.module_id, message.event_id or "")
        if key in self._event_inbox:
            return False
        handler = module.event_handlers.get(message.schema_id)
        if handler is None:
            return False
        handler(message)
        self._event_inbox.add(key)
        self._published_events.append(message)
        return True


def _validate_descriptors(payload: Mapping[str, Any]) -> None:
    descriptors = payload.get("data_refs", [])
    if not isinstance(descriptors, list) or len(descriptors) > MAX_DESCRIPTORS:
        raise CQEError("RESOURCE_EXHAUSTED", "invalid or excessive external data descriptors")
    for descriptor in descriptors:
        if not isinstance(descriptor, dict) or len(_json_bytes(descriptor)) > MAX_DESCRIPTOR_BYTES:
            raise CQEError("RESOURCE_EXHAUSTED", "external data descriptor exceeds 1 KiB")
        required = {"object_id", "data_type", "expected_size", "version", "checksum"}
        if not required.issubset(descriptor):
            raise CQEError("INVALID_MESSAGE", "external data descriptor is incomplete")


def _envelope_json(message: MessageEnvelope) -> dict[str, Any]:
    return {
        "protocol_version": message.protocol_version,
        "message_id": message.message_id,
        "event_id": message.event_id,
        "commit_id": message.commit_id,
        "message_type": message.message_type.value if isinstance(message.message_type, MessageType) else str(message.message_type),
        "schema_id": message.schema_id,
        "schema_version": message.schema_version,
        "source_module_id": message.source_module_id,
        "target_module_id": message.target_module_id,
        "operation_id": message.operation_id,
        "correlation_id": message.correlation_id,
        "causation_id": message.causation_id,
        "idempotency_key": message.idempotency_key,
        "expected_state_version": message.expected_state_version,
        "deadline_budget_ms": message.deadline_budget_ms,
        "requested_priority": message.requested_priority.value if isinstance(message.requested_priority, Priority) else None,
        "capability_id": message.capability_id,
        "state_version": message.state_version,
        "payload": dict(message.payload),
    }
