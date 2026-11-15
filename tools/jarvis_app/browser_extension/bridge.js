"use strict";

const JARVIS_PAGE_SOURCE = "JARVIS_LOCAL_CONSOLE";
const EXTENSION_SOURCE = "JARVIS_BROWSER_EXTENSION";
const ALLOWED_ACTIONS = new Set([
  "ping",
  "open-tab",
  "open-window",
  "close-last-tab",
  "close-last-window",
]);

window.addEventListener("message", event => {
  if (event.source !== window || event.origin !== location.origin) return;
  const message = event.data;
  if (!message || message.source !== JARVIS_PAGE_SOURCE ||
      message.type !== "extension-command" ||
      typeof message.requestId !== "string" ||
      !/^[A-Za-z0-9-]{16,80}$/u.test(message.requestId) ||
      !ALLOWED_ACTIONS.has(message.action)) {
    return;
  }
  const payload = message.payload && typeof message.payload === "object" &&
    !Array.isArray(message.payload) ? message.payload : {};
  chrome.runtime.sendMessage({
    type: "JARVIS_BRIDGE_COMMAND",
    action: message.action,
    payload,
  }, response => {
    const lastError = chrome.runtime.lastError;
    const result = lastError
      ? {ok: false, error: "Расширение JARVIS Browser Bridge недоступно."}
      : response;
    window.postMessage({
      source: EXTENSION_SOURCE,
      type: "result",
      requestId: message.requestId,
      ok: Boolean(result && result.ok),
      result: result && result.result,
      error: result && result.error,
    }, location.origin);
  });
});
