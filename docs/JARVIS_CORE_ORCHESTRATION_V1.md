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

The Android implementation uses app-private SharedPreferences for recent conversation history and a read-only in-memory cache of approved JPVR workspace memories after a successful authenticated bridge refresh, confirmed import, or restore. The cache is cleared when the bridge token is removed. When no workspace snapshot has been loaded, Android continues with local conversation history only. Desktop/PWA reads the workspace's `memories` records directly. Both use bounded lexical overlap, not semantic or vector search.

## Component boundaries

| Component | Current state |
|---|---|
| JARVIS Core | Owns the conversation turn on Android and desktop/PWA |
| Language engine | Optional local LiteRT-LM Qwen3 component; installation/runtime availability is reported separately from inference verification |
| Recent conversation memory | Available from bounded conversation history |
| Long-term JPVR memory | Lexically retrieved by desktop/PWA; not yet integrated into Android chat |
| Reasoning Engine | Separate symbolic planning preview; not automatically invoked for free-text chat |
| ANS-100 NetworkRuntime | A bounded two-neuron ALIF-DT signal pre-pass runs on desktop/PWA turns; Android can request the same pass through the authenticated Termux bridge. This is low-level numeric signal preprocessing only, not semantic reasoning, adaptive learning, or persistent neural state |
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


## JPVR Android recall and ANS-100 signal pass V1

Android memory retrieval:
- loads only records with `user_approved=true` from an authenticated workspace snapshot;
- retains at most 2,000 compact cached records, with content capped at 700 characters;
- retrieves at most three lexical-overlap matches for a prompt, within 1,800 total context characters;
- passes selected notes to LiteRT-LM as untrusted reference data, never as instructions;
- keeps the last loaded in-process snapshot available when Termux goes offline, but clears it when the access token is explicitly removed.

ANS-100 signal pass:
- invokes the existing `NeuronRuntime`, `NetworkRuntime`, and Numerical Oracle;
- feeds three transparent request-shape features (normalized token volume, lexical diversity, question punctuation) into an ALIF-DT request-integrator neuron;
- propagates a source spike over a bounded network edge to an attention-gate neuron;
- uses a fresh in-memory network for each request, `learning_rule="none"`, an eight-event work budget, and no persistent checkpoint;
- passes only the numeric feature summary and gate status to the language model.

This is real execution of the existing ANS-100 reference runtime, but it is a narrow signal preprocessor. It does not understand natural language, infer intent, retrieve memories, learn from dialogue, plan, or prove correctness. The full ANS-100 cognitive loop remains unintegrated. On Android the neural pre-pass is optional and is requested from the authenticated loopback Termux service with a short timeout; unavailable Termux does not block local conversation.

No tests, builds, APK generation, runtime inference or device benchmarks were run for this source change. Added tests are authored but not executed.
