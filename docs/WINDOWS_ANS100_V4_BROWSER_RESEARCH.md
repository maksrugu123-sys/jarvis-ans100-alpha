# Windows ANS-100 V4 and browser architecture research

**Branch:** `experiment/jarvis-integrated-prototype-v1`  
**Status:** source implementation milestone; regression tests authored, **NOT RUN**. Windows runtime and Chrome/Edge behavior are **NOT VERIFIED**.

## Desktop ANS-100 topology

`tools/neuron_runtime/hybrid_ans100.py` ports the Android V4 sparse graph contract into the Python desktop runtime:

| Population | Range | Neurons |
|---|---:|---:|
| Evidence input | 0–1,199 | 1,200 |
| Association | 1,200–3,999 | 2,800 |
| Working state | 4,000–5,799 | 1,800 |
| Candidate | 5,800–7,299 | 1,500 |
| Critic / uncertainty | 7,300–8,499 | 1,200 |
| Goal / planning | 8,500–9,399 | 900 |
| Decision readout | 9,400–9,999 | 600 |
| **Total** | **0–9,999** | **10,000** |

The topology uses the Android V4 deterministic projection salts, fan-outs, candidate/support/conflict lane ranges, 1 ms edge delay, feed-forward weight 1.0 and recurrent weight 0.18. The six candidate lanes are isolated and the graph is bounded at 25,500 directed connections. `build_network()` constructs all 10,000 Python `NeuronRuntime` instances and the bounded `NetworkRuntime`; it is not a ten-node placeholder.

The external evidence input contract accepts at most 128 unique neuron IDs from the first 1,200 input neurons. Candidate labels and support/conflict hints still have to come from explicitly typed Core evidence; the network does not independently infer their semantic truth.

### Live chat integration

The 10,000-neuron graph is now wired into the Windows desktop conversation path:

1. `LocalConsoleService` injects `HybridANS100Runtime` into `JarvisCore` only on the desktop platform.
2. For each chat turn, Core runs its existing bounded understanding pass and optional symbolic-plan generation, then the hybrid runtime builds a fresh V4 graph.
3. Up to 64 typed evidence records are mapped into the 1,200-neuron evidence population using the same persisted feature order, 48-slot feature width, ordinal and boolean-channel mapping as Android's `AndroidHybridEvidenceInputAdapter`. User text itself is not hashed into neuron IDs. Each active input is a presence pulse of strength 1.0; its activation hint remains metadata, not a probability. Up to four Core-approved memory references are included. Unverified browser text is not relabelled as verified tool evidence.
4. The six candidate support/conflict input records use the same Android Core heuristic rules: usable intent + task kind + plan goal, clarification flags and ambiguous context reference. The graph runs with a 10,000-event per-turn budget and at most eight timestamp batches. Readouts count unique spiking neurons, then use Android's normalized support-minus-conflict competition with 0.25 lateral inhibition, 0.05 minimum activation and 0.05 winner margin. Missing population activity, candidate/readout mismatch, pending events, budget exhaustion or an unresolved tie cause abstention.
5. Core passes a bounded, allowlisted `ans100_context` object to the language engine, including candidate activations, winner margin, population coverage and uncertainty. The LiteRT-LM/Qwen adapter labels it as advisory structural signal, not semantic truth or an execution instruction.
6. If graph construction or execution fails, Core records the error and continues through the legacy 10-neuron signal path; a graph failure does not block the chat.

The old persistent 10-neuron `ConversationSignalRuntime` continues to run in parallel and remains available as a fallback. The separate optional dialogue R-STDP classifier also remains separate and is not represented as the 10k graph.

This integration is a real invocation path, but it is **not yet independent semantic reasoning**. Core currently supplies heuristic, typed candidate hints; the graph propagates and scores them. It does not discover factual truth, train from conversation, or autonomously verify claims. A fresh in-memory graph is built per turn: synaptic learning and persistent graph checkpoints are disabled. The Android topology, typed-evidence slot mapping and candidate competition policy are ported, but numerical/behavioral parity with Android golden fixtures has not been demonstrated. Existing conversation memory and the legacy signal checkpoint are not deleted or reinterpreted.

Remaining validation gates before treating this as a stable production path:
- run the authored regression suite on a Windows/Python environment and profile startup, peak memory and per-turn latency;
- compare all seven population and 25,500-edge topology details against Android golden fixtures;
- measure candidate-readout coverage and false abstention/answer/clarification rates on held-out prompts;
- design versioned durable graph checkpoints only after resource and parity results justify persistence.

## Browser automation research

Public projects inspected:

- [JARVIS (bertrandmbanwi)](https://github.com/bertrandmbanwi/Jarvis): separates a Manifest V3 Chrome extension bridge from a dedicated Playwright browser. The extension handles the user's real tabs/DOM; Playwright handles a separate persistent Chromium profile. Its documented bridge uses a local WebSocket and reconnect handling.
- [JARVIS-6 (psycoks)](https://github.com/psycoks/Jarvis): Windows-focused assistant with a visible Playwright browser, multi-hop tool loop, and confirmation for consequential actions.
- [PersonalJarvis](https://github.com/tbckdev/PersonalJarvis): separates desktop/browser computer-use capabilities from model/agent orchestration.
- [Playwriter](https://github.com/remorses/playwriter): connects to the user's existing Chrome via an extension rather than requiring a separate browser profile; this trades isolation for access to existing tabs and sessions.
- [Playwright Chrome Extension](https://github.com/microsoft/playwright/tree/main/packages/extension): demonstrates an extension-mediated connection to selected existing browser tabs.

### Browser modes and chat commands

There are now two explicit browser modes plus a limited no-extension fallback:

1. **Ordinary Chrome/Edge without the extension:** chat commands use `window.open()` to open a public URL or Bing search in the browser that hosts the JARVIS page. This opens a real browser tab/window, but JARVIS cannot inspect arbitrary page text, enumerate existing tabs, or close user-owned tabs/windows. Browser popup policy may block this action. This is open-only functionality, not full computer control.
2. **Connected Chrome/Edge (extension mode):** Manifest V3 Browser Bridge 1.1.0 adds a content-script relay on the local JARVIS origin and a background service worker. The allowlist is deliberately narrow: `open-tab`, `open-window`, `close-last-tab`, `close-last-window`, and `ping`. The worker records only the tab/window IDs it created through explicit JARVIS commands; close commands use this registry and never query or close arbitrary user tabs. The JARVIS chat confirms before a close. The extension keeps its existing narrow permissions (`activeTab`, `scripting`, `storage`) and does not request the broad `tabs` permission or all-sites access. Reading the current ordinary-browser page remains a separate, deliberate popup action using `activeTab`.
3. **Isolated research browser (Playwright mode):** retain the existing visible, headed Chromium with its separate persistent JARVIS profile and read-only request guard. Its own active tab can be closed, and the whole isolated window can be stopped independently of ordinary Chrome/Edge.

The page-to-extension relay accepts only same-window messages from the local JARVIS page, checks the message action against an allowlist, uses a bounded request ID/response wait, and checks the sender origin again in the extension worker. Local JARVIS HTTP APIs continue to use bearer-token authentication. The worker stores at most 40 recently JARVIS-created tab/window IDs. This is a local control bridge, not a remote network service.

Chat folder command `Создай папку Проекты` (or `Создай папку с именем «Проекты»`) creates a single new directory below the application-owned `workspace-files` directory next to JARVIS data. The command accepts a name, not a path. Traversal, hidden/system names, invalid Windows characters, existing folders and names over 80 characters are rejected; existing contents are never overwritten.

Supported close commands are `Закрой вкладку` and `Закрой окно`. JARVIS asks for confirmation and closes only the last tracked JARVIS-created resource, or its own isolated Playwright tab/window. It does not close the current user tab, unrelated windows, or a tab merely because it is active.

Do not attach to a user's personal browser through remote-debugging flags or copy cookies into the isolated profile. Existing JARVIS extension permissions remain unchanged in this milestone.

## Verification record

- `tools/neuron_runtime/test_hybrid_ans100.py`, `tools/neuron_runtime/test_hybrid_ans100_runtime.py`, `tools/jarvis_app/test_jarvis_core.py`, `tools/jarvis_app/test_local_inference.py`, `tools/jarvis_app/test_local_fallback.py`, `tools/jarvis_app/test_workspace_files.py` and `tools/jarvis_app/test_server.py`: authored/updated, **NOT RUN**.
- The live desktop Core now invokes the 10,000-neuron graph adapter on each chat turn and passes its allowlisted readout to the language engine. This is source-level integration only; actual graph execution is **NOT VERIFIED**.
- No Python tests, Windows launch, browser extension installation, Playwright launch, model inference, Gradle build, CI, or GitHub Actions workflow was run.
- Browser Bridge 1.1.0 and its chat command route remain source changes only; Chrome/Edge behavior is **NOT VERIFIED**.
- The 10-neuron persistent signal preprocessor remains active in parallel as a fallback. The 10k graph itself is fresh/in-memory per turn, with no learning or persistent graph state.
