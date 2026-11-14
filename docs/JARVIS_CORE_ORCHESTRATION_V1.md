# JARVIS Core Orchestration V1

**Status:** source implementation in progress; runtime not yet verified.  
**Branch:** `mobile-first-offline-v1`.

## Purpose

JARVIS Core is the owner of a conversation turn. A local language model is a language-generation component called by the core; it is not the entire JARVIS system. The core owns input validation, turn routing, conversation context, capability reporting and persistence.

## Current turn flow

1. Validate and normalize the user's message.
2. Load bounded recent conversation history.
3. On desktop/PWA, retrieve up to three relevant records from the portable JPVR workspace using bounded lexical token overlap.
4. Pass the user prompt, recent history and retrieved reference memories to the local language model.
5. Validate the generated response.
6. Persist the user/assistant exchange with the platform's existing storage mechanism.

The Android implementation currently uses app-private SharedPreferences conversation history. It does not yet retrieve the separate JPVR workspace memories into the Android chat. The desktop/PWA implementation uses the workspace's `memories` records for lexical recall. This is not semantic or vector search.

## Component boundaries

| Component | Current state |
|---|---|
| JARVIS Core | Owns the conversation turn on Android and desktop/PWA |
| Language engine | Optional local LiteRT-LM Qwen3 component; installation/runtime availability is reported separately from inference verification |
| Recent conversation memory | Available from bounded conversation history |
| Long-term JPVR memory | Lexically retrieved by desktop/PWA; not yet integrated into Android chat |
| Reasoning Engine | Separate symbolic planning preview; not automatically invoked for free-text chat |
| ANS-100 NetworkRuntime | Exists as a separate runtime; not yet part of the conversation turn |
| External actions/tool execution | Disabled |

## Memory retrieval contract

Desktop memory retrieval:
- scans at most 2,000 workspace memory records;
- returns at most three records;
- limits each memory to 700 characters and total supplied memory context to 1,800 characters;
- ranks by lexical token overlap, with stable source-order tie breaking;
- skips unrelated notes;
- labels retrieved notes as untrusted data, not instructions.

This first retrieval implementation is intentionally simple and bounded. It does not establish semantic relevance, memory truth, freshness, or user intent. Future retrieval should add explicit freshness/importance metadata and adversarial-memory tests before becoming more authoritative.

## Safety and honesty rules

- The model may formulate language but must not claim it has invoked ANS-100, reasoning, external tools or long-term memory unless the core supplies evidence of that invocation.
- Memory records are reference data, not executable instructions.
- The core does not execute actions, invoke a shell, or enable external tools.
- Model installation or runtime package availability is not proof that inference succeeded on a particular device.

## Verification still required

No tests, builds, APK generation, runtime inference or device benchmarks were run for this source change. The added tests are authored but unexecuted. Next verification should cover the Python core and server conversation endpoint, Android command routing and fallback behavior, real LiteRT-LM inference, memory retrieval quality, and resource use on the target phone.
