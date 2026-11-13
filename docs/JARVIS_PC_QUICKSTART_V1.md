# JARVIS PC Quick Start V1

**Status:** source-level desktop development entry point  
**Date:** 2026-11-13

This guide starts the existing Python local console on a PC. It does not install a finished conversational assistant or a native desktop executable.

## What currently works at source level

- Python local-console process.
- Local browser/PWA interface.
- Local JPVR task/session registry.
- Bounded symbolic reasoning preview using structured facts and actions.
- Bearer-token protected state and mutation routes.

Not yet delivered: integrated offline language model, full natural-language conversation, production task execution, offline voice, native Windows/macOS/Linux package, or automatic synchronization with Android.

## Requirements

- A supported Python 3 installation.
- A local copy of this repository.
- A modern browser.

The repository's Python modules use modern Python syntax. The minimum supported Python version has not yet been formally declared or verified across Windows, macOS, and Linux. If startup fails, record the exact Python version and error instead of changing dependencies blindly.

## Start on Windows

Open PowerShell in the repository directory:

    py -3 --version
    py -3 -m tools.jarvis_app --port 8765

If the Python launcher is unavailable but python is on PATH:

    python --version
    python -m tools.jarvis_app --port 8765

## Start on Linux or macOS

Open a terminal in the repository directory:

    python3 --version
    python3 -m tools.jarvis_app --port 8765

## First launch

1. The server prints a one-time 43-character access token.
2. Save it in a password manager or another private location. Do not paste it into public chats or commit it to Git.
3. Open http://127.0.0.1:8765/ in the same PC browser.
4. Enter the token on the PWA sign-in screen.
5. Keep the terminal process running while using the local console.
6. Stop the server with Ctrl+C.

The default data and token-digest paths are under the current user's home directory:

- ~/.jarvis-ans100-alpha/local-console.jpvr
- ~/.jarvis-ans100-alpha/local-console.jpvr.access-token

The server creates and protects its private auth directory/files. Back up the data file only while the server is stopped, or use a future supported export operation. Do not manually edit JPVR bytes.

If the token is lost, stop the server and restart it with:

    python -m tools.jarvis_app --port 8765 --rotate-token

Use python3 instead of python on systems where that is the configured interpreter. Token rotation revokes the previous token and prints a replacement once.

## Local-only security boundary

The service binds to 127.0.0.1. Keep it that way. Do not expose it through 0.0.0.0, a LAN address, port forwarding, or a public tunnel. The current Bearer token protects local API access but is not a trusted human identity or permission to execute external actions.

The PWA and Android Compose client use the same API contract when they connect to a compatible local service. The Android client currently targets 127.0.0.1 on the phone itself; it does not connect to a PC-hosted service. Cross-device pairing and synchronization are not implemented.

## Troubleshooting

- **Module not found:** make sure the terminal's current directory is the repository root.
- **Port already in use:** stop the other local console or choose another port, then open the matching local URL.
- **Token rejected:** verify that the token belongs to this data/auth profile; rotate it only if necessary.
- **Storage error:** stop the server and preserve a copy of the JPVR file before attempting recovery. Do not delete or overwrite the only copy.
- **Browser page unavailable:** confirm the server is still running and open the exact 127.0.0.1 URL printed by the process.

## Verification status

This is a documented launch procedure based on the current CLI source. It has not been executed or verified on Windows, Linux, or macOS. No package, installer, desktop executable, build, test, or performance measurement is claimed.
