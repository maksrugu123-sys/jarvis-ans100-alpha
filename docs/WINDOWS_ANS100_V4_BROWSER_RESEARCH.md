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

### Recommended JARVIS design

Keep two explicit browser modes:

1. **Connected Chrome/Edge (extension mode):** user installs and explicitly connects the Browser Bridge; JARVIS sends typed commands through a loopback-only authenticated bridge; the extension returns structured results. Only the active/selected tab is exposed, and page extraction happens only for a user-requested read. Do not request cookies, history, bookmarks, all-sites access, or downloads.
2. **Isolated research browser (Playwright mode):** retain the existing visible, headed Chromium with its separate persistent JARVIS profile and read-only request guard. It remains the fallback for research when the extension is unavailable.

The extension should use a background service worker with reconnect/backoff and a local authenticated transport, plus a small command allowlist (open URL, search, read selected page, report tab state). Every command should have an ID, expiry, selected-tab scope, explicit result/error response and audit entry. Destructive actions, form submission, downloads, account changes and message sending remain disabled until a separate permission/confirmation design exists.

Do not attach to a user's personal browser through remote-debugging flags or copy cookies into the isolated profile. Existing JARVIS extension permissions remain unchanged in this milestone.

## Verification record

- `tools/neuron_runtime/test_hybrid_ans100.py`: authored, **NOT RUN**.
- No Python tests, Windows launch, browser extension installation, Playwright launch, model inference, Gradle build, CI, or GitHub Actions workflow was run.
- The current 10-neuron signal preprocessor remains in the active desktop conversation path until the typed-evidence integration and persistence gates above are completed.
