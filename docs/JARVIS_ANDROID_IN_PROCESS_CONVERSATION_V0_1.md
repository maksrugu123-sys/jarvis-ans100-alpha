# JARVIS Android In-Process Conversation Runtime V0.1

**Status:** IMPLEMENTED at source level; Android build, tests and device behavior NOT VERIFIED.  
**Date:** 2026-11-13  
**Target:** local text interaction on Android without Termux, a loopback server or internet.

## 1. Purpose

Provide the first in-process Android conversation path and isolate the UI from the response backend. This is a deliberately small foundation, not a claim that a full language model or the complete JARVIS assistant is ready.

## 2. Runtime boundary

The Compose screen calls JarvisViewModel; the ViewModel calls LocalConversationCoordinator. The coordinator owns the bounded local conversation repository and an injected LocalResponseEngine.

Current source components:

- app/src/main/java/org/jarvis/ans100/alpha/data/LocalConversationRuntime.kt: message/domain types, bounded V1 codec, repository, replaceable response-engine interface, coordinator and deterministic fallback.
- app/src/main/java/org/jarvis/ans100/alpha/data/SharedPreferencesConversationStorage.kt: app-private SharedPreferences adapter.
- JarvisViewModel.kt: asynchronous send/clear/reload actions and StateFlow UI state.
- MainActivity.kt: local conversation panel with an explicit capability label and history deletion confirmation.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationRuntimeTest.kt: focused repository/coordinator/fallback tests.

The local conversation path does not construct an HTTP client, open a socket, use Termux, call the Python loopback service or require an internet connection. The existing authenticated Termux bridge remains a separate optional development feature for JPVR task/session administration, shared memory and encrypted transfer.

## 3. Current response backend

LocalFallbackResponseEngine implements the LocalResponseEngine interface. It recognizes greeting, /help and /status inputs. For unsupported prompts it explicitly reports that an offline language model has not yet been integrated.

This is a deterministic command/greeting fallback. It is **not** an LLM, the ANS-100 neural runtime, the symbolic Reasoning Engine, natural-language task decomposition, voice, or an action executor. It does not call tools or cause external side effects. A future local model must be introduced through LocalResponseEngine and must report its true backend identity and availability.

## 4. Local history format and bounds

The Android-local history uses the version marker JARVIS_ANDROID_CONVERSATION V1 and a strict Base64URL text codec.

- At most 120 messages (60 complete user/assistant exchanges).
- At most 4,000 UTF-16 Kotlin characters per message.
- At most 1 MiB encoded persisted state.
- Each published turn contains both the user message and the assistant reply; a failed storage commit does not report success.
- Message IDs must be unique; roles must alternate USER/ASSISTANT; timestamps must be positive and non-decreasing.
- Unknown, malformed, over-limit or incomplete snapshots are rejected rather than silently repaired.
- The repository stores one bounded snapshot per conversation update using a synchronous SharedPreferences commit on the ViewModel's background executor.

The store is app-private but is not separately encrypted by this feature. It is not the existing JPVR shared workspace. As of V0.1, local Android chat history is not included in .jws or .jwb, is not visible in the PC PWA, and is not synchronized between devices. That gap must be resolved through a shared portable conversation contract before claiming cross-platform conversation continuity.

## 5. User-visible behavior

The screen identifies the backend as a local basic responder without an LLM. The last eight messages are shown; the repository retains up to 120. The user can explicitly clear the Android-local conversation history. Unsupported prompts receive an honest capability response rather than fabricated answers or claims of successful reasoning.

## 6. Verification

Eight focused Kotlin/JUnit tests are authored for round-trip/reopen, bounded retention, invalid input, failed persistence, corrupt state, clear, fallback honesty and injected-engine coordination. Status: **AUTHORED, NOT RUN**.

No Gradle sync/build, APK/AAB, Kotlin test, emulator run, Android device run, offline airplane-mode test, performance measurement, CI or GitHub Actions workflow was executed. Source readback and static cross-file inspection only.

## 7. Next stage

1. Evaluate small offline language-model runtimes and candidate models against the actual Android phone's RAM, storage, startup latency, token speed, thermal/battery cost and language quality. Do not pick or bundle a model based on parameter count alone.
2. Implement one local backend behind LocalResponseEngine, with bounded generation, cancellation, a hard context limit and truthful availability reporting.
3. Extend the portable workspace contract so Android-local conversations can be explicitly exported/imported and used by the PC PWA without copying device credentials or platform secrets.
4. Keep the existing Python console/PC PWA useful as a desktop path; align conversation schema and backend identity rather than inventing a second unrelated conversation format.

External actions, shell access, arbitrary filesystem operations and remote access remain disabled and are not part of this stage.
