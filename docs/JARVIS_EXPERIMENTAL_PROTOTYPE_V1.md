# JARVIS Experimental Integrated Prototype V1

**Branch:** `experiment/jarvis-integrated-prototype-v1`  
**Base:** `mobile-first-offline-v1` plus the sparse dialogue-neuron experiment  
**Status:** source-integrated prototype; builds and runtime verification have not been performed.

This branch is the first integrated experimental JARVIS client, not a standalone neuron demo. It reuses the existing Android application and Python local console, and connects the 10,000-feature dialogue layer to the Python conversation path, usable on a PC or through the Termux-hosted Android PWA. The classifier is not yet part of the native Android Compose conversation runtime.

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
- a deterministic greeting/help/status fallback when no model is imported;
- optional user-imported local LiteRT-LM model;
- approved local memories;
- an in-process ten-neuron Kotlin ANS-100 signal pass;
- optional Termux extension for the authenticated loopback Python bridge, shared workspace, task/session administration, symbolic reasoning preview, and encrypted workspace transfer/backup.

**Important platform boundary:** the 10,000-feature Python dialogue classifier is connected to the Python console path, which can run on a desktop or as a Termux-hosted PWA on Android. The native Android Compose client remains separate and currently runs its ten-neuron Kotlin signal pre-pass. Do not describe the native Compose app as running the Python 10k classifier.

No APK is included or claimed. Android compilation, installation and device inference remain unverified.

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
- The 10k sparse classifier does not provide general semantic understanding, text generation or human-like reasoning.
- The local language model is a separate generation component; it is not the ANS-100 network.

## Data and safety

The local console binds to `127.0.0.1` only. Never expose it through `0.0.0.0`, LAN port forwarding or a public tunnel. The access token is local API access control, not a trusted identity or permission to perform external actions.

Approved workspace memories and conversation history are stored separately from task/session JPVR state, auth-token material and the dialogue-neuron numeric checkpoint. Encrypted JWS/JWB operations apply to the portable workspace only; they do not back up every runtime file or Android-private state.

## Verification status

The branch was assembled by source edits and GitHub read-back. No Python tests, Android tests, Gradle sync/build, APK generation, Playwright launch, local model inference, CI or GitHub Actions were run. Those results must not be inferred from a successful commit. Continue with static review first, then request explicit permission before any build or test execution under the project working rules.
