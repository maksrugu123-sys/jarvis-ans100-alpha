"""Regression coverage for the confined JARVIS folder-creation capability.

Tests are authored but intentionally NOT RUN in this development session.
"""
from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.jarvis_app.workspace_files import (
    WorkspaceFolderError,
    create_workspace_folder,
    validate_folder_name,
)


class WorkspaceFolderTests(unittest.TestCase):
    def test_creates_only_a_direct_child_of_the_workspace_root(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "workspace-files"
            result = create_workspace_folder(root, "Мои проекты")
            self.assertTrue((root / "Мои проекты").is_dir())
            self.assertEqual(result["relative_path"], "workspace-files/Мои проекты")
            self.assertTrue(result["created"])

    def test_rejects_path_traversal_and_windows_path_characters(self):
        for name in ("../outside", "nested/name", r"nested\\name", "C:folder", "folder?", ".."):
            with self.subTest(name=name), self.assertRaises(WorkspaceFolderError):
                validate_folder_name(name)

    def test_rejects_reserved_and_hidden_names(self):
        for name in ("CON", "nul.txt", ".private", "folder."):
            with self.subTest(name=name), self.assertRaises(WorkspaceFolderError):
                validate_folder_name(name)

    def test_existing_folder_is_never_overwritten(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp) / "workspace-files"
            existing = root / "Projects"
            existing.mkdir(parents=True)
            marker = existing / "keep.txt"
            marker.write_text("keep", encoding="utf-8")
            with self.assertRaises(WorkspaceFolderError):
                create_workspace_folder(root, "Projects")
            self.assertEqual(marker.read_text(encoding="utf-8"), "keep")

    def test_rejects_empty_or_oversized_names(self):
        with self.assertRaises(WorkspaceFolderError):
            validate_folder_name("   ")
        with self.assertRaises(WorkspaceFolderError):
            validate_folder_name("x" * 81)


if __name__ == "__main__":
    unittest.main()
