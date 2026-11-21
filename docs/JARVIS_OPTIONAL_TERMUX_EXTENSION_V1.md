# JARVIS Optional Termux Extension V1

**Status:** Android integration implemented at source level; verification NOT RUN  
**Date:** 2026-11-14  
**Branch:** `mobile-first-offline-v1`

## 1. Product contract

The Android app is the host and owns its essential conversation path. Termux is an optional companion process that adds capabilities; it is not the core runtime and is not required to install, launch, chat, keep Android conversation history, or execute the built-in ANS-100 signal pre-pass.

The app does not silently migrate local data into Termux, start Termux commands, or grant the companion unrestricted access to app-private storage.

## 2. Standalone mode

When Termux is absent, stopped, unpaired, or unreachable:

- the Android app starts normally;
- local conversation history remains in app-private storage;
- the in-process Kotlin ANS-100 signal pre-pass and its separate SharedPreferences checkpoint remain available;
- the imported LiteRT-LM model can be used locally; without an installed model the app retains its bounded deterministic fallback;
- Android-local drafts remain separate from JPVR workspace/task data;
- shared JPVR memories, the Termux task/session registry, remote symbolic preview and encrypted workspace transfer/backup are unavailable until the extension is connected.

This does not yet mean that Android has a complete durable local user-memory implementation or full local natural-language reasoning. Those remain separate implementation/verification tasks.

## 3. Discovery and connection

- Android queries only the known companion package `com.termux` through the manifest `<queries>` entry. Package presence indicates installation only; it does not prove that the Python service is running.
- If Termux is installed and a token was previously saved, the app may attempt a bounded loopback connection at startup. Failure leaves the built-in core available.
- The user can re-check package presence, open Termux, start the local console and connect from the app.
- The current service start command is:

  ```sh
  python3 -m tools.jarvis_app --port 8765 --platform android
  ```

- The service prints a random 43-character Base64URL bearer token on first start. The user explicitly copies it into Android. The app stores it encrypted with Android Keystore.
- The app does not automatically run shell commands or start/stop the Termux service. The companion remains bound to `127.0.0.1:8765`.

## 4. Extension capabilities

After an authenticated connection, the current API contract supports:

1. Shared JPVR workspace and user-approved memory read/write.
2. Termux-owned task/session registry operations (create session/task, update session state, cancel queued task).
3. Bounded, non-mutating symbolic Reasoning Engine preview.
4. Encrypted workspace transfer and separate encrypted backup/restore through the Android Storage Access Framework.
5. An optional ANS-100 conversation-signal endpoint. Android uses it only while connected and while the endpoint is known to work; otherwise it routes to its in-process Kotlin implementation.

The bridge's capability flags for external actions, arbitrary tool execution and remote access remain false. A queued task or symbolic preview is not an executed action. This extension does not grant unrestricted Python execution to the Android app.

## 5. Failure and fallback invariants

- Package not installed: no connection attempt is made; the app reports standalone mode.
- Token missing: the user may still use the app; only extension pairing is unavailable.
- Service not started, refused connection, timeout or malformed bridge response: connection state is cleared and local conversation remains available.
- Termux signal endpoint failure: the endpoint is disabled for the current connection and the Kotlin signal pass is used; ordinary chat does not wait for repeated failed remote calls.
- Extension removal: stale connection UI is cleared after package re-check; the in-process chat path remains active.
- Shared JPVR workspace operations require a live authenticated extension connection. A cached workspace snapshot remains reference data and is never treated as an authority or instruction.

## 6. Security boundaries

- The Android client uses the fixed loopback URL only; this is not LAN or Internet remote access.
- Package detection is not authentication. A valid bearer token and API schema/version validation are still required.
- The bearer token is not a trusted human identity or exact-action approval.
- The app keeps the existing Android INTERNET permission because the bridge uses HTTP loopback and other optional platform paths may need network access. This change does not disable model download/import functionality.
- The app does not automatically execute commands, arbitrary scripts, or external side effects through Termux.

## 7. Tests and verification

- `app/src/test/java/org/jarvis/ans100/alpha/data/OptionalTermuxExtensionStatusTest.kt`: authored checks for missing/installed/paird/connected states and the remote-signal routing predicate.
- Tests: **AUTHORED, NOT RUN**.
- Kotlin compilation, Gradle, APK/AAB, Android package discovery, live Termux pairing, loopback I/O, airplane-mode and device behavior: **NOT RUN**.
- This document records a source-level contract, not a verified Android runtime result.
