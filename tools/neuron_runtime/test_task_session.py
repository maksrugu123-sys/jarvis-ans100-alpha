import json
import unittest
from .task_session import SessionStatus,TaskConflict,TaskSessionError,TaskSessionManager,TaskStatus

class TaskSessionLifecycleTests(unittest.TestCase):
    def manager(self,**limits): return TaskSessionManager(manager_id="test-manager",**limits)
    def test_create_session_and_task(self):
        m=self.manager(); s=m.create_session("s1",metadata={"source":"local"})
        t=m.create_task("t1","s1","idem-1","First task",input_refs=["memory:1"])
        self.assertEqual(s.status,SessionStatus.OPEN); self.assertEqual(t.status,TaskStatus.QUEUED)
        self.assertEqual(m.revision,2)
    def test_idempotent_create_returns_same_task(self):
        m=self.manager(); m.create_session("s1")
        a=m.create_task("t1","s1","idem-1","Task"); b=m.create_task("t1","s1","idem-1","Task")
        self.assertEqual(a,b); self.assertEqual(len(m.tasks),1)
    def test_idempotency_key_conflict_is_rejected(self):
        m=self.manager(); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        with self.assertRaises(TaskConflict):m.create_task("t2","s1","idem-1","Other")
    def test_state_machine_and_terminal_immutability(self):
        m=self.manager(); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        a=m.transition_task("t1",TaskStatus.RUNNING,expected_revision=1)
        b=m.transition_task("t1",TaskStatus.WAITING,expected_revision=a.revision)
        c=m.transition_task("t1",TaskStatus.RUNNING,expected_revision=b.revision)
        d=m.transition_task("t1",TaskStatus.COMPLETED,expected_revision=c.revision,result={"answer":"42"})
        self.assertEqual(d.status,TaskStatus.COMPLETED)
        with self.assertRaises(TaskConflict):m.transition_task("t1",TaskStatus.RUNNING,expected_revision=d.revision)
    def test_compare_and_swap_rejects_stale_revision(self):
        m=self.manager(); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        with self.assertRaises(TaskConflict):m.transition_task("t1",TaskStatus.RUNNING,expected_revision=0)
    def test_paused_session_blocks_task_creation_and_start(self):
        m=self.manager(); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        m.set_session_status("s1",SessionStatus.PAUSED,expected_revision=1)
        with self.assertRaises(TaskConflict):m.create_task("t2","s1","idem-2","Blocked")
        with self.assertRaises(TaskConflict):m.transition_task("t1",TaskStatus.RUNNING,expected_revision=1)
    def test_session_cannot_close_with_nonterminal_tasks(self):
        m=self.manager(); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        with self.assertRaises(TaskConflict):m.set_session_status("s1",SessionStatus.CLOSED,expected_revision=1)
    def test_session_can_close_after_terminal_tasks(self):
        m=self.manager(); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        m.transition_task("t1",TaskStatus.CANCELLED,expected_revision=1)
        self.assertEqual(m.set_session_status("s1",SessionStatus.CLOSED,expected_revision=1).status,SessionStatus.CLOSED)
    def test_capacity_rejection_does_not_mutate_snapshot(self):
        m=self.manager(max_tasks=1); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        before=m.encode_snapshot()
        with self.assertRaises(TaskSessionError):m.create_task("t2","s1","idem-2","Overflow")
        self.assertEqual(m.encode_snapshot(),before)
    def test_event_capacity_rejection_does_not_mutate_snapshot(self):
        m=self.manager(max_events=2); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        before=m.encode_snapshot()
        with self.assertRaises(TaskSessionError):m.transition_task("t1",TaskStatus.RUNNING,expected_revision=1)
        self.assertEqual(m.encode_snapshot(),before)
    def test_snapshot_round_trip_is_deterministic(self):
        m=self.manager(); m.create_session("s1",metadata={"z":1,"a":True})
        m.create_task("t1","s1","idem-1","Task",input_refs=["ref:1"])
        m.transition_task("t1",TaskStatus.RUNNING,expected_revision=1)
        m.transition_task("t1",TaskStatus.COMPLETED,expected_revision=2,result={"ok":True})
        payload=m.encode_snapshot(); restored=TaskSessionManager.restore_snapshot(payload)
        self.assertEqual(restored.encode_snapshot(),payload)
    def test_snapshot_rejects_duplicate_json_keys(self):
        payload=self.manager().encode_snapshot().replace(b'"version":1',b'"version":1,"version":1')
        with self.assertRaises(TaskSessionError):TaskSessionManager.restore_snapshot(payload)
    def test_snapshot_rejects_unknown_schema_and_oversize(self):
        raw=json.loads(self.manager().encode_snapshot()); raw["schema"]="other"
        with self.assertRaises(TaskSessionError):TaskSessionManager.restore_snapshot(json.dumps(raw).encode())
        with self.assertRaises(TaskSessionError):TaskSessionManager.restore_snapshot(b" "*(4*1024*1024+1))
    def test_invalid_json_metadata_is_rejected_without_mutation(self):
        m=self.manager()
        with self.assertRaises(TaskSessionError):m.create_session("s1",metadata={"bad":float("nan")})
        self.assertEqual(m.revision,0)
    def test_event_history_round_trip(self):
        m=self.manager(); m.create_session("s1"); m.create_task("t1","s1","idem-1","Task")
        m.transition_task("t1",TaskStatus.CANCELLED,expected_revision=1)
        self.assertEqual(TaskSessionManager.restore_snapshot(m.encode_snapshot()).events,m.events)

if __name__=="__main__": unittest.main()
