"""Visible, read-only browser research for the local PC JARVIS console.

The browser is headed (visible), uses a dedicated persistent profile, and all Playwright
objects are confined to one worker thread. The initial controller only issues GET/HEAD
requests, rejects local/private URL targets and never submits forms or downloads files.
"""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import ipaddress
from pathlib import Path
import re
import socket
from threading import Lock
from urllib.parse import quote_plus, urlsplit, urlunsplit
import uuid


MAX_QUERY_CHARS = 500
MAX_PAGE_TEXT_CHARS = 15_000
MAX_SEARCH_RESULTS = 10
_NAVIGATION_TIMEOUT_MS = 25_000
_QUERY_PATTERN = re.compile(r"\s+")


class VisibleBrowserResearchError(ValueError):
    """Invalid or unavailable read-only browser research operation."""


class VisibleBrowserUnavailable(VisibleBrowserResearchError):
    """Optional browser dependency or browser binary is unavailable."""


def validate_external_url(value: object) -> str:
    """Validate a direct external HTTP(S) navigation target without opening it."""
    if not isinstance(value, str):
        raise VisibleBrowserResearchError("URL must be text")
    raw = value.strip()
    if not raw or len(raw) > 2_048 or any(ord(char) < 0x20 for char in raw):
        raise VisibleBrowserResearchError("URL is empty, too long or contains control characters")
    try:
        parsed = urlsplit(raw)
        scheme = parsed.scheme.lower()
        host = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise VisibleBrowserResearchError("URL has an invalid authority or port") from exc
    if scheme not in {"http", "https"} or not host:
        raise VisibleBrowserResearchError("Only external HTTP and HTTPS pages are allowed")
    if parsed.username is not None or parsed.password is not None:
        raise VisibleBrowserResearchError("URLs containing embedded credentials are not allowed")
    host = host.rstrip(".").lower()
    if (
        host == "localhost"
        or host.endswith((".localhost", ".local", ".internal", ".lan"))
        or host == "metadata.google.internal"
    ):
        raise VisibleBrowserResearchError("Local and internal network targets are blocked")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        try:
            canonical_host = host.encode("idna").decode("ascii")
        except UnicodeError as exc:
            raise VisibleBrowserResearchError("URL hostname is invalid") from exc
        if "." not in canonical_host or any(not label or len(label) > 63 for label in canonical_host.split(".")):
            raise VisibleBrowserResearchError("URL must use a public fully qualified hostname")
        host_part = canonical_host
    else:
        if not address.is_global:
            raise VisibleBrowserResearchError("Private, loopback and reserved IP addresses are blocked")
        host_part = f"[{host}]" if address.version == 6 else host
    allowed_port = 80 if scheme == "http" else 443
    if port is not None and port != allowed_port:
        raise VisibleBrowserResearchError("Only standard HTTP/HTTPS ports are allowed")
    netloc = host_part if port is None or port == allowed_port else f"{host_part}:{port}"
    path = parsed.path or "/"
    return urlunsplit((scheme, netloc, path, parsed.query, ""))


def validate_search_query(value: object) -> str:
    if not isinstance(value, str):
        raise VisibleBrowserResearchError("Search query must be text")
    clean = _QUERY_PATTERN.sub(" ", value).strip()
    if not clean or len(clean) > MAX_QUERY_CHARS or "\x00" in clean:
        raise VisibleBrowserResearchError(f"Search query must contain 1..{MAX_QUERY_CHARS} characters")
    return clean


class VisibleBrowserResearchService:
    """One headed Chromium profile controlled from a dedicated Playwright worker."""

    def __init__(self, profile_dir: str | Path | None = None) -> None:
        default_profile = Path.home() / ".jarvis-ans100-alpha" / "research-browser-profile"
        self.profile_dir = Path(profile_dir).expanduser() if profile_dir is not None else default_profile
        self._executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="jarvis-visible-browser")
        self._call_lock = Lock()
        self._closed = False
        self._playwright = None
        self._context = None
        self._page = None
        self._paused = False
        self._last_query: str | None = None
        self._last_sources: list[dict[str, str]] = []

    def _call(self, function, *args):
        with self._call_lock:
            if self._closed:
                raise VisibleBrowserResearchError("Visible browser service is closed")
            try:
                return self._executor.submit(function, *args).result(timeout=45)
            except VisibleBrowserResearchError:
                raise
            except Exception as exc:
                raise VisibleBrowserResearchError("Visible browser operation failed; inspect the visible browser window") from exc

    def _ensure_browser(self) -> None:
        if self._context is not None:
            return
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:
            raise VisibleBrowserUnavailable(
                "Visible browser is optional. Install requirements-browser.txt and run 'playwright install chromium'."
            ) from exc
        try:
            self.profile_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            self._playwright = sync_playwright().start()
            self._context = self._playwright.chromium.launch_persistent_context(
                str(self.profile_dir),
                headless=False,
                accept_downloads=False,
                service_workers="block",
            )
            self._context.route("**/*", self._guard_request)
            self._context.route_web_socket(
                "**/*",
                lambda websocket: websocket.close(
                    code=1008,
                    reason="WebSocket connections are disabled in read-only research mode",
                ),
            )
            pages = self._context.pages
            self._page = pages[0] if pages else self._context.new_page()
            self._page.set_default_navigation_timeout(_NAVIGATION_TIMEOUT_MS)
            self._paused = False
        except Exception as exc:
            self._shutdown_browser()
            raise VisibleBrowserUnavailable(
                "Не удалось открыть видимый Chromium. Установите Playwright и браузер Chromium; проверьте, что среда поддерживает графическое окно."
            ) from exc

    @staticmethod
    def _guard_request(route) -> None:
        """Permit only bounded read-only navigation to syntactically public HTTP(S) URLs."""
        try:
            if route.request.method.upper() not in {"GET", "HEAD"}:
                route.abort()
                return
            clean_url = validate_external_url(route.request.url)
            parsed = urlsplit(clean_url)
            host = parsed.hostname
            port = parsed.port or (443 if parsed.scheme == "https" else 80)
            if host is None:
                raise VisibleBrowserResearchError("URL hostname is missing")
            # Resolve every request as well as checking literal IPs. This blocks
            # ordinary DNS-to-private-address SSRF, though it is not a full
            # network sandbox against DNS rebinding or browser implementation bugs.
            addresses = socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
            if not addresses or any(
                not ipaddress.ip_address(item[4][0].split("%", 1)[0]).is_global
                for item in addresses
            ):
                raise VisibleBrowserResearchError("The URL resolves to a private or reserved address")
            route.continue_()
        except Exception:
            try:
                route.abort()
            except Exception:
                pass

    def search(self, query: object) -> dict[str, object]:
        clean = validate_search_query(query)
        return self._call(self._search, clean)

    def _search(self, query: str) -> dict[str, object]:
        self._ensure_browser()
        if self._paused:
            raise VisibleBrowserResearchError("Управление браузером передано пользователю. Сначала верните управление JARVIS.")
        search_url = "https://www.bing.com/search?q=" + quote_plus(query)
        try:
            self._page.goto(search_url, wait_until="domcontentloaded", timeout=_NAVIGATION_TIMEOUT_MS)
            results = self._page.locator("li.b_algo h2 a").evaluate_all(
                """anchors => anchors.map(a => ({
                    title: (a.innerText || a.textContent || "").trim(),
                    url: a.href
                }))"""
            )
        except Exception as exc:
            raise VisibleBrowserResearchError("Не удалось выполнить поиск в видимом браузере") from exc
        sources: list[dict[str, str]] = []
        seen: set[str] = set()
        for item in results:
            if not isinstance(item, dict):
                continue
            title = "".join(char for char in str(item.get("title", "")) if ord(char) >= 0x20 and ord(char) != 0x7F).strip()[:300]
            try:
                url = validate_external_url(item.get("url"))
            except VisibleBrowserResearchError:
                continue
            if not title or url in seen or "bing.com/" in urlsplit(url).netloc:
                continue
            seen.add(url)
            sources.append({
                "source_id": uuid.uuid4().hex,
                "title": title,
                "url": url,
            })
            if len(sources) >= MAX_SEARCH_RESULTS:
                break
        self._last_query = query
        self._last_sources = sources
        return {
            "query": query,
            "browser_open": True,
            "paused": self._paused,
            "page": self._page_state(),
            "sources": sources,
            "retrieved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "scope": "visible_read_only_browser_search",
        }

    def open(self, url: object) -> dict[str, object]:
        clean = validate_external_url(url)
        return self._call(self._open, clean)

    def _open(self, url: str) -> dict[str, object]:
        self._ensure_browser()
        if self._paused:
            raise VisibleBrowserResearchError("Управление браузером передано пользователю. Сначала верните управление JARVIS.")
        try:
            self._page.goto(url, wait_until="domcontentloaded", timeout=_NAVIGATION_TIMEOUT_MS)
        except Exception as exc:
            raise VisibleBrowserResearchError("Не удалось открыть страницу в видимом браузере") from exc
        return {
            "browser_open": True,
            "paused": self._paused,
            "page": self._page_state(),
            "retrieved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        }

    def read(self) -> dict[str, object]:
        return self._call(self._read)

    def _read(self) -> dict[str, object]:
        if self._page is None or self._context is None:
            raise VisibleBrowserResearchError("Сначала откройте видимый браузер и страницу")
        if self._paused:
            raise VisibleBrowserResearchError("JARVIS не читает страницу, пока управление передано пользователю")
        try:
            title = "".join(char for char in self._page.title() if ord(char) >= 0x20 and ord(char) != 0x7F).strip()[:300]
            url = validate_external_url(self._page.url)
            body = self._page.locator("body").inner_text(timeout=10_000)
        except Exception as exc:
            raise VisibleBrowserResearchError("Не удалось прочитать текущую страницу") from exc
        clean_text = body.replace("\x00", "").strip()[:MAX_PAGE_TEXT_CHARS]
        return {
            "title": title,
            "url": url,
            "text": clean_text,
            "truncated": len(body) > len(clean_text),
            "retrieved_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "scope": "visible_read_only_page_text_untrusted",
        }

    def set_paused(self, paused: object) -> dict[str, object]:
        if not isinstance(paused, bool):
            raise VisibleBrowserResearchError("paused must be boolean")
        return self._call(self._set_paused, paused)

    def _set_paused(self, paused: bool) -> dict[str, object]:
        if self._context is None:
            raise VisibleBrowserResearchError("Видимый браузер ещё не запущен")
        self._paused = paused
        return {
            "browser_open": True,
            "paused": self._paused,
            "page": self._page_state(),
        }

    def stop(self) -> dict[str, object]:
        return self._call(self._stop)

    def _stop(self) -> dict[str, object]:
        self._shutdown_browser()
        self._paused = False
        self._last_query = None
        self._last_sources = []
        return {"browser_open": False, "paused": False, "page": None}

    def status(self) -> dict[str, object]:
        return self._call(self._status)

    def _status(self) -> dict[str, object]:
        return {
            "browser_open": self._context is not None,
            "paused": self._paused,
            "page": self._page_state(),
            "last_query": self._last_query,
            "source_count": len(self._last_sources),
            "scope": "visible_read_only_browser",
        }

    def _page_state(self) -> dict[str, str] | None:
        if self._page is None or self._context is None:
            return None
        try:
            return {"title": self._page.title()[:300], "url": self._page.url[:4_096]}
        except Exception:
            return {"title": "", "url": ""}

    def _shutdown_browser(self) -> None:
        context, playwright = self._context, self._playwright
        self._page = None
        self._context = None
        self._playwright = None
        if context is not None:
            try:
                context.close()
            except Exception:
                pass
        if playwright is not None:
            try:
                playwright.stop()
            except Exception:
                pass

    def close(self) -> None:
        with self._call_lock:
            if self._closed:
                return
            try:
                self._executor.submit(self._shutdown_browser).result(timeout=10)
            except Exception:
                pass
            self._closed = True
            self._executor.shutdown(wait=False, cancel_futures=True)
