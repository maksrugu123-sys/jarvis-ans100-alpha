const $ = (id) => document.getElementById(id);
const BASE_URL = "http://127.0.0.1:8765";
let accessToken = "";

function show(message, kind = "info") {
  $("result").textContent = message;
  $("result").dataset.kind = kind;
}

function setStatus(message, ready = false) {
  $("status").textContent = message;
  document.querySelector(".dot").classList.toggle("ready", ready);
}

async function request(path, {method = "GET", body} = {}) {
  if (!accessToken) throw new Error("Сначала подключите JARVIS и сохраните токен.");
  const headers = {Authorization: "Bearer " + accessToken};
  const options = {method, headers, cache: "no-store"};
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
    options.body = JSON.stringify(body);
  }
  let response;
  try {
    response = await fetch(BASE_URL + path, options);
  } catch {
    throw new Error("Не удалось связаться с JARVIS. Запустите локальное ядро на этом ПК.");
  }
  let payload;
  try { payload = await response.json(); }
  catch { throw new Error("JARVIS вернул некорректный ответ."); }
  if (!response.ok) {
    if (response.status === 401) {
      await chrome.storage.local.remove("jarvisAccessToken");
      accessToken = "";
      $("token").value = "";
      setStatus("Токен отклонён — подключитесь заново.");
    }
    throw new Error(payload.error || "Запрос не выполнен.");
  }
  return payload;
}

function normalizePublicWebUrl(value) {
  let raw = value.trim();
  if (!raw) throw new Error("Введите адрес сайта.");
  if (!/^[a-z][a-z0-9+.-]*:/i.test(raw)) raw = "https://" + raw;
  let parsed;
  try { parsed = new URL(raw); }
  catch { throw new Error("Не удалось распознать адрес сайта."); }
  if (!["http:", "https:"].includes(parsed.protocol) || !parsed.hostname ||
      parsed.username || parsed.password) {
    throw new Error("Разрешены только обычные HTTP/HTTPS адреса без логина и пароля.");
  }
  return parsed.href;
}

async function activeTab() {
  const tabs = await chrome.tabs.query({active: true, lastFocusedWindow: true});
  const tab = tabs[0];
  if (!tab || typeof tab.id !== "number") throw new Error("Не удалось определить активную вкладку.");
  return tab;
}

async function connect() {
  const token = $("token").value.trim();
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) {
    throw new Error("Токен должен содержать 43 символа Base64URL.");
  }
  accessToken = token;
  const state = await request("/api/extension/state");
  await chrome.storage.local.set({jarvisAccessToken: token});
  setStatus("Подключено · " + (state.backend_label || state.chat_mode), true);
  $("ask").disabled = !state.chat_available;
  show("Связь с локальным JARVIS установлена.", "success");
}

async function refreshConnection() {
  const stored = await chrome.storage.local.get("jarvisAccessToken");
  accessToken = typeof stored.jarvisAccessToken === "string" ? stored.jarvisAccessToken : "";
  if (!accessToken) {
    setStatus("Требуется подключение к JARVIS.");
    $("ask").disabled = true;
    return;
  }
  try {
    const state = await request("/api/extension/state");
    setStatus("Подключено · " + (state.backend_label || state.chat_mode), true);
    $("ask").disabled = !state.chat_available;
  } catch (error) {
    setStatus(error.message);
    $("ask").disabled = true;
  }
}

$("connect").addEventListener("click", async () => {
  $("connect").disabled = true;
  try { await connect(); }
  catch (error) { show(error.message, "error"); setStatus("Нет подключения."); }
  finally { $("connect").disabled = false; }
});

$("forget").addEventListener("click", async () => {
  await chrome.storage.local.remove("jarvisAccessToken");
  accessToken = "";
  $("token").value = "";
  $("ask").disabled = true;
  setStatus("Токен удалён из хранилища расширения.");
  show("Расширение отключено.", "success");
});

$("open").addEventListener("click", async () => {
  try {
    const url = normalizePublicWebUrl($("target").value);
    await chrome.tabs.create({url, active: true});
    show("Сайт открыт в новой вкладке.", "success");
  } catch (error) { show(error.message, "error"); }
});

$("search").addEventListener("click", async () => {
  const query = $("target").value.trim();
  if (!query || query.length > 500) {
    show("Введите поисковый запрос длиной до 500 символов.", "error");
    return;
  }
  await chrome.tabs.create({
    url: "https://www.bing.com/search?q=" + encodeURIComponent(query),
    active: true,
  });
  show("Поиск открыт в новой вкладке.", "success");
});

$("ask").addEventListener("click", async () => {
  const button = $("ask");
  const question = $("question").value.trim();
  if (!question || question.length > 900) {
    show("Введите вопрос длиной от 1 до 900 символов.", "error");
    return;
  }
  button.disabled = true;
  button.textContent = "Передаю страницу…";
  try {
    const tab = await activeTab();
    const extracted = await chrome.scripting.executeScript({
      target: {tabId: tab.id},
      func: () => ({
        title: (document.title || "").slice(0, 300),
        url: location.href,
        text: (document.body?.innerText || "").slice(0, 12000),
      }),
    });
    const page = extracted?.[0]?.result;
    if (!page || !page.text.trim()) throw new Error("На этой вкладке нет доступного текста страницы.");
    const state = await request("/api/extension/state");
    const result = await request("/api/extension/ask", {
      method: "POST",
      body: {
        expected_revision: state.revision,
        question,
        title: page.title || page.url,
        url: page.url,
        text: page.text,
      },
    });
    const messages = result.conversation?.messages || [];
    const answer = [...messages].reverse().find(item => item.role === "assistant")?.content;
    $("page").textContent = page.title + " · " + page.url;
    show(answer || "Страница передана JARVIS.", "success");
  } catch (error) {
    show(error.message, "error");
  } finally {
    button.textContent = "Передать страницу и спросить";
    button.disabled = false;
  }
});

(async function boot() {
  const tab = await activeTab().catch(() => null);
  if (tab) $("page").textContent = (tab.title || "Текущая вкладка") + "\n" + (tab.url || "");
  await refreshConnection();
})();
