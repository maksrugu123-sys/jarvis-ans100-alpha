"""Native Windows desktop shell for the local JARVIS application.

The local API runs in a background thread; pywebview hosts the UI in a
standalone WebView2 window, without launching Chrome or Edge as a browser.
"""
from __future__ import annotations

import ctypes
import json
import threading
import time
from urllib.error import URLError
from urllib.request import urlopen

from tools.jarvis_app.server import main as run_server


def _show_error(message: str) -> None:
    try:
        ctypes.windll.user32.MessageBoxW(
            None, message, "JARVIS — ошибка запуска", 0x10
        )
    except Exception:
        print(message)


def _wait_for_server(server_thread: threading.Thread, failures: list[BaseException]) -> None:
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if failures:
            raise RuntimeError(f"Локальное ядро JARVIS не запустилось: {failures[0]}")
        if not server_thread.is_alive():
            raise RuntimeError("Процесс локального ядра JARVIS неожиданно завершился.")
        try:
            with urlopen("http://127.0.0.1:8765/api/health", timeout=1) as response:
                health = json.loads(response.read().decode("utf-8"))
            if (
                health.get("status") == "ok"
                and health.get("platform") == "desktop"
                and health.get("auth_required") is False
                and health.get("auth_scheme") == "none"
            ):
                return
        except (OSError, URLError, ValueError, json.JSONDecodeError):
            pass
        time.sleep(0.5)
    raise RuntimeError(
        "JARVIS не запустил локальное ядро за 45 секунд. "
        "Подробности находятся в startup.log и startup-error.log."
    )


def main() -> int:
    try:
        import webview
    except Exception as exc:
        _show_error(
            "Не удалось загрузить встроенное окно JARVIS. "
            "Переустановите приложение из актуального ZIP.\n\n"
            f"Подробности: {exc}"
        )
        return 2

    failures: list[BaseException] = []

    def start_server() -> None:
        try:
            run_server()
        except BaseException as exc:
            failures.append(exc)

    server_thread = threading.Thread(
        target=start_server, name="jarvis-local-api", daemon=True
    )
    server_thread.start()

    try:
        _wait_for_server(server_thread, failures)
        webview.create_window(
            "JARVIS — ANS-100",
            "http://127.0.0.1:8765/",
            width=1440,
            height=920,
            min_size=(900, 640),
            background_color="#0b1119",
            text_select=True,
        )
        # WebView2 is an application runtime, not the Edge browser.
        webview.start(gui="edgechromium", debug=False)
        return 0
    except Exception as exc:
        _show_error(
            "Не удалось открыть окно приложения JARVIS.\n\n"
            "Для встроенного интерфейса требуется Microsoft Edge WebView2 Runtime. "
            "Он входит в Windows 11 и может быть установлен отдельно в Windows 10.\n\n"
            f"Подробности: {exc}"
        )
        return 3


if __name__ == "__main__":
    raise SystemExit(main())
