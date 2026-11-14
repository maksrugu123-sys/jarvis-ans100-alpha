# JARVIS Experimental Integrated Prototype V1

**Branch:** `experiment/jarvis-integrated-prototype-v1`  
**Base:** `mobile-first-offline-v1` plus the sparse dialogue-neuron experiment  
**Status:** source-integrated prototype; builds and runtime verification have not been performed.

This branch is the first integrated experimental JARVIS client, not a standalone neuron demo. It reuses the existing Android application and Python local console. A sparse 10,000-feature dialogue classifier is connected to both the Python conversation path and the native Android Compose chat, using separate platform checkpoints and hash mappings.

## What is available in the prototype

### PC local console / PWA

Launch from the repository root:

```sh
python3 -m tools.jarvis_app --port 8765 --platform desktop
```

Open `http://127.0.0.1:8765/` in the same computer. The first launch prints a one-time local access token; enter it in the sign-in panel. Keep the process running while using the console. It binds only to loopback.

The PC console now starts without a language model in **basic offline mode**. This fallback supports greetings, `/help`, `/status`, and explicit service responses; it does not answer arbitrary questions or pretend to be an LLM. The same JARVIS Core still processes each turn through bounded approved-memory retrieval, the ANS-100 signal pre-pass, and the 10,000-feature sparse dialogue classifier.

When the user supplies the supported model artifact and installs the matching runtime, the same console uses local Qwen generation:

```sh
python3 -m pip install -r requirements-litert-lm.txt
python3 -m tools.jarvis_app --port 8765 --platform desktop --model /absolute/path/qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm
```

The model is not downloaded automatically or included in the repository. Model compatibility, Russian-language quality, speed, memory use and thermal behavior remain unverified.

### JARVIS Core understanding and pre-generation planning

Every ordinary conversation turn is first processed by JARVIS Core on both platforms, before the response engine is called. Android uses `LocalUnderstandingPipeline`; PC/PWA uses `tools.jarvis_app.understanding.analyze_text`. Both produce the bounded `JARVIS_UNDERSTANDING_V1` record: a heuristic intent, task type, key terms, possible entities, negation and context indicators, plus a short evidence list.

Core also creates a `JARVIS_REASONING_PLAN_V1` template plan of up to five steps for question, explanation, comparison, action request, continuation, correction or basic social intents. Some ambiguous requests carry one clarification question. The plan is advisory and explicitly has `execution_authorized=false`.

When Qwen is installed, the original user message and this Core-produced analysis are both passed to the language adapter; Qwen formulates the natural-language answer. The adapters bound and validate the V1 context. When no model is installed, the local fallback still receives the Core analysis and can display a basic intent/plan notice, but it cannot provide open-ended factual conversation.

This is the beginning of independent JARVIS processing, not proof of deep semantic intelligence. The parser uses explicit rules and the plan uses templates; neither is a trained general-purpose reasoning model. A shared TSV fixture is read by both Android and Python test sources. Nine understanding tests per platform plus two Android Core integration tests are AUTHORED, NOT RUN. No cross-platform runtime parity or quality measurement is claimed.

The PC interface includes:
- local conversation history stored in the JPVR workspace;
- explicitly approved workspace memories and bounded lexical recall;
- ANS-100 ten-neuron signal preprocessing on conversation turns;
- a 10,000-addressable-feature sparse dialogue classifier, with explicit example labelling and a private numeric checkpoint;
- structured symbolic reasoning preview and plan review; these are not natural-language task decomposition or execution;
- JPVR task/session lifecycle administration;
- visible, read-only research browser using a separate headed Chromium profile;
- encrypted JWS workspace merge and JWB backup/restore.

### Run the integrated PWA from Termux on Android

The same Python console can run locally on an Android phone through Termux. This provides the integrated PWA interface and the Python 10,000-feature dialogue classifier without building or installing an APK. The native Compose app remains a separate no-Termux path.

From Termux, install Git/Python if they are not already present, then run:

```sh
pkg install git python
git clone -b experiment/jarvis-integrated-prototype-v1 https://github.com/maksrugu123-sys/jarvis-ans100-alpha.git
cd jarvis-ans100-alpha
python3 -m tools.jarvis_app --port 8765 --platform android
```

Open `http://127.0.0.1:8765/` in Chrome on the same phone and enter the one-time token printed by Termux. Keep the Termux process running while using the PWA. Stop it with Ctrl+C. This mode binds only to the phone's loopback interface; do not expose it to a LAN or public tunnel.

In this Android PWA mode, the deterministic fallback chat, local workspace, approved-memory panel, 10,000-feature Python dialogue classifier, task/session registry and symbolic reasoning preview are available at source level. The visible headed Chromium research browser is desktop-only and reports itself unavailable. The optional Termux bridge inside the native Compose app is still a separate integration; this guide's PWA is not the native Android APK.

### Dialogue-neuron experiment

The Python dialogue layer is a sparse feature classifier, not 10,000 simultaneously simulated LIF neurons and not a language model. It hashes tokens and adjacent token pairs into a bank of 10,000 addressable feature units. Its first labels are greeting, question, thanks and farewell.

The classifier begins untrained. It does not silently learn from chat. To train it, enter a sample and choose the correct label in the **Экспериментальные диалоговые нейроны** panel, then explicitly press **Обучить на этом примере**. The local checkpoint is:

`~/.jarvis-ans100-alpha/dialog-neurons-v1.json`

The checkpoint stores numeric weights and a training counter, not the original text examples. A classification is only a heuristic text-pattern estimate; a missing or weak prediction is reported as no confident label. This is an experimental side signal and does not replace the language model.

### Android application

The existing Jetpack Compose client remains in `app/` (application ID `org.jarvis.ans100.alpha`). Its source includes:
- local chat and bounded chat history without Termux;
- a deterministic Core-analysis-aware fallback for basic social replies and intent/plan notices when no model is imported;
- optional local LiteRT-LM model, installed through an in-app resumable download or manual import;
- approved local memories;
- an in-process ten-neuron Kotlin ANS-100 signal pass;
- a separate 10,000-slot sparse dialogue-intent classifier with four labels, local numeric checkpoint, metrics display and explicit feedback buttons;
- optional Termux extension for the authenticated loopback Python bridge, shared workspace, task/session administration, symbolic reasoning preview, and encrypted workspace transfer/backup.

#### In-app language-model installation

The native Android chat offers a **Download language model** action when no model is installed. The source implementation downloads the Qwen3-0.6B INT4 no-think LiteRT-LM artifact from a pinned Hugging Face revision, displays progress, supports cancellation and HTTP Range resume, verifies the exact 347,251,840-byte size and SHA-256 before publishing the file, and stores it under the app-private `files/models` directory. The base APK does not contain model weights. After a successful install the design uses local offline inference; the actual download and inference have not yet been verified on a device. The user can also import the same model file manually.

Pinned source: [litert-community/Qwen3-0.6B-int4](https://huggingface.co/litert-community/Qwen3-0.6B-int4), revision `6aa2daf8aba4aa456797fb8040b36a3948bcfda7`. Artifact: `qwen3_0.6b_nothink_q4_block32_ekv1280.litertlm`; SHA-256: `2df6821ec12702dafd33915e7a1a1adc7c4b053f3672fd9555dfaf3a114c4139`. The model card declares Apache-2.0. This file matches the app's current LiteRT-LM 0.17.1 dependency; do not silently replace it with the newer dynamic export, which has different runtime requirements.

The Android downloader also checks the full HTTP byte-range bounds and rejects encoded responses for resumable downloads. Cancellation disconnects the active request, including when the Android ViewModel is cleared; interrupted partial data can be resumed after reopening the app. The inference prompt is deliberately bounded for the 1,280-token model context: up to 750 characters per user prompt, the last two messages (up to 160 characters each), up to two approved memories (up to 120 characters each), and a compact numeric ANS-100 summary. These are conservative source-level limits, not a measured token-count guarantee. The model returns up to 96 output tokens.

The download implementation is source-level only. No APK build, device download, inference, memory benchmark or runtime compatibility test has been performed. The model's measured RAM use and generation speed on the target TECNO phone are unknown; a 331 MiB file can require substantially more RAM while running.

**Important platform boundary:** native Android now has its own sparse 10,000-slot classifier in `LocalDialogFeatureClassifier.kt`. It maps up to 256 Unicode tokens and adjacent pairs into a sparse subset of the 10,000-slot bank, predicts four simple intent labels, and starts untrained. The Compose panel shows active-feature count and a heuristic label only when its score margin is large enough. The user must explicitly choose one of four feedback labels to update weights. The Android checkpoint contains numeric weights and a training counter, not raw utterances. The Python implementation uses BLAKE2-based hashing while Kotlin uses SHA-256, so their feature IDs and checkpoints are not interchangeable. This classifier is separate from the ten-neuron Kotlin ANS-100 signal pass and from Qwen; it does not generate text, perform semantic understanding, route actions or enter Qwen's prompt as a reasoning result.

No APK is included or claimed. The seven sparse-classifier tests, nine understanding tests, two Core handoff/fallback tests and four model-download validation tests are AUTHORED, NOT RUN. Android compilation, installation, actual download/resume and device inference remain unverified.

### Visible PC research browser

Optional dependencies:

```sh
python3 -m pip install -r requirements-browser.txt
python3 -m playwright install chromium
```

The browser opens a visible Chromium window and provides bounded search, open, read and ask-about-page actions. Only GET/HEAD navigation is allowed by the current controller. It is not a full network sandbox. Page contents are untrusted data. Do not use the dedicated research profile for personal-account sign-in or sensitive browsing.

## What is deliberately not enabled

- No shell or arbitrary Python command execution through chat.
- No file editing, application control, purchases, messages, form submissions, downloads or external side effects.
- A queued task is only a record; it is not running.
- A reasoning plan is a symbolic preview; it is not an approval or completed action.
- The reference Task Loop, safety gate, CQE and persistence components are not yet assembled into a trusted autonomous action service.
- The 10k sparse classifier does not provide general semantic understanding, text generation or human-like reasoning, and its Android/Python checkpoints are not cross-platform compatible.
- The local language model is a separate generation component; it is not the ANS-100 network.

## Data and safety

The local console binds to `127.0.0.1` only. Never expose it through `0.0.0.0`, LAN port forwarding or a public tunnel. The access token is local API access control, not a trusted identity or permission to perform external actions.

Approved workspace memories and conversation history are stored separately from task/session JPVR state, auth-token material and the dialogue-neuron numeric checkpoint. Encrypted JWS/JWB operations apply to the portable workspace only; they do not back up every runtime file or Android-private state.

## Verification status

The branch was assembled by source edits and GitHub read-back. No Python tests, Android tests, Gradle sync/build, APK generation, Playwright launch, local model inference, CI or GitHub Actions were run. Those results must not be inferred from a successful commit. Continue with static review first, then request explicit permission before any build or test execution under the project working rules.
