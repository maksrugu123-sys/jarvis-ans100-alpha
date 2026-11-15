"""Source-level regression checks for the Windows desktop package."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class WindowsPackagingContractTests(unittest.TestCase):
    def test_frozen_entrypoint_uses_desktop_server(self):
        source = (ROOT / "tools/jarvis_app/windows_entry.py").read_text(encoding="utf-8")
        self.assertIn("from tools.jarvis_app.server import main", source)
        self.assertIn("main()", source)

    def test_spec_bundles_ui_and_browser_extension_assets_without_import_sweeps(self):
        source = (ROOT / "packaging/windows/JARVIS.spec").read_text(encoding="utf-8")
        self.assertIn("tools/jarvis_app/static", source)
        self.assertIn("tools/jarvis_app/browser_extension", source)
        self.assertIn("hiddenimports = []", source)
        self.assertNotIn("from PyInstaller.utils.hooks import collect_submodules", source)
        self.assertNotIn("collect_submodules(package)", source)
        self.assertIn("console=True", source)

    def test_packaged_launcher_targets_the_bundled_executable_and_keeps_diagnostics(self):
        source = (ROOT / "start_jarvis_windows_exe.bat").read_text(encoding="utf-8")
        helper = (ROOT / "run_jarvis_core.bat").read_text(encoding="utf-8")
        self.assertIn(r"JARVIS\JARVIS.exe", source)
        self.assertIn("run_jarvis_core.bat", source)
        self.assertIn("--platform desktop", helper)
        self.assertIn("Tee-Object", helper)
        self.assertIn("startup.log", helper)
        self.assertIn("--app=%JARVIS_URL%", source)

    def test_posix_only_policy_registry_does_not_break_windows_imports(self):
        source = (ROOT / "tools/neuron_runtime/policy_registry.py").read_text(encoding="utf-8")
        self.assertNotRegex(source, r"(?m)^import fcntl$")
        self.assertIn('if os.name == "nt":', source)
        self.assertIn("requires POSIX file-locking", source)

    def test_windows_desktop_mode_does_not_require_manual_tokens(self):
        server = (ROOT / "tools/jarvis_app/server.py").read_text(encoding="utf-8")
        app = (ROOT / "tools/jarvis_app/static/app.js").read_text(encoding="utf-8")
        popup = (ROOT / "tools/jarvis_app/browser_extension/popup.js").read_text(encoding="utf-8")
        self.assertIn('if self.platform not in ("desktop", "android"):', server)
        self.assertIn('service.platform == "desktop" and urlsplit(self.path).path not in _BROWSER_EXTENSION_API_PATHS', server)
        self.assertIn('"auth_required": service.platform != "desktop"', server)
        self.assertIn('await enterConsole("")', app)
        self.assertIn('const headers = {Authorization: "Bearer " + accessToken};', popup)
        self.assertIn('jarvisAccessToken', popup)

    def test_workflow_runs_a_real_windows_runtime_smoke_test_before_release(self):
        source = (ROOT / ".github/workflows/windows-desktop.yml").read_text(encoding="utf-8")
        self.assertIn("Smoke-test packaged server on Windows", source)
        self.assertIn("/api/health", source)
        self.assertIn("run_jarvis_core.bat", source)


if __name__ == "__main__":
    unittest.main()
