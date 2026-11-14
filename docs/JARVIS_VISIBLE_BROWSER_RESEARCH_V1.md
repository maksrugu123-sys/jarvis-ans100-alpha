# JARVIS Visible Browser Research V1

**Status:** source implementation V0.1 on `mobile-first-offline-v1`; browser/UI/Core tests AUTHORED, NOT RUN; headed PC runtime NOT VERIFIED.  
**Date:** 2026-11-14  
**Target:** PC / desktop JARVIS  
**Primary product rule:** the browser must open as a visible foreground Chromium window. No silent headless fallback.

## 1. Current source implementation

The optional controller lives in `tools/jarvis_app/visible_browser.py` and is owned by `LocalConsoleService`. It uses Playwright >=1.48's synchronous Python API inside one dedicated worker thread, with a persistent JARVIS-only profile at:

`~/.jarvis-ans100-alpha/research-browser-profile`

The controller starts only when the user searches or opens a page. It launches Chromium with `headless=False`, disables downloads and service workers, and keeps a single dedicated page in a separate profile. Playwright is not imported at application startup; the normal local console still starts if the optional package is missing.

Install the optional dependency from the repository root:

```bash
python3 -m pip install -r requirements-browser.txt
python3 -m playwright install chromium
```

Then run the existing desktop console and authenticate in the PWA as usual. The Browser JARVIS panel performs visible search, opens selected sources, reads current page text, lets the user take over, and closes the research window.

The first search provider is Bing's public HTML search results. Search-result extraction relies on its current DOM selector and may need maintenance if the provider changes its markup.

## 2. Authenticated local API

The browser controller is available only through the existing authenticated loopback console. The server remains bound to `127.0.0.1`; all browser API operations require the console's Bearer token and the existing local-origin check.

| Method | Route | Purpose |
|---|---|---|
| GET | `/api/browser` | Browser state, current page and pause state |
| POST | `/api/browser/search` | Open visible search results and return up to 10 source records |
| POST | `/api/browser/open` | Open a selected external HTTP(S) result |
| POST | `/api/browser/read` | Read the current visible page title, URL and bounded body text |
| POST | `/api/browser/control` | Pause/resume JARVIS control for manual user takeover |
| POST | `/api/browser/stop` | Close the dedicated browser context |
| POST | `/api/browser/ask` | Ask the local model about the currently opened page and save the answer with its source URL |

Page text displayed in the UI is limited to 15,000 characters. When the user asks JARVIS a question about the current page, Core passes at most 1,800 characters of extracted page text to the local inference adapter. The source title, URL and retrieval timestamp accompany the context; the source URL is appended to the stored assistant answer. This is a bounded first slice, not a general web research agent or multi-source evidence engine.

The "Ask JARVIS" action requires the optional local model to be installed and available. Page search and reading can be used independently of model availability.

## 3. User control and page lifecycle

- Search and open operations show the active query/page in the visible browser window.
- **Take control** pauses JARVIS navigation and reading while leaving the window visible for the user. The user can manually inspect and navigate the page. **Return control** re-enables JARVIS operations.
- **Stop** closes the persistent browser context. The JARVIS profile remains on disk to preserve browser state for this dedicated profile.
- Browser close, crash, unsupported page or navigation timeout is surfaced as an error; there is no hidden/headless retry.
- The current implementation does not detect every possible manual gesture in Chromium; takeover is explicit through the PWA control.
- The current UI reads one page at a time. It does not manage a multi-tab source graph or compare conflicting evidence automatically.

## 4. Read-only boundary

The HTTP route guard permits only GET/HEAD requests, restricts target URLs to HTTP(S) on standard ports, rejects embedded URL credentials and local/private/reserved literal IPs, and checks DNS-resolved addresses for public IPs on each routed request. A separate Playwright WebSocket route rejects WebSocket connections. Downloads are disabled and service workers are blocked.

This is a first safety filter, **not a complete network sandbox**. DNS can change between validation and the browser's connection (DNS rebinding/TOCTOU); browser engines and page JavaScript still execute web content; and HTTP GET endpoints can be misused by some sites despite being conventionally read-only. Use a dedicated clean JARVIS profile, do not sign it into personal accounts, and do not treat this implementation as suitable for sensitive authenticated browsing. A future release should add OS-level egress isolation or an explicit outbound proxy with address pinning before expanding permissions.

JARVIS does not submit forms, click page controls, send messages, publish, purchase, upload files, download artifacts or execute page-provided commands. The browser search/read capability does not grant write permission. If the user manually takes control, JARVIS stays paused until the user explicitly returns control.

## 5. Untrusted page content and prompt-injection boundary

Page title, URL and extracted body are treated as untrusted external reference data. The local language adapter receives an explicit instruction to ignore page directives, role changes, requests for secrets, alternate URLs or actions, and to use the content only as evidence for the user's question. Automatic tool calling remains disabled.

This prompt defense is a mitigation, not a proof against prompt injection. The model may still misinterpret or misstate page content. The UI and saved answer include the actual source URL; users should open and verify important claims themselves. The controller does not independently validate a page's claims, publication dates, authorship or factual accuracy.

## 6. Current limitations

- Requires a desktop session capable of showing a Chromium window. Headed mode has not been verified on Windows, Linux or macOS in this change.
- Requires optional Playwright >=1.48 package and installed Chromium browser binary; neither is auto-downloaded by JARVIS.
- Bing page selectors may change; anti-bot, CAPTCHA, rate limits or search markup changes can cause search to return no sources. The controller does not bypass site restrictions.
- One active page, a maximum of 10 extracted search-result links, page text bounded to 15,000 characters, model context bounded to 1,800 characters, 25-second navigation timeout and 45-second worker request timeout.
- No screenshots, PDF extraction, image understanding, multi-source claim/evidence graph, page caching, automatic retries or source deduplication across separate search sessions.
- No automatic Task Loop/CQE dispatch, external action execution, desktop package, or Android-to-PC remote browser control.

## 7. Tests and verification

`tools/jarvis_app/test_visible_browser.py` covers URL scheme/credential/host/port and search-query validation. `tools/jarvis_app/test_jarvis_core.py` covers bounded untrusted external-page context handling. `tools/jarvis_app/test_local_inference.py` covers the instruction boundary when page text is added to the system context. Browser HTTP/UI integration tests are authored in source where applicable; all such tests are **AUTHORED, NOT RUN**.

No Playwright browser was launched, no model inference was run, no PWA session was exercised, and no Windows/macOS/Linux runtime test, Python unit test, Gradle build, APK/AAB, CI or Actions workflow was executed. This feature is **IMPLEMENTED at source level, NOT VERIFIED at runtime**.
