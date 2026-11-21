# JARVIS Android App Shell V0.6

## Purpose

The Android client opens directly into the local JARVIS conversation. Previously implemented features remain available but are grouped into four bottom-navigation destinations rather than displayed as one long page.

## Navigation

- **Чат** — in-process conversation, model installation/import, download progress, and explicitly labeled dialogue-classifier feedback.
- **Память** — device-local approved memories and the optional shared JPVR workspace, including encrypted JWS transfer and JWB backup/restore.
- **Задачи** — Android-local sessions and task drafts. These drafts remain separate from Python runtime tasks.
- **Система** — optional Termux pairing/bridge, runtime task/session operations and non-executing reasoning preview.

The bottom navigation is stateful and keeps the selected destination through ordinary Compose recreation via saveable state. Each destination has its own scrollable content. The conversation composer remains pinned above navigation while the chat is selected; the full message history is shown in its own bounded, auto-following list. The existing feature panels and ViewModel callbacks are reused; no data migration or storage schema change is part of this UI batch.

## Local-first messaging

The status copy now explains that built-in conversation and local memory work without Termux, while Termux is an optional extension for shared workspace and runtime administration.

## Verification

Source-only change. Gradle sync/build, APK generation, installation, device layout checks and Compose UI tests were not run. The navigation is not claimed as device-verified.
