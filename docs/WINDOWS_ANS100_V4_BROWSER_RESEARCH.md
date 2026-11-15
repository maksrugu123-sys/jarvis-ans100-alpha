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

### Integration boundary

This is the desktop network foundation, **not yet a replacement of the current 10-neuron `ConversationSignalRuntime` in the live chat path**. The 10-neuron structural preprocessor is preserved. The next integration step must:
1. map versioned typed Core evidence to the same 1,200 input range as Android;
2. schedule a bounded input batch and run the 10k graph under event/time budgets;
3. compute candidate support/conflict readouts and abstain on missing or incomplete lanes;
4. pass a provenance-labelled decision object to Qwen;
5. add durable topology/state/weight checkpoints and safe version migration;
6. prove parity against Android golden fixtures before enabling it by default.

The Python reference network is currently in-memory. Do not claim persistent cross-restart learning, identical Android numerical/runtime behavior, or independent semantic reasoning yet. Existing desktop conversation memory and the old signal checkpoint are not deleted or reinterpreted.

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

- `tools/neuron_runtime/test_hybrid_ans100.py` and `tools/jarvis_app/test_workspace_files.py`: authored, **NOT RUN**.
- Folder endpoint and extension archive regression coverage updated in `tools/jarvis_app/test_server.py`; **NOT RUN**.
- No Python tests, Windows launch, browser extension installation, Playwright launch, model inference, Gradle build, CI, or GitHub Actions workflow was run.
- Browser Bridge 1.1.0 and the chat command route are source changes only; Chrome/Edge behavior is **NOT VERIFIED**.
- The current 10-neuron signal preprocessor remains in the active desktop conversation path until the typed-evidence integration and persistence gates above are completed.
