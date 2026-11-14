# JARVIS Core Orchestration V1

**Status:** source implementation on mobile-first-offline-v1; Python and Kotlin tests are AUTHORED, NOT RUN. No model inference or target-device runtime is verified.

## Purpose

JARVIS Core owns a conversation turn. A language model is a pluggable language-generation component, not the whole assistant. Core validates input, loads bounded history, selects approved reference memories, optionally attaches a low-level ANS-100 signal summary, validates the response and commits the complete exchange.

## Android turn path

Compose → JarvisViewModel → LocalConversationCoordinator → JarvisCore → LocalUnderstandingPipeline + bounded template planner → LocalResponseEngine

- Conversation history is app-private and bounded to 120 messages / 4,000 characters per message.
- The optional LiteRT-LM Qwen3-0.6B INT4 no-think adapter is source-integrated. The artifact is manually imported and not bundled; successful inference has not been verified. Before either response path, Android Core creates `JARVIS_UNDERSTANDING_V1` and a bounded `JARVIS_REASONING_PLAN_V1` using recent conversation history. The Qwen adapter receives this advisory context; the no-model fallback can display its intent and clarification/plan notice but cannot generate open-ended answers.
- The in-process Kotlin ANS-100 V2 pass contains ten logical ALIF-DT nodes (six request-shape sensors, two aggregators, an adaptive integrator and an output gate). It is optional: Termux may supply the Python signal result when connected, but failure of both passes does not block the ordinary conversation. This is numerical preprocessing, not semantic understanding or dialogue learning.
- Device-local approved memories persist in a versioned app-private SharedPreferences store. Limits are 2,000 records, 700 content characters and 64 category characters. Only explicit local approval or imported records marked user_approved=true enter the cache. Mutations commit before the cache publishes a candidate; corrupt snapshots and failed commits are fail-closed.
- On an authenticated Termux bridge refresh/import/restore, approved shared-workspace memories are merged into the device cache. Removing the bridge token leaves the app's device-local memory intact.
- Android memory retrieval uses bounded lexical overlap, selecting at most three records and at most 1,800 context characters. Retrieved notes are untrusted reference data, not instructions.

**Storage boundary:** the new app-private memory store is not a native JPVR engine. Android-created local notes are not currently uploaded into the PC workspace or automatically included in JWS/JWB; there is no automatic cross-device synchronization. The shared JPVR workspace and the Android device-local store remain separate persistence systems.

## Desktop / PWA turn path

PWA → loopback-only Python server → tools/jarvis_app/jarvis_core.py → local inference adapter

- The local Python host binds to 127.0.0.1 and requires a bearer token for state and mutation APIs.
- Conversation and approved memories live in the PC's JPVR workspace; API writes use revision compare-and-swap.
- Desktop/PWA JARVIS Core invokes the ten-neuron ConversationSignalRuntime V2 preprocessor and reports the actual invocation result. Its adaptive state is persisted in a separate JPVR checkpoint.
- Optional Qwen3-0.6B INT4 inference requires an explicitly supplied local model and pinned Python runtime package. Successful model inference and response quality remain unverified.

## Shared ANS-100 V2 contract

Both Python and Kotlin extract the same six bounded features: token volume, lexical diversity, question marker, punctuation density, sentence density and uppercase ratio. The topology has six sensors, two aggregators, one adaptive request integrator and one attention/output gate connected by nine directed routes. The common fixture app/src/test/resources/ans100/conversation-v2.tsv is consumed by both test suites and compares feature values, event/batch/spike counts, gate state, adaptive state, threshold and model time.

The shared fixture `app/src/test/resources/understanding/understanding-v1.tsv` additionally aligns intent labels, negation/context indicators, template-plan size and clarification behavior between the Android and Python analyzers. The shared fixtures are authored contract coverage, not proof of parity until both suites run. STDP/R-STDP dialogue learning remains disabled. The signal pass does not understand natural language, infer intent or validate the generated answer. The new text parser uses explicit rules and the planner uses templates; neither is a trained semantic model or a replacement for the structured Reasoning Engine.

## Visible browser research context

Desktop/PWA can invoke the optional headed Chromium controller only through the authenticated loopback server. The user starts a visible search/open action, can inspect the current page, pause JARVIS and take manual control, and then explicitly return control. Page text is read from the current visible page; a separate “Ask JARVIS” action passes at most 1,800 characters, title, URL and retrieval time to Core as untrusted external context. The model is instructed to ignore page-provided commands and automatic tool calling remains disabled. The saved answer includes the source title/URL.

This is a one-page research helper, not a multi-source evidence graph. Search results depend on the current Bing DOM structure. The HTTP route guard permits only GET/HEAD, and a separate Playwright route blocks WebSocket connections; downloads and service workers are disabled. URL/host/DNS checks reject local and non-public IP targets, but this is not a full network sandbox against DNS rebinding or browser/page script risks. Do not use the dedicated browser profile for private authenticated accounts. Playwright >=1.48/Chromium are optional manual installs; the headed desktop runtime remains unverified. Tests are AUTHORED, NOT RUN.

## Reasoning, tasks and safety

The bounded Reasoning Engine can analyze explicit structured requests and produce deterministic symbolic plans. It is not automatically invoked for ordinary free text, does not parse unrestricted natural language and does not execute steps. The Task Loop, Permission & Safety Gate, approval and idempotency modules remain separate reference components. Caller-supplied evidence is not independent proof. External tools, shell access and side-effecting actions remain disabled.

## Memory retrieval contract

- Scan at most 2,000 approved records.
- Return at most three lexical-overlap matches.
- Limit a record to 700 content characters and total model context to 1,800 characters.
- Use stable source-order tie breaking.
- Treat notes as potentially stale, untrusted reference data.

Lexical overlap is not semantic or vector search and does not establish truth, freshness or intent.

## Verification and open work

- Kotlin app-local memory tests: AUTHORED, NOT RUN.
- Shared Python/Kotlin ANS-100 fixture tests: AUTHORED, NOT RUN.
- Existing Core, local-console and signal tests remain NOT RUN unless explicitly recorded as user-run historical evidence elsewhere.
- No Gradle build, APK/AAB, model load/inference, Android device run, PC PWA session, browser session or performance measurement is claimed.
- Next implementation work: visible browser research on PC; native Android JPVR or a reliable export bridge for device-local memories; actual model/runtime verification only after explicit test/build authorization.
