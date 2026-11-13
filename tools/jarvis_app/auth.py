"""Private bearer-token authentication for the on-device JARVIS console."""
from __future__ import annotations

import hashlib
import hmac
import os
from pathlib import Path
import re
import secrets
import stat


_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")
_DIGEST_PATTERN = re.compile(r"sha256:[0-9a-f]{64}\n$")


class LocalConsoleAuthError(ValueError):
    """Authentication material is missing, malformed or unsafe to read."""


class LocalConsoleAuth:
    """Stores only a token digest; raw bootstrap tokens are returned once."""

    def __init__(self, path: str | os.PathLike[str], *, rotate: bool = False) -> None:
        self.path = Path(path).expanduser()
        self._secure_parent()
        self.bootstrap_token: str | None = None

        exists = os.path.lexists(self.path)
        if rotate or not exists:
            if exists:
                self._validate_existing_file()
            token = secrets.token_urlsafe(32)
            self._write_digest(hashlib.sha256(token.encode("ascii")).hexdigest())
            self.bootstrap_token = token
            self._digest = hashlib.sha256(token.encode("ascii")).digest()
        else:
            self._digest = self._read_digest()

    def _secure_parent(self) -> None:
        try:
            self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            metadata = self.path.parent.lstat()
            if not stat.S_ISDIR(metadata.st_mode):
                raise LocalConsoleAuthError(
                    "authentication parent must be a real directory, not a symlink"
                )
            if hasattr(os, "geteuid") and metadata.st_uid != os.geteuid():
                raise LocalConsoleAuthError(
                    "authentication directory has an unexpected owner"
                )
            if metadata.st_mode & 0o077:
                os.chmod(self.path.parent, 0o700)
        except LocalConsoleAuthError:
            raise
        except OSError as exc:
            raise LocalConsoleAuthError(
                "cannot secure authentication directory"
            ) from exc

    def _open_existing_file(self) -> int:
        descriptor = None
        try:
            flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
            descriptor = os.open(self.path, flags)
            metadata = os.fstat(descriptor)
            if not stat.S_ISREG(metadata.st_mode):
                raise LocalConsoleAuthError(
                    "authentication path must be a regular file"
                )
            if hasattr(os, "geteuid") and metadata.st_uid != os.geteuid():
                raise LocalConsoleAuthError(
                    "authentication file has an unexpected owner"
                )
            if metadata.st_mode & 0o077:
                os.fchmod(descriptor, 0o600)
            return descriptor
        except LocalConsoleAuthError:
            if descriptor is not None:
                os.close(descriptor)
            raise
        except OSError as exc:
            if descriptor is not None:
                os.close(descriptor)
            raise LocalConsoleAuthError(
                "cannot safely open authentication digest"
            ) from exc

    def _validate_existing_file(self) -> None:
        descriptor = self._open_existing_file()
        os.close(descriptor)

    def _write_digest(self, hex_digest: str) -> None:
        temporary = self.path.with_name(
            self.path.name + "." + secrets.token_hex(8) + ".tmp"
        )
        descriptor = None
        try:
            descriptor = os.open(
                temporary,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o600,
            )
            with os.fdopen(descriptor, "w", encoding="ascii") as stream:
                descriptor = None
                stream.write("sha256:" + hex_digest + "\n")
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, self.path)

            # Persist the directory entry where supported; the file itself was
            # already fsynced before the atomic replacement.
            try:
                directory_flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
                directory_fd = os.open(self.path.parent, directory_flags)
                try:
                    os.fsync(directory_fd)
                finally:
                    os.close(directory_fd)
            except OSError:
                pass
        except OSError as exc:
            if descriptor is not None:
                os.close(descriptor)
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
            raise LocalConsoleAuthError(
                "cannot persist authentication digest"
            ) from exc

    def _read_digest(self) -> bytes:
        descriptor = self._open_existing_file()
        try:
            with os.fdopen(descriptor, "r", encoding="ascii") as stream:
                raw = stream.read(73)
        except (OSError, UnicodeError) as exc:
            raise LocalConsoleAuthError(
                "cannot read authentication digest"
            ) from exc
        if not _DIGEST_PATTERN.fullmatch(raw):
            raise LocalConsoleAuthError(
                "authentication digest has an invalid format"
            )
        return bytes.fromhex(raw[7:-1])

    def verify(self, token: str | None) -> bool:
        if not isinstance(token, str) or not _TOKEN_PATTERN.fullmatch(token):
            return False
        candidate = hashlib.sha256(token.encode("ascii")).digest()
        return hmac.compare_digest(candidate, self._digest)
