"""Source-level regression checks for the Windows desktop package.

These checks are intentionally not run as part of authoring the package.
"""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class WindowsPackagingContractTests(unittest.TestCase):
    def test_frozen_entrypoint_uses_desktop_server(self):
        source = (ROOT / "tools/jarvis_app/windows_entry.py").read_text(encoding="utf-8")
        self.assertIn("from tools.jarvis_app.server import main", source)
        self.assertIn('main()', source)

    def test_spec_bundles_ui_and_browser_extension_assets(self):
        source = (ROOT / "packaging/windows/JARVIS.spec").read_text(encoding="utf-8")
        self.assertIn("tools/jarvis_app/static", source)
        self.assertIn("tools/jarvis_app/browser_extension", source)
        self.assertIn('console=True', source)

    def test_packaged_launcher_targets_the_bundled_executable(self):
        source = (ROOT / "start_jarvis_windows_exe.bat").read_text(encoding="utf-8")
        self.assertIn(r"JARVIS\JARVIS.exe", source)
        self.assertIn("--platform desktop", source)
        self.assertIn("--app=%JARVIS_URL%", source)


if __name__ == "__main__":
    unittest.main()
