"""Source-level regression checks for the Windows desktop package."""
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]


class WindowsPackagingContractTests(unittest.TestCase):
    def test_frozen_entrypoint_uses_desktop_server(self):
        source = (ROOT / "tools/jarvis_app/windows_entry.py").read_text(encoding="utf-8")
        self.assertIn("from tools.jarvis_app.windows_desktop import main", source)
        self.assertIn("--server-only", source)
        self.assertIn("main()", source)

    def test_windows_app_uses_embedded_webview_not_external_browser(self):
        source = (ROOT / "tools/jarvis_app/windows_desktop.py").read_text(encoding="utf-8")
        self.assertIn("webview.create_window(", source)
        self.assertIn('webview.start(gui="edgechromium"', source)
        self.assertIn("http://127.0.0.1:8765/", source)
        self.assertNotIn("webbrowser.open", source)
        self.assertNotIn("Start-Process", source)

    def test_spec_bundles_ui_and_browser_extension_assets_without_import_sweeps(self):
        source = (ROOT / "packaging/windows/JARVIS.spec").read_text(encoding="utf-8")
        self.assertIn("tools/jarvis_app/static", source)
        self.assertIn("tools/jarvis_app/browser_extension", source)
        self.assertIn('"webview.platforms.edgechromium"', source)
        self.assertIn('"webview.platforms.winforms"', source)
        self.assertIn('"clr_loader"', source)
        self.assertNotIn("from PyInstaller.utils.hooks import collect_submodules", source)
        self.assertNotIn("collect_submodules(package)", source)
        self.assertIn("console=True", source)

    def test_packaged_launcher_has_one_clear_entrypoint_and_persistent_diagnostics(self):
        source = (ROOT / "start_jarvis_windows_exe.bat").read_text(encoding="utf-8")
        self.assertIn(r"JARVIS\JARVIS.exe", source)
        self.assertIn("Start-Process", source)
        self.assertIn("/api/health", source)
        self.assertIn("auth_required -eq $false", source)
        self.assertIn("auth_scheme -eq 'none'", source)
        self.assertIn("startup.log", source)
        self.assertIn("Нажмите Enter", source)
        self.assertIn("LAUNCHER ERROR:", source)
        self.assertIn("[ОШИБКА ЗАПУСКА JARVIS]", source)
        self.assertIn("startup-error.log", source)
        self.assertNotIn("run_jarvis_core.bat", source)

    def test_posix_only_policy_registry_does_not_break_windows_imports(self):
        source = (ROOT / "tools/neuron_runtime/policy_registry.py").read_text(encoding="utf-8")
        self.assertNotRegex(source, r"(?m)^import fcntl$")
        self.assertIn('if os.name == "nt":', source)
        self.assertIn("requires POSIX file-locking", source)

    def test_legacy_windows_launcher_does_not_request_tokens_or_accept_old_servers(self):
        source = (ROOT / "start_jarvis_windows.bat").read_text(encoding="utf-8")
        self.assertIn("auth_required -eq $false", source)
        self.assertIn("auth_scheme -eq 'none'", source)
        self.assertIn("no token is needed", source.lower())
        self.assertNotIn("Enter the one-time access token", source)

    def test_windows_desktop_mode_does_not_require_manual_tokens(self):
        server = (ROOT / "tools/jarvis_app/server.py").read_text(encoding="utf-8")
        app = (ROOT / "tools/jarvis_app/static/app.js").read_text(encoding="utf-8")
        popup = (ROOT / "tools/jarvis_app/browser_extension/popup.js").read_text(encoding="utf-8")
        self.assertIn('if platform not in ("desktop", "android"):', server)
        self.assertIn("self.auth = None", server)
        self.assertIn('"auth_required": False, "auth_scheme": "none"', server)
        self.assertIn("def _require_auth(self) -> bool:", server)
        self.assertIn('await enterConsole("")', app)
        self.assertIn('if service.platform == "desktop" and path in _BROWSER_EXTENSION_API_PATHS and not extension_request:', server)
        self.assertNotIn('Authorization: "Bearer " + accessToken', popup)
        self.assertNotIn('jarvisAccessToken', popup)
        self.assertNotIn('id="token"', (ROOT / "tools/jarvis_app/browser_extension/popup.html").read_text(encoding="utf-8"))
        self.assertIn('health.auth_required !== false', app)
        self.assertNotIn('#auth-panel', app)
        self.assertIn('JARVIS подключён · локальный режим без токена', app)
        self.assertIn('jarvis-local-console-v24', (ROOT / "tools/jarvis_app/static/sw.js").read_text(encoding="utf-8"))
        html = (ROOT / "tools/jarvis_app/static/index.html").read_text(encoding="utf-8")
        self.assertNotIn('auth-panel', html)
        self.assertNotIn('подключите расширение токеном из окна JARVIS Core', html)


    def test_windows_10_and_11_compatibility_matrix_is_required_before_release(self):
        workflow = (ROOT / ".github/workflows/windows-desktop.yml").read_text(encoding="utf-8")
        compatibility = workflow.split("  build-windows:", 1)[0]
        self.assertIn("windows-2022", compatibility)
        self.assertIn("windows-2025", compatibility)
        self.assertIn("needs: windows-compatibility", workflow)
        docs = (ROOT / "docs/JARVIS_WINDOWS_COMPATIBILITY.md").read_text(encoding="utf-8")
        self.assertIn("Windows 10 version 22H2", docs)
        self.assertIn("Windows 11 versions 23H2 and 24H2", docs)
        self.assertIn("Windows on ARM is not yet verified", docs)

    def test_workflow_runs_a_real_windows_runtime_smoke_test_before_release(self):
        source = (ROOT / ".github/workflows/windows-desktop.yml").read_text(encoding="utf-8")
        self.assertIn("Smoke-test packaged server on Windows", source)
        self.assertIn('"pywebview>=6.0,<7"', source)
        self.assertIn('"--server-only"', source)
        self.assertIn("/api/health", source)
        self.assertIn('Copy-Item "start_jarvis_windows_exe.bat" "dist/Запустить JARVIS.bat"', source)
        self.assertNotIn('Copy-Item "run_jarvis_core.bat"', source)


if __name__ == "__main__":
    unittest.main()
