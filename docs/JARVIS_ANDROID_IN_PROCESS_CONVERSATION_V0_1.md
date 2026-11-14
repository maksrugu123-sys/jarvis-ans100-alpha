# JARVIS Android In-Process Conversation Runtime V0.1

**Status:** IMPLEMENTED at source level; Android build, tests and device behavior NOT VERIFIED.  
**Date:** 2026-11-13  
**Target:** local text interaction on Android without Termux, a loopback server or internet.

## 1. Purpose

Provide the first in-process Android conversation path and isolate the UI from the response backend. This is a deliberately small foundation, not a claim that a full language model or the complete JARVIS assistant is ready.

## 2. Runtime boundary

The Compose screen calls JarvisViewModel; the ViewModel calls LocalConversationCoordinator. Android JarvisCore owns the turn, loads recent history, runs LocalUnderstandingPipeline and its bounded template planner, optionally retrieves approved memories, then passes the Core analysis to the injected LocalResponseEngine. The language engine only generates wording; the Core analysis also runs when no model is installed.

Current source components:

- app/src/main/java/org/jarvis/ans100/alpha/data/LocalConversationRuntime.kt: message/domain types, bounded V1 codec, repository, replaceable response-engine interface, coordinator and deterministic fallback.
- app/src/main/java/org/jarvis/ans100/alpha/data/SharedPreferencesConversationStorage.kt: app-private SharedPreferences adapter.
- JarvisViewModel.kt: asynchronous send/clear/reload actions and StateFlow UI state.
- MainActivity.kt: local conversation panel with an explicit capability label and history deletion confirmation.
- app/src/test/java/org/jarvis/ans100/alpha/data/LocalConversationRuntimeTest.kt: focused repository/coordinator/fallback tests.

The local conversation path does not construct an HTTP client, open a socket, use Termux, call the Python loopback service or require an internet connection. The existing authenticated Termux bridge remains a separate optional development feature for JPVR task/session administration, shared memory and encrypted transfer.

## 3. Current response backend

LocalFallbackResponseEngine implements the LocalResponseEngine interface. It recognizes basic social messages, /help and /status, and accepts the Core's bounded intent/plan record. For other requests it can show the heuristic intent and a clarification or preliminary-plan notice, while explicitly reporting that free-form answers require an installed language model.

This is a deterministic fallback, not an LLM. The Core-side `JARVIS_UNDERSTANDING_V1` parser and `JARVIS_REASONING_PLAN_V1` planner are bounded rules/templates, not general semantic understanding, not the ANS-100 neural runtime, and not the separate symbolic Reasoning Engine. They do not perform content-aware task decomposition, voice, or action execution. It does not call tools or cause external side effects. The optional LiteRT-LM Qwen adapter is now source-integrated behind LocalResponseEngine, but its runtime and device performance remain unverified.

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

The screen identifies the active backend as a local basic responder or the installed language model. The last eight messages are shown; the repository retains up to 120. The user can explicitly clear the Android-local conversation history. Without a model, the Core can report a bounded intent estimate and ask for missing context, but it cannot formulate arbitrary factual answers.

## 6. Verification

Fifteen focused Kotlin/JUnit conversation-runtime tests cover round-trip/reopen, bounded retention, invalid input, failed persistence, corrupt state, clear, fallback honesty, Core status, approved-memory routing, Core analysis handoff, model-free planning and injected-engine coordination. Eleven separate Kotlin understanding-pipeline tests cover intent/plan behavior and shared fixtures. Status: **AUTHORED, NOT RUN**.

No Gradle sync/build, APK/AAB, Kotlin test, emulator run, Android device run, offline airplane-mode test, performance measurement, CI or GitHub Actions workflow was executed. Source readback and static cross-file inspection only.

## 7. Next stage

1. Evaluate small offline language-model runtimes and candidate models against the actual Android phone's RAM, storage, startup latency, token speed, thermal/battery cost and language quality. Do not pick or bundle a model based on parameter count alone.
2. Implement one local backend behind LocalResponseEngine, with bounded generation, cancellation, a hard context limit and truthful availability reporting.
3. Extend the portable workspace contract so Android-local conversations can be explicitly exported/imported and used by the PC PWA without copying device credentials or platform secrets.
4. Keep the existing Python console/PC PWA useful as a desktop path; align conversation schema and backend identity rather than inventing a second unrelated conversation format.

External actions, shell access, arbitrary filesystem operations and remote access remain disabled and are not part of this stage.


## LiteRT-LM opt-in inference backend

The conversation coordinator now uses an optional `LocalResponseEngine` implementation backed by LiteRT-LM Android 0.17.1. Kotlin Gradle plugins are pinned to 2.3.10 to satisfy the published runtime metadata. Model loading and generation are restricted to a dedicated single-thread executor.

Use the exact no-think Qwen3-0.6B INT4 artifact `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm` (347,251,840 bytes) from `https://huggingface.co/litert-community/Qwen3-0.6B-int4`. The base APK does not bundle weights. The Compose chat offers an in-app download from a pinned revision with progress, cancellation, HTTP Range resume, exact-size and SHA-256 validation before publication to app-private `files/models/`. Manual import through the Android document picker remains supported.

The integration is CPU-only, with a 1,280-token engine context ceiling and 96-token output ceiling. The current source limits each user prompt to 750 characters, passes up to two prior messages capped at 160 characters each, includes up to two approved memories capped at 120 content characters each, and supplies a compact numeric ANS-100 summary. Character caps are not a precise token-count guarantee. Before model initialization Android must report at least 1,280 MiB available and not be in low-memory state. This is not an OOM guarantee and may prevent use on phones with little free RAM. If the model file is absent, only the deterministic fallback remains available.

No tools are registered, automatic tool calling is disabled, and the system instruction states that the model has no access to network, files, apps or external actions. Voice is not part of this backend.

The phone conversation continues to use app-private SharedPreferences. It is not yet connected to portable workspace conversations and does not currently move to PC through JWS/JWB. Cross-device transfer is a separate unfinished stage.

No Gradle sync, build, test, model initialization or inference was run for this integration. API signatures were checked against LiteRT-LM documentation/source, but compile/runtime compatibility remains unverified.

## 2026-11-14 update — ten-neuron conversation signal runtime V2

The earlier two-neuron signal pass is superseded by the V2 topology. Both in-process Kotlin on Android and Python on desktop/Termux now define the same ten logical neurons: six request-shape sensors (token volume, lexical diversity, question marker, punctuation density, sentence density and uppercase ratio), content and structure aggregators, an adaptive ALIF-DT integrator and an output gate. Nine directed connections route sensor spikes through the aggregators and integrator.

The V2 response contract is JARVIS_ANS100_CONVERSATION_SIGNAL_V2. It includes active/network neuron counts and the added punctuation, sentence and uppercase features. The Android bridge accepts V1 only as a compatibility response; newly written local and Python checkpoints use V2. An older Android V1 checkpoint is migrated while retaining integrator/gate membrane state, adaptation, refractory clocks and spike counters. A legacy Python network checkpoint is migrated only when its old scheduler queue is empty; otherwise migration fails closed.

Each platform uses a bounded 64-event budget. The signal runtime still receives only deterministic text-shape values. STDP/R-STDP learning from dialogue remains disabled, and the signal does not provide semantic understanding or reasoning. Ordinary Android chat must continue when both optional Termux signalling and the local signal pass fail; the ViewModel now treats this pre-pass as optional and calls the conversation coordinator with a null signal context in that case.

The focused Kotlin and Python regression tests cover ten-neuron flow, event bounds, persistence/reopen, legacy checkpoint migration, neutral inputs and malformed/persistence-failure behavior. Status: AUTHORED, NOT RUN. Android builds, desktop runtime tests, device inference, resource use and cross-language differential execution have not been run.


## 2026-11-14 update — app-private approved memory

Android now provides an app-local approved-memory store and Compose panel which work without Termux. This storage is distinct from the recent conversation history and from the shared Python JPVR workspace. It persists through process recreation but is not a native JPVR engine and is not automatically exported or synchronized to PC. See JARVIS_ANDROID_LOCAL_MEMORY_V1.md.


## 2026-11-14 update — native sparse dialogue-feature classifier

The native Compose chat now includes a separate 10,000-slot sparse text-feature classifier. It hashes up to 256 Unicode tokens and adjacent-token pairs into active slots, then scores four intent labels: greeting, question, thanks and farewell. It begins untrained and abstains until explicit labels have been provided and a minimum score margin is reached. The conversation panel displays the active feature count and any heuristic classification; four explicit feedback buttons train the last user message. The checkpoint stores numeric weights and a counter only. Corrupt checkpoints fail closed, and loading is lazy on the conversation executor.

This classifier is not the ten-neuron ANS-100 ALIF-DT signal runtime, not Qwen and not a general semantic model. It does not generate text or trigger actions. Kotlin uses SHA-256 feature hashing; Python's corresponding classifier uses BLAKE2b, so the separate checkpoints cannot be exchanged. Seven focused Kotlin/JUnit tests are AUTHORED, NOT RUN. The implementation is source-level only.


## 2026-11-14 update — pinned in-app model download

The native chat now offers a separately installed Qwen3-0.6B INT4 no-think model download. The exact file is 347,251,840 bytes with SHA-256
`2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`, pinned to Hugging Face revision
`6aa2daf8aba4aa456797fb8040b36a3948bcfda7`. The app-private base APK still excludes model weights. The downloader validates the full Content-Range start/end/total for resumed responses, rejects non-identity content encodings, validates the completed file before publication, and disconnects active HTTP on explicit cancellation or ViewModel clearing. Manual document-picker import is still available. The four pure validation tests in `LiteRtLmModelStoreValidationTest.kt` are AUTHORED, NOT RUN. No on-device download or inference has been performed.

## 2026-11-14 update — native sparse dialogue-feature classifier

The native Compose chat now includes a separate 10,000-slot sparse text-feature classifier. It hashes up to 256 Unicode tokens and adjacent-token pairs into active slots, then scores four intent labels: greeting, question, thanks and farewell. It begins untrained and abstains until explicit labels have been provided and a minimum score margin is reached. The conversation panel displays the active feature count and any heuristic classification; four explicit feedback buttons train the last user message. The checkpoint stores numeric weights and a counter only. Corrupt checkpoints fail closed, and loading is lazy on the conversation executor.

This classifier is not the ten-neuron ANS-100 ALIF-DT signal runtime, not Qwen and not a general semantic model. It does not generate text or trigger actions. Kotlin uses SHA-256 feature hashing; Python's corresponding classifier uses BLAKE2b, so the separate checkpoints cannot be exchanged. Seven focused Kotlin/JUnit tests are AUTHORED, NOT RUN. The implementation is source-level only.
