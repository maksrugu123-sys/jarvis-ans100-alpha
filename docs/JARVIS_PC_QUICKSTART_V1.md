# JARVIS PC Quick Start V1

**Status:** source-level desktop development entry point  
**Date:** 2026-11-13

This guide describes the existing Python local console for Linux/macOS and the current Windows 10/11 compatibility work. A cross-platform JPVR I/O adapter is now present in the source, but Windows execution has not yet been verified. This is not a claim of a finished Windows release, conversational assistant, or native desktop executable.

## What currently works at source level

- Python local-console process.
- Local browser/PWA interface with the shared-format memory manager (create/search/list/delete).
- Local JPVR task/session registry.
- Separate JPVR shared workspace for conversations, explicit memories, task summaries and non-secret settings.
- Authenticated workspace API V1 with revision-based conflict detection.
- Encrypted `.jws` workspace export/import in the PWA, with merge preview, explicit confirmation and no silent overwrites.
- Bounded symbolic reasoning preview using structured facts and actions.
- Bearer-token protected state and mutation routes.

Not yet delivered: integrated offline language model, full natural-language conversation, production task execution, offline voice, native Windows/macOS/Linux package, or automatic synchronization with Android.

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

The server creates and protects its private auth directory/files. Use the PWA's encrypted `.jws` export for shared workspace/memory transfer. The separate task/session file `local-console.jpvr` still requires a stopped-server backup; the `.jws` file does not contain that registry. Do not manually edit JPVR bytes.

If the token is lost, stop the server and restart it with:

    python -m tools.jarvis_app --port 8765 --rotate-token

Use python3 instead of python on systems where that is the configured interpreter. Token rotation revokes the previous token and prints a replacement once.

## Local-only security boundary

The service binds to 127.0.0.1. Keep it that way. Do not expose it through 0.0.0.0, a LAN address, port forwarding, or a public tunnel. The current Bearer token protects local API access but is not a trusted human identity or permission to execute external actions.

The PWA and Android Compose client use the same API contract when they connect to a compatible local service. Workspace routes are GET/POST `/api/workspace`, use Bearer authentication and compare-and-swap revisions, and are bounded to 16 MiB. On Termux, launch with `python3 -m tools.jarvis_app --port 8765 --platform android`; on a PC use `--platform desktop`. The Android client currently targets 127.0.0.1 on the phone itself; it does not connect to a PC-hosted service. Manual encrypted `.jws` export/import is now implemented in both UIs and uses the shared workspace schema. See [Shared Workspace & Memory V1](JARVIS_SHARED_WORKSPACE_MEMORY_V1.md) for the transfer steps. Cross-device pairing and automatic synchronization are not implemented.

## Troubleshooting

- **Module not found:** make sure the terminal's current directory is the repository root.
- **Port already in use:** stop the other local console or choose another port, then open the matching local URL.
- **Token rejected:** verify that the token belongs to this data/auth profile; rotate it only if necessary.
- **Storage error:** stop the server and preserve a copy of the JPVR file before attempting recovery. Do not delete or overwrite the only copy.
- **Browser page unavailable:** confirm the server is still running and open the exact 127.0.0.1 URL printed by the process.

## Verification status

This is a documented launch procedure based on the current CLI source. It has not been executed or verified on Linux or macOS. Windows 10/11 support is experimental and has not been runtime-verified. No package, installer, desktop executable, build, test, or performance measurement is claimed.
