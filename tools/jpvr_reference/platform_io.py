"""Cross-platform positional I/O and advisory locking for JPVR files.

POSIX uses flock + pread/pwrite. Windows uses a one-byte msvcrt region
lock (exclusive for both readers and writers) and serialized seek/read/write.
The Windows lock is deliberately conservative: it sacrifices concurrent
readers rather than weakening cross-process safety.
"""
from __future__ import annotations

import errno
import os
import time
from typing import Final

_LOCK_OFFSET: Final[int] = 0
_LOCK_LENGTH: Final[int] = 1


def lock_file(fd: int, kind: int) -> None:
    """Acquire a process-wide file lock; Windows safely serializes all access."""
    if os.name == "nt":
        import msvcrt

        # msvcrt.locking() uses the descriptor's current position.
        # LK_NBLCK lets us wait without its built-in ten-second timeout.
        while True:
            os.lseek(fd, _LOCK_OFFSET, os.SEEK_SET)
            try:
                msvcrt.locking(fd, msvcrt.LK_NBLCK, _LOCK_LENGTH)
                return
            except OSError as exc:
                if exc.errno not in (errno.EACCES, errno.EAGAIN, errno.EDEADLK):
                    raise
                time.sleep(0.05)
    else:
        import fcntl

        fcntl.flock(fd, kind)


def unlock_file(fd: int) -> None:
    """Release a lock acquired by lock_file()."""
    if os.name == "nt":
        import msvcrt

        os.lseek(fd, _LOCK_OFFSET, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_UNLCK, _LOCK_LENGTH)
    else:
        import fcntl

        fcntl.flock(fd, fcntl.LOCK_UN)


def read_at_seek(fd: int, size: int, offset: int) -> bytes:
    """Seek-based positional read used by Windows and directly testable elsewhere."""
    if size < 0 or offset < 0:
        raise ValueError("size and offset must be non-negative")
    result = bytearray()
    while len(result) < size:
        try:
            os.lseek(fd, offset + len(result), os.SEEK_SET)
            part = os.read(fd, size - len(result))
        except InterruptedError:
            continue
        if not part:
            break
        result.extend(part)
    return bytes(result)


def read_at(fd: int, size: int, offset: int) -> bytes:
    """Read up to size bytes from an absolute offset without changing API semantics."""
    if size < 0 or offset < 0:
        raise ValueError("size and offset must be non-negative")
    if not hasattr(os, "pread"):
        return read_at_seek(fd, size, offset)
    result = bytearray()
    while len(result) < size:
        try:
            part = os.pread(fd, size - len(result), offset + len(result))
        except InterruptedError:
            continue
        if not part:
            break
        result.extend(part)
    return bytes(result)


def write_all_at_seek(fd: int, data: bytes, offset: int) -> None:
    """Seek-based positional write used by Windows and directly testable elsewhere."""
    if offset < 0:
        raise ValueError("offset must be non-negative")
    view = memoryview(data)
    done = 0
    while done < len(view):
        try:
            os.lseek(fd, offset + done, os.SEEK_SET)
            count = os.write(fd, view[done:])
        except InterruptedError:
            continue
        if count <= 0:
            raise OSError("short write to JPVR container")
        done += count


def write_all_at(fd: int, data: bytes, offset: int) -> None:
    """Write all bytes at an absolute offset, retrying interrupted operations."""
    if offset < 0:
        raise ValueError("offset must be non-negative")
    if not hasattr(os, "pwrite"):
        write_all_at_seek(fd, data, offset)
        return
    view = memoryview(data)
    done = 0
    while done < len(view):
        try:
            count = os.pwrite(fd, view[done:], offset + done)
        except InterruptedError:
            continue
        if count <= 0:
            raise OSError("short write to JPVR container")
        done += count


def fsync_parent(path: str) -> None:
    """Best-effort portable parent-directory durability.

    POSIX can fsync an opened directory. The Python standard library does not
    expose an equivalent portable Windows directory flush; on Windows the
    file itself is still fsynced, while directory-entry durability is provided
    by the filesystem/OS and is not claimed as an explicit directory fsync.
    """
    if os.name == "nt":
        return
    parent = os.path.dirname(path) or "."
    fd = os.open(parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(fd)
    finally:
        os.close(fd)
