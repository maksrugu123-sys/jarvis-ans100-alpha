# JARVIS Browser Bridge — Chrome / Edge

This is a Manifest V3 companion extension for the JARVIS local desktop console.

## Install

1. Start JARVIS on the PC and open its local console.
2. Download the extension ZIP from the **Браузер JARVIS** panel.
3. Extract the ZIP into a normal folder (do not delete the folder after installation).
4. In Chrome open `chrome://extensions`; in Microsoft Edge open `edge://extensions`.
5. Turn on **Developer mode**.
6. Select **Load unpacked** and choose the extracted `jarvis-browser-extension` folder.
7. Pin **JARVIS Browser Bridge** to the toolbar.
8. Open the JARVIS Core terminal, copy the one-time 43-character access token and paste it into the extension popup. Press **Подключить**.

The extension does not install itself automatically and is not published in either browser's store.

## Features

- Open a public HTTP/HTTPS address in a new browser tab.
- Search the web in a new Bing tab.
- On a deliberate button press, read up to 12,000 characters of visible text from the active tab and send it to the local JARVIS Core for a question/summary.
- The active page is not read in the background. The extension uses the browser's temporary `activeTab` permission, granted after the user opens the extension, and injects a read-only extraction function only after pressing the send button.
- The local API token is stored in `chrome.storage.local` on this browser profile. Use **Забыть токен** to remove it.

## Boundaries

- The extension only connects to `http://127.0.0.1:8765`; it does not expose JARVIS to the network.
- It cannot read browser history, cookies, passwords, bookmarks or all tabs.
- It does not submit forms, click page buttons, download files, upload data, log into accounts or execute shell commands.
- Internal/private/localhost page URLs are rejected by the JARVIS server before their text is accepted.
- Text from a web page is untrusted context. It cannot authorize JARVIS actions.
- The local chat must have a working configured inference runtime to answer page questions. A no-model fallback is not a general-purpose page reader.

The extension has not yet been tested in a real Chrome or Edge installation. The repository does not contain a signed CRX or automatic installer; use the unpacked developer-mode installation above.
