"""Tests for the shared desktop/Android workspace memory contract."""
import os
import tempfile
import unittest

from tools.jpvr_reference.storage import JPVRStorage
from tools.neuron_runtime.workspace_store import (
    JPVRWorkspaceStore, WorkspaceConflict, WorkspaceError, WorkspaceNotFound,
    WorkspaceSnapshot, decode_workspace, encode_workspace,
)


def empty_snapshot(workspace_id="shared-workspace", platform="desktop"):
    return WorkspaceSnapshot(
        workspace_id=workspace_id,
        revision=0,
        exported_at="2026-11-13T12:00:00+00:00",
        source_platform=platform,
        records={"conversations": [], "memories": [], "tasks": [], "settings": []},
    )


class WorkspaceCodecTests(unittest.TestCase):
    def test_canonical_round_trip_and_integrity(self):
        snapshot = empty_snapshot()
        raw = encode_workspace(snapshot)
        decoded = decode_workspace(raw)
        self.assertEqual(decoded.workspace_id, snapshot.workspace_id)
        self.assertEqual(decoded.records, snapshot.records)
        self.assertEqual(encode_workspace(decoded), raw)

    def test_memory_requires_explicit_user_approval(self):
        snapshot = empty_snapshot()
        snapshot.records["memories"].append({
            "id": "mem-1", "revision": 1,
            "updated_at": "2026-11-13T12:00:00+00:00",
            "content": "User-approved preference",
            "category": "preference", "user_approved": False,
        })
        with self.assertRaisesRegex(WorkspaceError, "user-approved"):
            encode_workspace(snapshot)

    def test_duplicate_ids_are_rejected(self):
        snapshot = empty_snapshot()
        item = {
            "id": "record-1", "revision": 0,
            "updated_at": "2026-11-13T12:00:00+00:00",
            "title": "Conversation", "messages": [],
        }
        snapshot.records["conversations"].append(item)
        snapshot.records["tasks"].append({
            "id": "record-1", "revision": 0,
            "updated_at": "2026-11-13T12:00:00+00:00",
            "title": "Task", "status": "queued",
        })
        with self.assertRaisesRegex(WorkspaceError, "unique"):
            encode_workspace(snapshot)

    def test_payload_tampering_fails_integrity(self):
        raw = encode_workspace(empty_snapshot())
        tampered = raw.replace(b"shared-workspace", b"other-workspace")
        with self.assertRaisesRegex(WorkspaceError, "SHA-256"):
            decode_workspace(tampered)


class JPVRWorkspaceStoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.temp.name, "workspace.jpvr")
        self.storage = JPVRStorage.create(self.path)
        self.store = JPVRWorkspaceStore(self.storage)
        self.addCleanup(self.temp.cleanup)
        self.addCleanup(self.storage.close)

    def test_save_load_round_trip_and_revision(self):
        snapshot = empty_snapshot()
        saved = self.store.save(snapshot, expected_revision=0)
        loaded, revision, active = self.store.load()
        self.assertEqual(saved.revision, 1)
        self.assertEqual(revision, 1)
        self.assertEqual(loaded.workspace_id, snapshot.workspace_id)
        self.assertEqual(loaded.records, snapshot.records)
        self.assertEqual(active.generation, saved.generation)

    def test_stale_revision_is_rejected_without_publishing(self):
        snapshot = empty_snapshot()
        first = self.store.save(snapshot, expected_revision=0)
        with self.assertRaises(WorkspaceConflict):
            self.store.save(snapshot, expected_revision=0)
        loaded, revision, _ = self.store.load()
        self.assertEqual(revision, first.revision)
        self.assertEqual(loaded.workspace_id, snapshot.workspace_id)

    def test_workspace_identity_is_bound_to_dedicated_container(self):
        self.store.save(empty_snapshot("workspace-a"), expected_revision=0)
        with self.assertRaises(WorkspaceConflict):
            self.store.save(empty_snapshot("workspace-b"), expected_revision=1)

    def test_large_conversation_snapshot_uses_blob_pages(self):
        snapshot = empty_snapshot()
        snapshot.records["conversations"].append({
            "id": "conversation-1", "revision": 1,
            "updated_at": "2026-11-13T12:00:00+00:00",
            "title": "Long conversation",
            "messages": [{
                "id": "message-1", "role": "user",
                "created_at": "2026-11-13T12:00:00+00:00",
                "content": "x" * 12000,
            }],
        })
        saved = self.store.save(snapshot, expected_revision=0)
        loaded, revision, _ = self.store.load()
        self.assertEqual(saved.storage_kind, "BLOB")
        self.assertEqual(revision, 1)
        self.assertEqual(loaded.records["conversations"][0]["messages"][0]["content"], "x" * 12000)

    def test_reopen_preserves_shared_workspace(self):
        snapshot = empty_snapshot(platform="android")
        self.store.save(snapshot, expected_revision=0)
        self.storage.close()
        reopened = JPVRStorage.open(self.path, recover_tail=False)
        self.addCleanup(reopened.close)
        loaded, revision, _ = JPVRWorkspaceStore(reopened).load()
        self.assertEqual(revision, 1)
        self.assertEqual(loaded.source_platform, "android")


if __name__ == "__main__":
    unittest.main()
