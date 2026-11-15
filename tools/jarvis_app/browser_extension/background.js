"use strict";

const REGISTRY_KEY = "jarvisOwnedBrowserResources";
const MAX_OWNED_ITEMS = 40;

function validatePublicUrl(value) {
  if (typeof value !== "string" || value.length > 2048) {
    throw new Error("Адрес сайта недопустим.");
  }
  let url;
  try { url = new URL(value); }
  catch { throw new Error("Не удалось распознать адрес сайта."); }
  const host = url.hostname.toLowerCase();
  const localHost = host === "localhost" || host === "0.0.0.0" || host === "[::1]" ||
    /\\.(localhost|local|internal|lan|home|home\\.arpa|test|invalid|private|corp)$/u.test(host);
  const ipv4 = /^\\d{1,3}(?:\\.\\d{1,3}){3}$/u.test(host);
  if (!["http:", "https:"].includes(url.protocol) || !host.includes(".") ||
      url.username || url.password || localHost || ipv4) {
    throw new Error("Разрешены только публичные HTTP/HTTPS адреса.");
  }
  url.hash = "";
  return url.href;
}

async function readRegistry() {
  const stored = await chrome.storage.local.get(REGISTRY_KEY);
  const value = stored[REGISTRY_KEY];
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {tabs: [], windows: []};
  }
  return {
    tabs: Array.isArray(value.tabs) ? value.tabs.filter(item =>
      item && Number.isInteger(item.id) && Number.isInteger(item.windowId)
    ).slice(-MAX_OWNED_ITEMS) : [],
    windows: Array.isArray(value.windows) ? value.windows.filter(item =>
      item && Number.isInteger(item.id)
    ).slice(-MAX_OWNED_ITEMS) : [],
  };
}

async function writeRegistry(registry) {
  await chrome.storage.local.set({
    [REGISTRY_KEY]: {
      tabs: registry.tabs.slice(-MAX_OWNED_ITEMS),
      windows: registry.windows.slice(-MAX_OWNED_ITEMS),
    },
  });
}

async function openOwnedTab(payload) {
  const url = validatePublicUrl(payload.url);
  const tab = await chrome.tabs.create({url, active: true});
  if (!Number.isInteger(tab.id) || !Number.isInteger(tab.windowId)) {
    throw new Error("Браузер не вернул идентификатор новой вкладки.");
  }
  const registry = await readRegistry();
  registry.tabs.push({id: tab.id, windowId: tab.windowId});
  await writeRegistry(registry);
  return {opened: true, kind: "tab"};
}

async function openOwnedWindow(payload) {
  const url = validatePublicUrl(payload.url);
  const created = await chrome.windows.create({url, type: "normal", focused: true});
  if (!Number.isInteger(created.id)) {
    throw new Error("Браузер не вернул идентификатор нового окна.");
  }
  const tabs = await chrome.tabs.query({windowId: created.id});
  const registry = await readRegistry();
  registry.windows.push({id: created.id});
  for (const tab of tabs) {
    if (Number.isInteger(tab.id) && Number.isInteger(tab.windowId)) {
      registry.tabs.push({id: tab.id, windowId: tab.windowId});
    }
  }
  await writeRegistry(registry);
  return {opened: true, kind: "window"};
}

async function closeLastOwnedTab() {
  const registry = await readRegistry();
  while (registry.tabs.length) {
    const tab = registry.tabs[registry.tabs.length - 1];
    try {
      await chrome.tabs.get(tab.id);
      await chrome.tabs.remove(tab.id);
      registry.tabs.pop();
      const remainingInWindow = registry.tabs.some(item => item.windowId === tab.windowId);
      if (!remainingInWindow) {
        try { await chrome.windows.get(tab.windowId); }
        catch { registry.windows = registry.windows.filter(item => item.id !== tab.windowId); }
      }
      await writeRegistry(registry);
      return {closed: true, kind: "tab"};
    } catch {
      registry.tabs.pop();
    }
  }
  await writeRegistry(registry);
  throw new Error("Нет вкладок, которые JARVIS открыл своей командой.");
}

async function closeLastOwnedWindow() {
  const registry = await readRegistry();
  while (registry.windows.length) {
    const win = registry.windows[registry.windows.length - 1];
    try {
      await chrome.windows.get(win.id);
      await chrome.windows.remove(win.id);
      registry.windows.pop();
      registry.tabs = registry.tabs.filter(item => item.windowId !== win.id);
      await writeRegistry(registry);
      return {closed: true, kind: "window"};
    } catch {
      registry.windows.pop();
      registry.tabs = registry.tabs.filter(item => item.windowId !== win.id);
    }
  }
  await writeRegistry(registry);
  throw new Error("Нет окон, которые JARVIS открыл своей командой.");
}

async function handleCommand(message, sender) {
  if (sender.id !== chrome.runtime.id || sender.frameId !== 0) {
    throw new Error("Источник команды не разрешён.");
  }
  let senderUrl;
  try { senderUrl = new URL(sender.url || ""); }
  catch { throw new Error("Команда должна поступать из локального окна JARVIS."); }
  if (senderUrl.origin !== "http://127.0.0.1:8765") {
    throw new Error("Команда должна поступать из локального окна JARVIS.");
  }
  const payload = message.payload && typeof message.payload === "object" &&
    !Array.isArray(message.payload) ? message.payload : {};
  switch (message.action) {
    case "ping":
      return {connected: true, ownedTabCount: (await readRegistry()).tabs.length,
        ownedWindowCount: (await readRegistry()).windows.length};
    case "open-tab":
      if (Object.keys(payload).join(",") !== "url") throw new Error("Неверные параметры open-tab.");
      return openOwnedTab(payload);
    case "open-window":
      if (Object.keys(payload).join(",") !== "url") throw new Error("Неверные параметры open-window.");
      return openOwnedWindow(payload);
    case "close-last-tab":
      if (Object.keys(payload).length) throw new Error("close-last-tab не принимает параметры.");
      return closeLastOwnedTab();
    case "close-last-window":
      if (Object.keys(payload).length) throw new Error("close-last-window не принимает параметры.");
      return closeLastOwnedWindow();
    default:
      throw new Error("Команда браузера не входит в разрешённый список.");
  }
}

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  if (!message || message.type !== "JARVIS_BRIDGE_COMMAND") return false;
  handleCommand(message, sender)
    .then(result => sendResponse({ok: true, result}))
    .catch(error => sendResponse({
      ok: false,
      error: error instanceof Error ? error.message : "Команда браузера не выполнена.",
    }));
  return true;
});
