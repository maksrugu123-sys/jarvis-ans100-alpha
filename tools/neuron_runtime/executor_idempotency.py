"""SQLite-backed idempotency ledger for a caller-supplied ActionExecutor.

The ledger commits an invocation claim before delegation and stores known results.
A repeated key never blindly dispatches again while an earlier outcome is unknown.
This is a conservative local reference wrapper, not an exactly-once external
transaction or a substitute for the downstream service's own idempotency API.
"""
from __future__ import annotations

from contextlib import contextmanager
from dataclasses import dataclass
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import time
from typing import Iterator, Protocol

from .permission_safety_gate import AuthorizationRequest, authorization_intent_digest
from .reasoning_engine import PlanningAction
from .task_loop import ActionExecutionResult, ActionExecutor, ExecutionOutcome


_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$")
_DIGEST_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_SCHEMA_VERSION = 1
_MAX_RESULT_BYTES = 128 * 1024
_MAX_LEDGER_RECORDS = 256
_MAX_DATABASE_PAGES = 16_384
_DOMAIN = b"JARVIS:EXECUTOR-IDEMPOTENCY:V1\x00"
_FINAL_STATES = frozenset({"succeeded", "failed_no_effect"})


class DurableExecutorLedgerError(ValueError):
    """Base error for durable executor-ledger validation and storage failures."""


class DurableExecutorLedgerConflict(DurableExecutorLedgerError):
    """An idempotency key was reused for a different request or result."""


class DurableExecutorLedgerCorrupt(DurableExecutorLedgerError):
    """The ledger schema or a persisted result failed validation."""


class DurableExecutorLedgerCapacity(DurableExecutorLedgerError):
    """The bounded ledger has no remaining record capacity."""


class _Executor(Protocol):
    def execute(
        self,
        action: PlanningAction,
        request: AuthorizationRequest,
    ) -> ActionExecutionResult:
        ...


@dataclass(frozen=True)
class DurableExecutionRecord:
    idempotency_key: str
    request_digest: str
    state: str
    created_at_ns: int
    updated_at_ns: int
    result: ActionExecutionResult | None
    result_digest: str | None
    reconciliation_ref: str | None


def _canonical(value: object) -> bytes:
    try:
        return json.dumps(
            value, sort_keys=True, ensure_ascii=True,
            separators=(",", ":"), allow_nan=False,
        ).encode("ascii")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise DurableExecutorLedgerError("executor ledger value cannot be canonicalized") from exc


def _request_digest(action: PlanningAction, request: AuthorizationRequest) -> str:
    if not isinstance(action, PlanningAction):
        raise DurableExecutorLedgerError("action must be a PlanningAction")
    if not isinstance(request, AuthorizationRequest):
        raise DurableExecutorLedgerError("request must be an AuthorizationRequest")
    if action.action_id != request.action_id:
        raise DurableExecutorLedgerError("action ID and authorization request ID differ")
    document = {
        "authorization_intent_digest": authorization_intent_digest(request),
        "action": {
            "action_id": action.action_id,
            "summary": action.summary,
            "rationale": action.rationale,
            "preconditions": list(action.preconditions),
            "add_facts": list(action.add_facts),
            "delete_facts": list(action.delete_facts),
        },
    }
    return hashlib.sha256(_DOMAIN + _canonical(document)).hexdigest()


def _encode_result(result: ActionExecutionResult) -> tuple[str, str, str]:
    if not isinstance(result, ActionExecutionResult):
        raise DurableExecutorLedgerError("result must be an ActionExecutionResult")
    if result.outcome is ExecutionOutcome.UNKNOWN:
        raise DurableExecutorLedgerError("UNKNOWN outcomes are not final ledger results")
    document = {
        "outcome": result.outcome.value,
        "observed_facts": list(result.observed_facts),
        "failure_code": result.failure_code,
    }
    encoded = _canonical(document)
    if len(encoded) > _MAX_RESULT_BYTES:
        raise DurableExecutorLedgerError("executor result exceeds the ledger size limit")
    digest = hashlib.sha256(b"JARVIS:EXECUTOR-RESULT:V1\x00" + encoded).hexdigest()
    state = "succeeded" if result.outcome is ExecutionOutcome.SUCCEEDED else "failed_no_effect"
    return state, encoded.decode("ascii"), digest


def _decode_result(raw: str, expected_digest: str) -> ActionExecutionResult:
    if not isinstance(raw, str) or len(raw.encode("utf-8")) > _MAX_RESULT_BYTES:
        raise DurableExecutorLedgerCorrupt("stored executor result exceeds its size limit")
    if not isinstance(expected_digest, str) or not _DIGEST_PATTERN.fullmatch(expected_digest):
        raise DurableExecutorLedgerCorrupt("stored result digest has an invalid format")
    try:
        encoded = raw.encode("ascii", errors="strict")
    except UnicodeEncodeError as exc:
        raise DurableExecutorLedgerCorrupt("stored executor result is not ASCII") from exc
    actual = hashlib.sha256(b"JARVIS:EXECUTOR-RESULT:V1\x00" + encoded).hexdigest()
    if not hmac.compare_digest(actual, expected_digest):
        raise DurableExecutorLedgerCorrupt("stored executor result digest mismatch")
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DurableExecutorLedgerCorrupt("stored executor result JSON is invalid") from exc
    if not isinstance(value, dict) or set(value) != {"outcome", "observed_facts", "failure_code"}:
        raise DurableExecutorLedgerCorrupt("stored executor result has unexpected fields")
    try:
        result = ActionExecutionResult(
            outcome=ExecutionOutcome(value["outcome"]),
            observed_facts=tuple(value["observed_facts"]),
            failure_code=value["failure_code"],
        )
    except (TypeError, ValueError) as exc:
        raise DurableExecutorLedgerCorrupt("stored executor result violates the Task Loop contract") from exc
    if result.outcome is ExecutionOutcome.UNKNOWN:
        raise DurableExecutorLedgerCorrupt("UNKNOWN cannot be stored as a final result")
    return result


class DurableIdempotencyExecutor:
    """Persist an invocation claim before calling a delegate and cache known results.

    If a previous invocation is still IN_PROGRESS or UNKNOWN, execute() returns
    UNKNOWN without calling the delegate again. A trusted reconciler may later
    call record_reconciled_result() after consulting the real external system.
    """

    def __init__(
        self,
        path: str | os.PathLike[str],
        delegate: _Executor,
        *,
        busy_timeout_ms: int = 5000,
        max_records: int = _MAX_LEDGER_RECORDS,
    ) -> None:
        self.path = Path(path)
        if not hasattr(delegate, "execute") or not callable(delegate.execute):
            raise DurableExecutorLedgerError("delegate must implement execute(action, request)")
        if isinstance(busy_timeout_ms, bool) or not isinstance(busy_timeout_ms, int) or not 1 <= busy_timeout_ms <= 60_000:
            raise DurableExecutorLedgerError("busy_timeout_ms must be in 1..60000")
        if (
            isinstance(max_records, bool)
            or not isinstance(max_records, int)
            or not 1 <= max_records <= _MAX_LEDGER_RECORDS
        ):
            raise DurableExecutorLedgerError(
                f"max_records must be in 1..{_MAX_LEDGER_RECORDS}"
            )
        self.delegate = delegate
        self.busy_timeout_ms = busy_timeout_ms
        self.max_records = max_records
        try:
            parent_existed = self.path.parent.exists()
            self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            if not parent_existed:
                os.chmod(self.path.parent, 0o700)
            parent_metadata = self.path.parent.lstat()
        except OSError as exc:
            raise DurableExecutorLedgerError("cannot secure executor-ledger directory") from exc
        if not stat.S_ISDIR(parent_metadata.st_mode) or parent_metadata.st_mode & 0o077:
            raise DurableExecutorLedgerError("executor-ledger parent must be a private directory (mode 0700)")
        if hasattr(os, "geteuid") and parent_metadata.st_uid != os.geteuid():
            raise DurableExecutorLedgerError("executor-ledger directory has an unexpected owner")

        self._prepare_database_file()
        self._initialize_schema()

    def _prepare_database_file(self) -> None:
        flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(self.path, flags, 0o600)
        except FileExistsError:
            try:
                descriptor = os.open(self.path, os.O_RDWR | getattr(os, "O_NOFOLLOW", 0))
            except OSError as exc:
                raise DurableExecutorLedgerError("cannot safely open executor-ledger file") from exc
        except OSError as exc:
            raise DurableExecutorLedgerError("cannot create executor-ledger file") from exc
        try:
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode):
                raise DurableExecutorLedgerError("executor-ledger path must be a regular file")
            if hasattr(os, "geteuid") and metadata.st_uid != os.geteuid():
                raise DurableExecutorLedgerError("executor-ledger file has an unexpected owner")
            os.fchmod(descriptor, 0o600)
        finally:
            os.close(descriptor)

    def _connect(self) -> sqlite3.Connection:
        try:
            connection = sqlite3.connect(
                self.path,
                timeout=self.busy_timeout_ms / 1000,
                isolation_level=None,
            )
            connection.execute(f"PRAGMA busy_timeout={self.busy_timeout_ms}")
            connection.execute("PRAGMA foreign_keys=ON")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute(f"PRAGMA max_page_count={_MAX_DATABASE_PAGES}")
            os.chmod(self.path, 0o600)
            return connection
        except (OSError, sqlite3.Error) as exc:
            raise DurableExecutorLedgerError("cannot open executor-ledger database") from exc

    @contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            connection.execute("BEGIN IMMEDIATE")
            yield connection
            connection.commit()
        except sqlite3.Error as exc:
            try:
                connection.rollback()
            except sqlite3.Error:
                pass
            raise DurableExecutorLedgerError("executor-ledger transaction failed") from exc
        except Exception:
            try:
                connection.rollback()
            except sqlite3.Error:
                pass
            raise
        finally:
            connection.close()

    def _initialize_schema(self) -> None:
        try:
            with self._transaction() as connection:
                current = connection.execute("PRAGMA user_version").fetchone()[0]
                if current not in (0, _SCHEMA_VERSION):
                    raise DurableExecutorLedgerCorrupt("unsupported executor-ledger schema version")
                connection.execute(
                    """
                    CREATE TABLE IF NOT EXISTS action_results (
                        idempotency_key TEXT PRIMARY KEY,
                        request_digest TEXT NOT NULL,
                        state TEXT NOT NULL CHECK (
                            state IN ('in_progress', 'unknown', 'succeeded', 'failed_no_effect')
                        ),
                        result_json TEXT,
                        result_digest TEXT,
                        created_at_ns INTEGER NOT NULL,
                        updated_at_ns INTEGER NOT NULL,
                        reconciliation_ref TEXT
                    )
                    """
                )
                connection.execute(f"PRAGMA user_version={_SCHEMA_VERSION}")
        except sqlite3.Error as exc:
            raise DurableExecutorLedgerError("cannot initialize executor-ledger schema") from exc

    @staticmethod
    def _validate_key(value: object) -> str:
        if not isinstance(value, str) or not _ID_PATTERN.fullmatch(value):
            raise DurableExecutorLedgerError("idempotency_key must be a stable 1..128 character identifier")
        return value

    @staticmethod
    def _unknown() -> ActionExecutionResult:
        return ActionExecutionResult(outcome=ExecutionOutcome.UNKNOWN)

    def _record_from_row(self, row: tuple[object, ...]) -> DurableExecutionRecord:
        (
            key, request_digest, state, created_at_ns, updated_at_ns,
            result_json, result_digest, reconciliation_ref,
        ) = row
        if not isinstance(key, str) or not _ID_PATTERN.fullmatch(key):
            raise DurableExecutorLedgerCorrupt("stored idempotency key is invalid")
        if not isinstance(request_digest, str) or not _DIGEST_PATTERN.fullmatch(request_digest):
            raise DurableExecutorLedgerCorrupt("stored request digest is invalid")
        if state not in {"in_progress", "unknown", "succeeded", "failed_no_effect"}:
            raise DurableExecutorLedgerCorrupt("stored executor state is invalid")
        if any(isinstance(item, bool) or not isinstance(item, int) or item < 0 for item in (created_at_ns, updated_at_ns)):
            raise DurableExecutorLedgerCorrupt("stored timestamps are invalid")
        if state in _FINAL_STATES:
            if result_json is None or result_digest is None:
                raise DurableExecutorLedgerCorrupt("final executor result is missing")
            result = _decode_result(result_json, result_digest)
            if (state == "succeeded") != (result.outcome is ExecutionOutcome.SUCCEEDED):
                raise DurableExecutorLedgerCorrupt("executor state and result outcome disagree")
        else:
            if result_json is not None or result_digest is not None:
                raise DurableExecutorLedgerCorrupt("non-final executor state contains a result")
            result = None
        if reconciliation_ref is not None and (
            not isinstance(reconciliation_ref, str) or not _ID_PATTERN.fullmatch(reconciliation_ref)
        ):
            raise DurableExecutorLedgerCorrupt("reconciliation reference is invalid")
        return DurableExecutionRecord(
            idempotency_key=key,
            request_digest=request_digest,
            state=state,
            created_at_ns=created_at_ns,
            updated_at_ns=updated_at_ns,
            result=result,
            result_digest=result_digest,
            reconciliation_ref=reconciliation_ref,
        )

    def lookup(self, idempotency_key: str) -> DurableExecutionRecord | None:
        key = self._validate_key(idempotency_key)
        connection = self._connect()
        try:
            row = connection.execute(
                """
                SELECT idempotency_key, request_digest, state, created_at_ns,
                       updated_at_ns, result_json, result_digest, reconciliation_ref
                FROM action_results WHERE idempotency_key=?
                """,
                (key,),
            ).fetchone()
            return None if row is None else self._record_from_row(row)
        except sqlite3.Error as exc:
            raise DurableExecutorLedgerError("cannot read executor-ledger record") from exc
        finally:
            connection.close()

    def _mark_unknown(self, key: str, request_digest: str) -> None:
        now = time.time_ns()
        with self._transaction() as connection:
            connection.execute(
                """
                UPDATE action_results
                SET state='unknown', updated_at_ns=?
                WHERE idempotency_key=? AND request_digest=? AND state='in_progress'
                """,
                (now, key, request_digest),
            )

    def _store_final(
        self,
        key: str,
        request_digest: str,
        result: ActionExecutionResult,
        *,
        reconciliation_ref: str | None = None,
    ) -> ActionExecutionResult:
        state, result_json, result_digest = _encode_result(result)
        now = time.time_ns()
        with self._transaction() as connection:
            cursor = connection.execute(
                """
                UPDATE action_results
                SET state=?, result_json=?, result_digest=?, updated_at_ns=?,
                    reconciliation_ref=?
                WHERE idempotency_key=? AND request_digest=?
                  AND state IN ('in_progress', 'unknown')
                """,
                (state, result_json, result_digest, now, reconciliation_ref, key, request_digest),
            )
            if cursor.rowcount == 1:
                return result
        current = self.lookup(key)
        if current is not None and current.request_digest == request_digest:
            if current.result is not None:
                if current.result == result:
                    return current.result
                raise DurableExecutorLedgerConflict("a different final result is already stored")
            return self._unknown()
        raise DurableExecutorLedgerConflict("executor-ledger record changed during result publication")

    def execute(
        self,
        action: PlanningAction,
        request: AuthorizationRequest,
    ) -> ActionExecutionResult:
        key = self._validate_key(getattr(request, "idempotency_key", None))
        digest = _request_digest(action, request)
        now = time.time_ns()
        try:
            with self._transaction() as connection:
                row = connection.execute(
                    """
                    SELECT idempotency_key, request_digest, state, created_at_ns,
                           updated_at_ns, result_json, result_digest, reconciliation_ref
                    FROM action_results WHERE idempotency_key=?
                    """,
                    (key,),
                ).fetchone()
                if row is not None:
                    existing = self._record_from_row(row)
                    if existing.request_digest != digest:
                        raise DurableExecutorLedgerConflict(
                            "idempotency key is already bound to a different action intent"
                        )
                    return existing.result if existing.result is not None else self._unknown()
                record_count = connection.execute(
                    "SELECT COUNT(*) FROM action_results"
                ).fetchone()[0]
                if record_count >= self.max_records:
                    raise DurableExecutorLedgerCapacity(
                        "executor idempotency ledger capacity is exhausted; do not prune unresolved keys"
                    )
                connection.execute(
                    """
                    INSERT INTO action_results (
                        idempotency_key, request_digest, state,
                        created_at_ns, updated_at_ns
                    ) VALUES (?, ?, 'in_progress', ?, ?)
                    """,
                    (key, digest, now, now),
                )
        except sqlite3.Error as exc:
            raise DurableExecutorLedgerError("cannot claim executor idempotency key") from exc

        try:
            result = self.delegate.execute(action, request)
            if not isinstance(result, ActionExecutionResult):
                self._mark_unknown(key, digest)
                return self._unknown()
            if result.outcome is ExecutionOutcome.UNKNOWN:
                self._mark_unknown(key, digest)
                return self._unknown()
        except Exception:
            # The delegate could have committed an external effect before losing its reply.
            try:
                self._mark_unknown(key, digest)
            except DurableExecutorLedgerError:
                pass
            return self._unknown()

        try:
            return self._store_final(key, digest, result)
        except DurableExecutorLedgerError:
            # Never report a durable success if the result could not be committed.
            return self._unknown()

    def record_reconciled_result(
        self,
        action: PlanningAction,
        request: AuthorizationRequest,
        result: ActionExecutionResult,
        *,
        reconciliation_ref: str,
    ) -> ActionExecutionResult:
        """Record a known result after a trusted caller checks the real target system.

        This method records the caller's assertion; it does not authenticate the
        caller or independently prove that the target system has that state.
        """
        key = self._validate_key(getattr(request, "idempotency_key", None))
        if not isinstance(reconciliation_ref, str) or not _ID_PATTERN.fullmatch(reconciliation_ref):
            raise DurableExecutorLedgerError("reconciliation_ref must be a stable identifier")
        digest = _request_digest(action, request)
        return self._store_final(
            key, digest, result, reconciliation_ref=reconciliation_ref,
        )
