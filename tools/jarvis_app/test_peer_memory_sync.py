from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.jarvis_app.peer_memory_sync import (
    PeerMemorySyncConflict,
    PeerMemorySyncError,
    PeerMemorySyncStore,
)


def record(record_id: str, content: str, updated_at: str = "2026-11-14T10:00:00Z") -> dict[str, object]:
    return {
        "id": record_id,
        "content": content,
        "category": "project",
        "updated_at": updated_at,
        "user_approved": True,
    }


class PeerMemorySyncStoreTests(unittest.TestCase):
    def test_additive_merge_and_reopen_preserve_approved_memories(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "peer.json"
            store = PeerMemorySyncStore(path)
            first = store.merge([record("phone-1", "JARVIS memory")], 0)
            self.assertEqual(first["added"], 1)
            reopened = PeerMemorySyncStore(path)
            self.assertEqual(reopened.snapshot()["revision"], 1)
            self.assertEqual(reopened.snapshot()["memories"][0]["id"], "phone-1")

    def test_newer_same_id_record_updates_existing_value(self):
        with tempfile.TemporaryDirectory() as directory:
            store = PeerMemorySyncStore(Path(directory) / "peer.json")
            store.merge([record("same-id", "old", "2026-11-14T10:00:00Z")], 0)
            updated = store.merge([record("same-id", "new", "2026-11-14T11:00:00Z")], 1)
            self.assertEqual(updated["updated"], 1)
            self.assertEqual(updated["memories"][0]["content"], "new")

    def test_stale_revision_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = PeerMemorySyncStore(Path(directory) / "peer.json")
            store.merge([record("phone-1", "note")], 0)
            with self.assertRaises(PeerMemorySyncConflict):
                store.merge([record("phone-2", "other")], 0)

    def test_unapproved_or_duplicate_records_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            store = PeerMemorySyncStore(Path(directory) / "peer.json")
            unapproved = record("note-1", "private")
            unapproved["user_approved"] = False
            with self.assertRaises(PeerMemorySyncError):
                store.merge([unapproved], 0)
            with self.assertRaises(PeerMemorySyncError):
                store.merge([record("same", "a"), record("same", "b")], 0)


if __name__ == "__main__":
    unittest.main()
