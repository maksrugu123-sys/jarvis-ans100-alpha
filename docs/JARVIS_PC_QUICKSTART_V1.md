# JARVIS PC Quick Start V1

**Status:** source-level desktop development entry point  
**Date:** 2026-11-13

This guide describes the existing Python local console for Linux/macOS and the current Windows 10/11 compatibility work. An optional visible Chromium research controller is now present at source level; it requires a separately installed Playwright package and Chromium browser. A cross-platform JPVR I/O adapter is present in the source, but Windows execution has not yet been verified. This is not a claim of a finished Windows release, conversational assistant, or native desktop executable.

## What currently works at source level

- Python local-console process.
- Local browser/PWA interface with the shared-format memory manager (create/search/list/delete).
- Local JPVR task/session registry.
- Separate JPVR shared workspace for conversations, explicit memories, task summaries and non-secret settings.
- Authenticated workspace API V1 with revision-based conflict detection.
- Encrypted `.jws` workspace export/import in the PWA, with merge preview, explicit confirmation and no silent overwrites.
- Separate encrypted `.jwb` workspace backup and full replacement restore, with integrity validation, current-revision backup acknowledgement, preview, explicit confirmation and server-side CAS.
- Bounded symbolic reasoning preview using structured facts and actions.
- Optional visible Chromium research panel: search, open and read page text; pause automation for manual user takeover; ask the local model about the current page with the source URL saved alongside the answer.
- Bearer-token protected state and mutation routes.

An optional LiteRT-LM source adapter for Qwen3-0.6B INT4 no-think inference exists, and the local conversation route is wired through JARVIS Core. The adapter requires an explicitly supplied model file; successful inference and natural-language response quality have not been runtime-verified. Not yet delivered: verified production conversation quality, production task execution, offline voice, native Windows/macOS/Linux package, multi-source evidence validation, secure network isolation for untrusted pages, or automatic synchronization with Android.

## Requirements

- A supported Python 3 installation.
- A local copy of this repository.
- A modern browser.

The repository's Python modules use modern Python syntax. The minimum supported Python version has not yet been formally declared or verified across Windows, macOS, and Linux. If startup fails, record the exact Python version and error instead of changing dependencies blindly.

## Windows 10 and Windows 11 compatibility

The JPVR storage code now routes file operations through `tools/jpvr_reference/platform_io.py`:

- POSIX uses `flock` and native positional I/O.
- Windows uses a one-byte `msvcrt` file-region lock. It deliberately serializes readers and writers instead of pretending Windows has the same shared-lock behavior.
- Windows opens JPVR descriptors in binary mode and uses seek-based positional reads/writes while the container lock is held.
- The Python standard library has no portable Windows equivalent for POSIX parent-directory `fsync`; Windows currently flushes file contents, but the code does not claim an explicit directory-entry durability barrier.

The seek-based I/O fallback has dedicated source tests, including a Windows-only cross-process region-lock regression. The token store also has a Windows-only regression for rotating legacy read-only digest files. These tests are authored but have not been run on Windows. The Windows token-digest adapter now avoids applying POSIX chmod assumptions and relies on inherited Windows profile ACLs; the standard library code does not audit or harden those ACLs. **Neither Windows 10 nor Windows 11 has been executed or verified yet.** Until the full JPVR storage suite and a real Windows 10/11 smoke test pass, treat Windows runtime support as experimental and do not keep the only copy of important data in a Windows JPVR container.

## Start on Windows 10 or Windows 11

Open PowerShell in the repository directory:

    py -3 --version
    py -3 -m tools.jarvis_app --port 8765 --platform desktop

If the Python launcher is unavailable, use `python -m tools.jarvis_app --port 8765 --platform desktop` after confirming that `python` points to a supported Python 3 installation. Then open `http://127.0.0.1:8765/` in a browser on the same PC and enter the one-time token printed in PowerShell. Keep the terminal open while using JARVIS.

The JPVR implementation is intended to use the same adapter on Windows 10 and 11, but neither OS has been run in this development environment. Before storing important information, verify that `%USERPROFILE%\\.jarvis-ans100-alpha` inherits a private ACL for the current Windows account. The application does not yet inspect or configure Windows ACL entries.

## Start on Linux or macOS

Open a terminal in the repository directory:

    python3 --version
    python3 -m tools.jarvis_app --port 8765 --platform desktop

## Optional visible research browser

The PC PWA includes a source-level visible browser panel. The browser opens a separate foreground Chromium window with a dedicated JARVIS profile; it is not a headless scraper and does not reuse your everyday browser profile.

From the repository root, install the optional package and browser binary:

    python3 -m pip install -r requirements-browser.txt
    python3 -m playwright install chromium

On Windows, use `py -3 -m pip install -r requirements-browser.txt` and `py -3 -m playwright install chromium`. A graphical desktop session must be available. JARVIS does not automatically install a browser or run in headless mode.

After starting the local console and signing in to the PWA, use the **Браузер JARVIS** panel:
1. Enter a search query. Chromium opens visibly and the PWA lists the extracted source links.
2. Open a result, then use **Прочитать открытую страницу** to display the extracted text.
3. Enter a question and choose **Спросить JARVIS**. This requires an installed and available local language model. The model receives up to 1,800 characters of page text as explicitly untrusted context, and the saved answer includes the page URL.
4. Choose **Забрать управление** to pause JARVIS while you interact with the visible window; choose **Вернуть управление JARVIS** when finished.
5. Choose **Закрыть браузер** to close the dedicated browser context.

The browser uses the separate profile under `~/.jarvis-ans100-alpha/research-browser-profile` (or the corresponding data directory on Windows). It is designed for clean read-only research. Do not sign into personal accounts or use it for sensitive authenticated browsing. It allows only GET/HEAD network requests, blocks WebSocket connections, downloads and service workers, and applies hostname/IP/port checks; these are best-effort filters, not a complete network sandbox against DNS rebinding or browser-engine vulnerabilities. Page scripts still run. JARVIS does not submit forms, send messages, make purchases, upload files or perform external actions.

If Playwright or Chromium is missing, the browser feature reports an error and the rest of the local console remains available. Search markup may change; unsupported pages, anti-bot checks or timeouts can cause search/reading to fail. The visible browser is source-implemented but its headed runtime, manual takeover and model-assisted page answers have not been executed or verified on Windows/Linux/macOS.

## First launch

1. The server prints a one-time 43-character access token.
2. Save it in a password manager or another private location. Do not paste it into public chats or commit it to Git.
3. Open http://127.0.0.1:8765/ in the same PC browser.
4. Enter the token on the PWA sign-in screen.
5. Keep the terminal process running while using the local console.
6. Stop the server with Ctrl+C.

The default data and token-digest paths are under the current user's home directory:

- ~/.jarvis-ans100-alpha/local-console.jpvr (task/session lifecycle)
- ~/.jarvis-ans100-alpha/workspace.jpvr (shared workspace/memory)
- ~/.jarvis-ans100-alpha/local-console.jpvr.access-token

The server creates and protects its private auth directory/files. Use encrypted `.jws` to merge workspace data between devices. Use a separate `.jwb` to make a point-in-time password-encrypted backup and restore the complete shared workspace after checking the preview and acknowledging a separately saved backup of the current revision. JWB contains only conversations, explicit memories, task summaries and non-secret settings; it does not contain `local-console.jpvr`, Android drafts, auth tokens, Keystore keys or neural checkpoints. The separate task/session file `local-console.jpvr` still requires its own stopped-server file backup. Do not manually edit JPVR bytes.

If the token is lost, stop the server and restart it with:

    python -m tools.jarvis_app --port 8765 --rotate-token

Use python3 instead of python on systems where that is the configured interpreter. Token rotation revokes the previous token and prints a replacement once.

## Local-only security boundary

The service binds to 127.0.0.1. Keep it that way. Do not expose it through 0.0.0.0, a LAN address, port forwarding, or a public tunnel. The current Bearer token protects local API access but is not a trusted human identity or permission to execute external actions.

The PWA and Android Compose client use the same API contract when they connect to a compatible local service. Workspace routes are GET/POST `/api/workspace` plus POST `/api/workspace/restore`, use Bearer authentication and compare-and-swap revisions, and are bounded to 16 MiB. On Termux, launch with `python3 -m tools.jarvis_app --port 8765 --platform android`; on a PC use `--platform desktop`. The Android client currently targets 127.0.0.1 on the phone itself; it does not connect to a PC-hosted service. Manual encrypted `.jws` merge transfer and `.jwb` backup/restore are implemented at source level in both UIs. Browser, Android, Windows and cross-platform verification remain pending. See [Shared Workspace & Memory V1](JARVIS_SHARED_WORKSPACE_MEMORY_V1.md) and [Workspace Backup & Restore V1](JARVIS_WORKSPACE_BACKUP_RESTORE_V1.md). Cross-device pairing and automatic synchronization are not implemented.

## Troubleshooting

- **Module not found:** make sure the terminal's current directory is the repository root.
- **Port already in use:** stop the other local console or choose another port, then open the matching local URL.
- **Token rejected:** verify that the token belongs to this data/auth profile; rotate it only if necessary.
- **Storage error:** stop the server and preserve a copy of the JPVR file before attempting recovery. Do not delete or overwrite the only copy.
- **Browser page unavailable:** confirm the server is still running and open the exact 127.0.0.1 URL printed by the process.

## Verification status

This is a documented launch procedure based on the current CLI source. It has not been executed or verified on Linux or macOS. Windows 10/11 support is experimental and has not been runtime-verified. No package, installer, desktop executable, build, test, or performance measurement is claimed.
