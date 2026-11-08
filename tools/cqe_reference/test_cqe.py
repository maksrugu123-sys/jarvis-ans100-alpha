import unittest
import uuid

from .cqe import (
    Capability, CommandProposal, EventSpec, InMemoryCommitCoordinator,
    MessageEnvelope, MessageType, ModuleSpec, NCCDispatcher, Priority,
)


def uid():
    return str(uuid.uuid4())


class CQEReferenceTests(unittest.TestCase):
    def setUp(self):
        self.now = [100.0]
        self.store = InMemoryCommitCoordinator()
        self.store.register_domain("neuron", {"value": 0}, version=0)
        self.ncc = NCCDispatcher(self.store, clock=lambda: self.now[0])
        self.events = []
        self.state = {"value": 0}

        def set_value(message):
            return CommandProposal("neuron", message.expected_state_version,
                                   {"value": message.payload["value"]})

        def read_value(_message):
            version, state = self.store.read("neuron")
            return {"version": version, **state}

        self.ncc.register_module(ModuleSpec(
            module_id="signal-module",
            supported_schemas={"set-value": frozenset({1}), "read-value": frozenset({1})},
            query_handlers={"read-value": read_value},
        ))
        self.ncc.register_module(ModuleSpec(
            module_id="state-manager",
            supported_schemas={"set-value": frozenset({1})},
            command_handlers={"set-value": set_value},
            command_priorities={"set-value": Priority.P3},
            published_event_schemas=frozenset({"state.changed"}),
        ))
        self.ncc.register_module(ModuleSpec(
            module_id="observer",
            supported_schemas={"state.changed": frozenset({1})},
            event_handlers={"state.changed": lambda message: self.events.append(message)},
        ))
        self.cap = Capability("cap-1", "signal-module", frozenset({"set-value"}),
                              Priority.P3, 100.5, 1)
        self.ncc.register_capability(self.cap)

    def command(self, **changes):
        fields = dict(
            protocol_version=1, message_id=uid(), message_type=MessageType.COMMAND,
            schema_id="set-value", schema_version=1, source_module_id="signal-module",
            target_module_id="state-manager", operation_id=uid(), idempotency_key="idem-1",
            expected_state_version=0, capability_id="cap-1", payload={"value": 5},
        )
        fields.update(changes)
        return MessageEnvelope(**fields)

    def test_command_accept_then_commit(self):
        accepted = self.ncc.submit(self.command())
        self.assertEqual(accepted.status, "ACCEPTED")
        result = self.ncc.drain_one()
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(self.store.read("neuron"), (1, {"value": 5}))

    def test_query_is_read_only_and_correlated(self):
        query = MessageEnvelope(
            1, uid(), MessageType.QUERY, "read-value", 1, "signal-module",
            {"include_version": True}, target_module_id="signal-module", correlation_id="corr-1",
        )
        self.assertEqual(self.ncc.submit(query).status, "ACCEPTED")
        result = self.ncc.drain_one()
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(result.value, {"version": 0, "value": 0})
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_missing_capability_is_denied(self):
        result = self.ncc.submit(self.command(capability_id=None))
        self.assertEqual((result.status, result.code), ("REJECTED", "UNAUTHORIZED"))

    def test_capability_rechecked_before_commit(self):
        accepted = self.ncc.submit(self.command(deadline_budget_ms=5_000))
        self.assertEqual(accepted.status, "ACCEPTED")
        self.now[0] = 101.0
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "UNAUTHORIZED"))
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_revoked_capability_is_rechecked_before_commit(self):
        self.assertEqual(self.ncc.submit(self.command()).status, "ACCEPTED")
        self.ncc.revoke_capability("cap-1")
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "UNAUTHORIZED"))
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_query_deadline_cannot_exceed_registered_budget(self):
        query = MessageEnvelope(1, uid(), MessageType.QUERY, "read-value", 1,
                                "signal-module", {}, target_module_id="signal-module",
                                correlation_id="corr-deadline", deadline_budget_ms=1001)
        result = self.ncc.submit(query)
        self.assertEqual((result.status, result.code), ("REJECTED", "INVALID_MESSAGE"))

    def test_priority_comes_from_ncc_policy_not_sender_hint(self):
        result = self.ncc.submit(self.command(requested_priority=Priority.P0))
        self.assertEqual(result.status, "ACCEPTED")
        self.assertEqual(self.ncc.queue_counts["P3"], 1)
        self.assertEqual(self.ncc.queue_counts["P0"], 0)

    def test_unsupported_schema_is_rejected(self):
        result = self.ncc.submit(self.command(schema_version=2))
        self.assertEqual((result.status, result.code), ("REJECTED", "UNSUPPORTED_SCHEMA"))

    def test_invalid_message_id_is_rejected(self):
        result = self.ncc.submit(self.command(message_id="not-a-uuid"))
        self.assertEqual((result.status, result.code), ("REJECTED", "INVALID_MESSAGE"))

    def test_command_idempotency_prevents_duplicate_queueing(self):
        first_message = self.command()
        first = self.ncc.submit(first_message)
        second = self.ncc.submit(self.command(message_id=uid(), operation_id=first_message.operation_id))
        self.assertEqual(first.status, "ACCEPTED")
        self.assertEqual(second.status, "ACCEPTED")
        self.assertEqual(self.ncc.queued_count, 1)

    def test_same_idempotency_key_cannot_name_other_operation(self):
        self.ncc.submit(self.command())
        second = self.ncc.submit(self.command(operation_id=uid(), message_id=uid()))
        self.assertEqual((second.status, second.code), ("REJECTED", "INVALID_MESSAGE"))

    def test_stale_state_version_is_rejected_at_commit(self):
        self.ncc.submit(self.command(expected_state_version=3))
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "VERSION_CONFLICT"))
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_deadline_expiring_during_handler_prevents_commit(self):
        def slow_handler(message):
            self.now[0] += 0.020
            return CommandProposal("neuron", message.expected_state_version,
                                   {"value": message.payload["value"]})

        self.ncc.register_module(ModuleSpec(
            "slow-manager", {"slow-set": frozenset({1})},
            command_handlers={"slow-set": slow_handler},
            command_deadline_caps_ms={"slow-set": 10},
        ))
        self.ncc.register_capability(Capability(
            "cap-slow", "signal-module", frozenset({"slow-set"}),
            Priority.P3, 200.0, 1,
        ))
        message = self.command(
            target_module_id="slow-manager", schema_id="slow-set", operation_id=uid(),
            idempotency_key="slow-1", capability_id="cap-slow", deadline_budget_ms=10,
        )
        self.assertEqual(self.ncc.submit(message).status, "ACCEPTED")
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "DEADLINE_EXCEEDED"))
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_priority_queue_drains_higher_priority_first(self):
        processed = []

        def make_module(module_id, schema_id, priority):
            def handler(message):
                processed.append(schema_id)
                return CommandProposal("neuron", message.expected_state_version,
                                       {"value": len(processed)})
            self.ncc.register_module(ModuleSpec(
                module_id, {schema_id: frozenset({1})},
                command_handlers={schema_id: handler},
                command_priorities={schema_id: priority},
            ))
            self.ncc.register_capability(Capability(
                f"cap-{schema_id}", "signal-module", frozenset({schema_id}),
                priority, 200.0, 1,
            ))

        make_module("ordinary-manager", "ordinary", Priority.P3)
        make_module("urgent-manager", "urgent", Priority.P2)
        for schema_id, target in (("ordinary", "ordinary-manager"), ("urgent", "urgent-manager")):
            message = self.command(
                target_module_id=target, schema_id=schema_id, operation_id=uid(),
                idempotency_key=f"idem-{schema_id}", capability_id=f"cap-{schema_id}",
                expected_state_version=0,
            )
            self.assertEqual(self.ncc.submit(message).status, "ACCEPTED")
        self.ncc.drain_all()
        self.assertEqual(processed, ["urgent", "ordinary"])

    def test_priority_aging_prevents_starvation(self):
        processed = []

        def register(module_id, schema_id, priority):
            def handler(message):
                processed.append(schema_id)
                return CommandProposal("neuron", message.expected_state_version,
                                       {"value": len(processed)})
            self.ncc.register_module(ModuleSpec(
                module_id, {schema_id: frozenset({1})},
                command_handlers={schema_id: handler},
                command_priorities={schema_id: priority},
            ))
            self.ncc.register_capability(Capability(
                f"cap-{schema_id}", "signal-module", frozenset({schema_id}),
                priority, 200.0, 1,
            ))

        register("aged-manager", "aged-ordinary", Priority.P3)
        register("fresh-manager", "fresh-urgent", Priority.P2)

        aged = self.command(
            target_module_id="aged-manager", schema_id="aged-ordinary",
            operation_id=uid(), idempotency_key="aged-ordinary",
            capability_id="cap-aged-ordinary",
        )
        self.assertEqual(self.ncc.submit(aged).status, "ACCEPTED")

        # One aging interval promotes the waiting P3 message to effective P2.
        self.now[0] += 5.0
        urgent = self.command(
            target_module_id="fresh-manager", schema_id="fresh-urgent",
            operation_id=uid(), idempotency_key="fresh-urgent",
            capability_id="cap-fresh-urgent",
        )
        self.assertEqual(self.ncc.submit(urgent).status, "ACCEPTED")

        first = self.ncc.drain_one()
        self.assertEqual(first.operation_id, aged.operation_id)
        self.assertEqual(processed, ["aged-ordinary"])

    def test_expired_deadline_prevents_execution(self):
        self.ncc.submit(self.command(deadline_budget_ms=10))
        self.now[0] += 0.011
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "DEADLINE_EXCEEDED"))
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_queue_is_bounded_by_per_source_limit(self):
        # Queries use P2; one ordinary source may hold at most 32 queued messages.
        for index in range(32):
            query = MessageEnvelope(1, uid(), MessageType.QUERY, "read-value", 1,
                                    "signal-module", {}, target_module_id="signal-module",
                                    correlation_id=f"corr-{index}")
            self.assertEqual(self.ncc.submit(query).status, "ACCEPTED")
        query = MessageEnvelope(1, uid(), MessageType.QUERY, "read-value", 1,
                                "signal-module", {}, target_module_id="signal-module",
                                correlation_id="corr-overflow")
        overflow = self.ncc.submit(query)
        self.assertEqual((overflow.status, overflow.code), ("DEFERRED", "RESOURCE_EXHAUSTED"))
        self.assertEqual(self.ncc.queued_count, 32)

    def test_inline_payload_limit_is_enforced(self):
        result = self.ncc.submit(self.command(payload={"blob": "x" * 5000}))
        self.assertEqual((result.status, result.code), ("REJECTED", "RESOURCE_EXHAUSTED"))

    def test_critical_priority_cannot_be_requested_by_untrusted_sender(self):
        target = ModuleSpec(
            "critical-handler", {"critical": frozenset({1})},
            command_handlers={"critical": lambda m: CommandProposal("neuron", 0, {"value": 9})},
            command_priorities={"critical": Priority.P0},
        )
        self.ncc.register_module(target)
        self.ncc.register_capability(Capability("cap-critical", "signal-module",
            frozenset({"critical"}), Priority.P0, 1000.0, 1))
        msg = self.command(target_module_id="critical-handler", schema_id="critical",
                           operation_id=uid(), idempotency_key="critical-1",
                           capability_id="cap-critical", expected_state_version=0,
                           requested_priority=Priority.P0)
        result = self.ncc.submit(msg)
        self.assertEqual((result.status, result.code), ("REJECTED", "UNAUTHORIZED"))

    def test_durable_command_is_not_falsely_acknowledged(self):
        def durable_handler(message):
            return CommandProposal("neuron", 0, {"value": 7}, requires_durable=True)
        self.ncc.register_module(ModuleSpec(
            "durable-manager", {"durable": frozenset({1})},
            command_handlers={"durable": durable_handler},
        ))
        self.ncc.register_capability(Capability("cap-save", "signal-module", frozenset({"durable"}),
                                                Priority.P3, 1000.0, 1))
        msg = self.command(target_module_id="durable-manager", operation_id=uid(),
                           schema_id="durable", idempotency_key="save-1",
                           capability_id="cap-save")
        self.assertEqual(self.ncc.submit(msg).status, "ACCEPTED")
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "MODULE_UNAVAILABLE"))
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_event_published_only_after_successful_commit(self):
        event = EventSpec(uid(), "state.changed", 1, {"value": 5})
        def command_with_event(message):
            return CommandProposal("neuron", 0, {"value": 5}, events=(event,))
        self.ncc.register_module(ModuleSpec(
            "event-state-manager", {"set-event": frozenset({1})},
            command_handlers={"set-event": command_with_event},
            published_event_schemas=frozenset({"state.changed"}),
        ))
        self.ncc.register_capability(Capability("cap-event", "signal-module",
            frozenset({"set-event"}), Priority.P3, 1000.0, 1))
        msg = self.command(target_module_id="event-state-manager", schema_id="set-event",
                           operation_id=uid(), idempotency_key="event-1", capability_id="cap-event")
        self.assertEqual(self.ncc.submit(msg).status, "ACCEPTED")
        self.assertEqual(self.events, [])
        result = self.ncc.drain_one()
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(len(self.events), 1)
        self.assertEqual(self.events[0].commit_id, result.commit_id)

    def test_failed_event_delivery_stays_pending_and_can_be_retried(self):
        attempts = [0]

        def flaky_handler(message):
            attempts[0] += 1
            if attempts[0] == 1:
                raise RuntimeError("temporary subscriber failure")

        self.ncc.register_module(ModuleSpec(
            "flaky-observer", {"state.changed": frozenset({1})},
            event_handlers={"state.changed": flaky_handler},
        ))
        event = EventSpec(uid(), "state.changed", 1, {"value": 8})
        def command_with_event(_message):
            return CommandProposal("neuron", 0, {"value": 8}, events=(event,))
        self.ncc.register_module(ModuleSpec(
            "retry-event-manager", {"set-retry": frozenset({1})},
            command_handlers={"set-retry": command_with_event},
            published_event_schemas=frozenset({"state.changed"}),
        ))
        self.ncc.register_capability(Capability("cap-retry", "signal-module",
            frozenset({"set-retry"}), Priority.P3, 1000.0, 1))
        msg = self.command(target_module_id="retry-event-manager", schema_id="set-retry",
                           operation_id=uid(), idempotency_key="retry-event", capability_id="cap-retry")
        self.assertEqual(self.ncc.submit(msg).status, "ACCEPTED")
        result = self.ncc.drain_one()
        self.assertEqual(result.status, "COMPLETED")
        self.assertEqual(self.ncc.pending_outbox_count, 1)
        self.assertEqual(self.ncc.deliver_pending_events(), 1)
        self.assertEqual(self.ncc.pending_outbox_count, 0)
        self.assertEqual(attempts[0], 2)

    def test_outbox_capacity_is_checked_before_commit(self):
        events = tuple(EventSpec(uid(), "state.changed", 1, {"index": i}) for i in range(241))
        def many_events(_message):
            return CommandProposal("neuron", 0, {"value": 99}, events=events)
        self.ncc.register_module(ModuleSpec(
            "bulk-event-manager", {"bulk": frozenset({1})},
            command_handlers={"bulk": many_events},
            published_event_schemas=frozenset({"state.changed"}),
        ))
        self.ncc.register_capability(Capability("cap-bulk", "signal-module",
            frozenset({"bulk"}), Priority.P3, 1000.0, 1))
        msg = self.command(target_module_id="bulk-event-manager", schema_id="bulk",
                           operation_id=uid(), idempotency_key="bulk-1", capability_id="cap-bulk")
        self.assertEqual(self.ncc.submit(msg).status, "ACCEPTED")
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "RESOURCE_EXHAUSTED"))
        self.assertEqual(self.store.read("neuron"), (0, {"value": 0}))

    def test_query_result_limit_is_enforced(self):
        self.ncc.register_module(ModuleSpec(
            "large-query", {"large-read": frozenset({1})},
            query_handlers={"large-read": lambda _m: {"large": "z" * 17000}},
        ))
        query = MessageEnvelope(1, uid(), MessageType.QUERY, "large-read", 1,
                                "signal-module", {}, target_module_id="large-query",
                                correlation_id="large-correlation")
        self.assertEqual(self.ncc.submit(query).status, "ACCEPTED")
        result = self.ncc.drain_one()
        self.assertEqual((result.status, result.code), ("FAILED", "RESOURCE_EXHAUSTED"))

    def test_event_delivery_deduplicates_per_subscriber(self):
        event_id = uid()
        event = MessageEnvelope(1, uid(), MessageType.EVENT, "state.changed", 1,
                                "state-manager", {"value": 3}, event_id=event_id)
        # The publisher must explicitly register the event schema (set up above).
        self.assertEqual(self.ncc.submit(event).status, "ACCEPTED")
        first = self.ncc.drain_one()
        self.assertEqual(first.value["delivered"], 1)
        redelivery = MessageEnvelope(**{**event.__dict__, "message_id": uid()})
        self.assertEqual(self.ncc.submit(redelivery).status, "ACCEPTED")
        second = self.ncc.drain_one()
        self.assertEqual(second.value["delivered"], 0)
        self.assertEqual(len(self.events), 1)

    def test_nan_payload_is_rejected(self):
        result = self.ncc.submit(self.command(payload={"value": float("nan")}))
        self.assertEqual((result.status, result.code), ("REJECTED", "INVALID_MESSAGE"))

    def test_query_with_operation_id_is_rejected(self):
        query = MessageEnvelope(1, uid(), MessageType.QUERY, "read-value", 1,
                                "signal-module", {}, target_module_id="signal-module",
                                correlation_id="corr", operation_id="not-allowed")
        result = self.ncc.submit(query)
        self.assertEqual((result.status, result.code), ("REJECTED", "INVALID_MESSAGE"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
