"""Focused security regression tests for local-console bearer authentication."""
from __future__ import annotations

import hashlib
from pathlib import Path
import re
import tempfile
import unittest

from tools.jarvis_app.auth import LocalConsoleAuth, LocalConsoleAuthError


class LocalConsoleAuthTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.path = self.root / "private" / "access-token"

    def test_first_start_creates_one_time_token_and_private_digest_file(self):
        auth = LocalConsoleAuth(self.path)
        token = auth.bootstrap_token

        self.assertIsNotNone(token)
        self.assertRegex(token, re.compile(r"^[A-Za-z0-9_-]{43}$"))
        self.assertTrue(auth.verify(token))
        self.assertEqual(self.path.read_text(encoding="ascii"),
                         "sha256:" + hashlib.sha256(token.encode("ascii")).hexdigest() + "\n")
        self.assertNotIn(token, self.path.read_text(encoding="ascii"))
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)
        self.assertEqual(self.path.parent.stat().st_mode & 0o777, 0o700)

    def test_existing_digest_does_not_reveal_bootstrap_token_again(self):
        first = LocalConsoleAuth(self.path)
        token = first.bootstrap_token
        second = LocalConsoleAuth(self.path)

        self.assertIsNone(second.bootstrap_token)
        self.assertTrue(second.verify(token))
        self.assertFalse(second.verify(None))

    def test_rotation_invalidates_old_token_and_returns_new_token_once(self):
        first = LocalConsoleAuth(self.path)
        old_token = first.bootstrap_token
        rotated = LocalConsoleAuth(self.path, rotate=True)
        new_token = rotated.bootstrap_token

        self.assertIsNotNone(new_token)
        self.assertNotEqual(old_token, new_token)
        self.assertFalse(rotated.verify(old_token))
        self.assertTrue(rotated.verify(new_token))
        reopened = LocalConsoleAuth(self.path)
        self.assertIsNone(reopened.bootstrap_token)
        self.assertTrue(reopened.verify(new_token))

    def test_malformed_tokens_are_rejected_without_raising(self):
        auth = LocalConsoleAuth(self.path)
        for candidate in (None, "", "x" * 42, "x" * 44, "contains spaces", 123):
            self.assertFalse(auth.verify(candidate))

    def test_malformed_digest_file_fails_closed(self):
        self.path.parent.mkdir(mode=0o700, parents=True)
        self.path.write_text("not-a-digest\n", encoding="ascii")
        with self.assertRaises(LocalConsoleAuthError):
            LocalConsoleAuth(self.path)

    def test_symlink_authentication_path_is_rejected(self):
        self.path.parent.mkdir(mode=0o700, parents=True)
        target = self.root / "target"
        target.write_text("sha256:" + "0" * 64 + "\n", encoding="ascii")
        try:
            self.path.symlink_to(target)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable on this platform")
        with self.assertRaises(LocalConsoleAuthError):
            LocalConsoleAuth(self.path)

    def test_authentication_digest_is_bound_to_exact_token(self):
        auth = LocalConsoleAuth(self.path)
        token = auth.bootstrap_token
        changed = token[:-1] + ("A" if token[-1] != "A" else "B")
        self.assertFalse(auth.verify(changed))
        self.assertTrue(auth.verify(token))



    def test_existing_directory_and_digest_permissions_are_repaired(self):
        first = LocalConsoleAuth(self.path)
        token = first.bootstrap_token
        self.path.parent.chmod(0o755)
        self.path.chmod(0o644)

        reopened = LocalConsoleAuth(self.path)

        self.assertTrue(reopened.verify(token))
        self.assertEqual(self.path.parent.stat().st_mode & 0o777, 0o700)
        self.assertEqual(self.path.stat().st_mode & 0o777, 0o600)


    def test_symlink_parent_directory_is_rejected_without_chmodding_target(self):
        real_parent = self.root / "real-private"
        real_parent.mkdir(mode=0o755)
        original_mode = real_parent.stat().st_mode & 0o777
        alias = self.root / "parent-alias"
        try:
            alias.symlink_to(real_parent, target_is_directory=True)
        except (OSError, NotImplementedError):
            self.skipTest("directory symlinks are unavailable on this platform")

        with self.assertRaises(LocalConsoleAuthError):
            LocalConsoleAuth(alias / "access-token")

        self.assertEqual(real_parent.stat().st_mode & 0o777, original_mode)

    def test_dangling_symlink_is_not_treated_as_a_missing_token_file(self):
        self.path.parent.mkdir(mode=0o700, parents=True)
        missing_target = self.root / "not-created"
        try:
            self.path.symlink_to(missing_target)
        except (OSError, NotImplementedError):
            self.skipTest("symlinks are unavailable on this platform")

        with self.assertRaises(LocalConsoleAuthError):
            LocalConsoleAuth(self.path)

        self.assertTrue(self.path.is_symlink())
        self.assertFalse(missing_target.exists())


if __name__ == "__main__":
    unittest.main()
