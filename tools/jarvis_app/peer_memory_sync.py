"""Versioned, additive-only peer sync for user-approved JARVIS memories."""
from __future__ import annotations

import json
import os
import re
import tempfile
import threading
from pathlib import Path
from typing import Mapping


class PeerMemorySyncError(ValueError):
    pass


class PeerMemorySyncConflict(PeerMemorySyncError):
    pass


class PeerMemorySyncStore:
    """Small CAS-protected peer memory overlay, separate from the JPVR file.

    The overlay carries only explicitly approved memory records. The PC can
    merge/read it without making the Android client's private chat history
    or authentication material part of the portable workspace.
    """

    MAX_RECORDS = 2_000
    MAX_CONTENT_CHARS = 700
    MAX_CATEGORY_CHARS = 64
    MAX_ID_CHARS = 128
    MAX_UPDATED_AT_CHARS = 80
    MAX_FILE_BYTES = 4 * 1024 * 1024
    _ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")

    def __init__(self, path: str | os.PathLike[str]) -> None:
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.RLock()
        if not self.path.exists():
            self._write({"revision": 0, "memories": []})

    def snapshot(self) -> dict[str, object]:
        with self._lock:
            value = self._read()
            return {
                "revision": value["revision"],
                "memories": [dict(record) for record in value["memories"]],
            }

    def merge(
        self,
        records: object,
        expected_revision: object,
    ) -> dict[str, object]:
        if isinstance(expected_revision, bool) or not isinstance(expected_revision, int) or expected_revision < 0:
            raise PeerMemorySyncError("expected_revision must be a non-negative integer")
        if not isinstance(records, list) or len(records) > self.MAX_RECORDS:
            raise PeerMemorySyncError("memories must be a list within the record limit")
        normalized = [self._normalize(item) for item in records]
        incoming_ids = [item["id"] for item in normalized]
        if len(incoming_ids) != len(set(incoming_ids)):
            raise PeerMemorySyncError("incoming memory IDs must be unique")

        with self._lock:
            current = self._read()
            if current["revision"] != expected_revision:
                raise PeerMemorySyncConflict("peer memory revision conflict; fetch again and retry")
            merged = {item["id"]: dict(item) for item in current["memories"]}
            added = 0
            updated = 0
            conflicts = 0
            for item in normalized:
                previous = merged.get(item["id"])
                if previous is None:
                    merged[item["id"]] = item
                    added += 1
                    continue
                if previous == item:
                    continue
                # ISO-8601 UTC timestamps sort lexicographically when normalized.
                if item["updated_at"] > previous["updated_at"]:
                    merged[item["id"]] = item
                    updated += 1
                else:
                    conflicts += 1
            if added or updated:
                if current["revision"] >= (1 << 63) - 1:
                    raise PeerMemorySyncError("peer memory revision counter is exhausted")
                next_value = {
                    "revision": current["revision"] + 1,
                    "memories": sorted(merged.values(), key=lambda item: (item["updated_at"], item["id"])),
                }
                self._write(next_value)
                current = next_value
            return {
                "revision": current["revision"],
                "added": added,
                "updated": updated,
                "conflicts": conflicts,
                "memories": [dict(item) for item in current["memories"]],
            }

    def _read(self) -> dict[str, object]:
        try:
            raw = self.path.read_bytes()
        except FileNotFoundError:
            return {"revision": 0, "memories": []}
        except OSError as exc:
            raise PeerMemorySyncError("cannot read peer memory sync store") from exc
        if len(raw) > self.MAX_FILE_BYTES:
            raise PeerMemorySyncError("peer memory sync store exceeds its size bound")
        try:
            value = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise PeerMemorySyncError("peer memory sync store is corrupted") from exc
        if not isinstance(value, dict) or set(value) != {"revision", "memories"}:
            raise PeerMemorySyncError("peer memory sync store has an invalid root")
        revision = value["revision"]
        memories = value["memories"]
        if isinstance(revision, bool) or not isinstance(revision, int) or revision < 0:
            raise PeerMemorySyncError("peer memory sync revision is invalid")
        if not isinstance(memories, list) or len(memories) > self.MAX_RECORDS:
            raise PeerMemorySyncError("peer memory records are invalid")
        normalized = [self._normalize(item) for item in memories]
        ids = [item["id"] for item in normalized]
        if len(ids) != len(set(ids)):
            raise PeerMemorySyncError("peer memory store contains duplicate IDs")
        return {"revision": revision, "memories": normalized}

    @classmethod
    def _normalize(cls, item: object) -> dict[str, object]:
        if not isinstance(item, Mapping) or set(item) != {
            "id", "content", "category", "updated_at", "user_approved"
        }:
            raise PeerMemorySyncError("each memory must contain exactly id/content/category/updated_at/user_approved")
        record_id = item["id"]
        content = item["content"]
        category = item["category"]
        updated_at = item["updated_at"]
        if not isinstance(record_id, str) or not cls._ID.fullmatch(record_id):
            raise PeerMemorySyncError("memory ID is invalid")
        if not isinstance(content, str) or not content.strip() or len(content) > cls.MAX_CONTENT_CHARS or "\x00" in content:
            raise PeerMemorySyncError("memory content is empty or outside its bound")
        if not isinstance(category, str) or len(category) > cls.MAX_CATEGORY_CHARS or "\x00" in category:
            raise PeerMemorySyncError("memory category is invalid")
        if not isinstance(updated_at, str) or not updated_at or len(updated_at) > cls.MAX_UPDATED_AT_CHARS:
            raise PeerMemorySyncError("memory updated_at is invalid")
        if item["user_approved"] is not True:
            raise PeerMemorySyncError("only explicitly user-approved memories may be synchronized")
        return {
            "id": record_id,
            "content": content.strip(),
            "category": category.strip(),
            "updated_at": updated_at,
            "user_approved": True,
        }

    def _write(self, value: dict[str, object]) -> None:
        payload = json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            allow_nan=False,
        ).encode("utf-8")
        if len(payload) > self.MAX_FILE_BYTES:
            raise PeerMemorySyncError("peer memory sync store would exceed its size bound")
        fd, temporary = tempfile.mkstemp(prefix=self.path.name + ".", suffix=".tmp", dir=self.path.parent)
        try:
            try:
                os.fchmod(fd, 0o600)
            except (AttributeError, OSError):
                pass
            with os.fdopen(fd, "wb") as stream:
                stream.write(payload)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)
            try:
                directory_fd = os.open(self.path.parent, os.O_RDONLY)
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
            except OSError:
                pass
        except Exception:
            try:
                os.unlink(temporary)
            except OSError:
                pass
            raise
