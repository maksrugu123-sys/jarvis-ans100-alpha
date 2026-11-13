const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

let accessToken = sessionStorage.getItem("jarvis.console.access.v2") || "";
let refreshTimer = null;

function lockConsole(message) {
  accessToken = "";
  sessionStorage.removeItem("jarvis.console.access.v2");
  if (refreshTimer !== null) {
    window.clearInterval(refreshTimer);
    refreshTimer = null;
  }
  document.body.classList.add("auth-required");
  $("#auth-panel").hidden = false;
  $("#logout").hidden = true;
  if (message) setNotice(message, "error");
}

function unlockConsole() {
  document.body.classList.remove("auth-required");
  $("#auth-panel").hidden = true;
  $("#logout").hidden = false;
}

$("#logout").addEventListener("click", () => {
  lockConsole("Вы вышли из локальной консоли. Для входа нужен токен доступа.");
});

async function api(path, body) {
  const headers = { "Accept": "application/json" };
  if (accessToken) headers.Authorization = "Bearer " + accessToken;
  if (body !== undefined) headers["Content-Type"] = "application/json";
  const response = await fetch(path, {
    method: body === undefined ? "GET" : "POST",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    cache: "no-store",
  });
  let payload;
  try { payload = await response.json(); }
  catch { throw new Error("Сервер вернул некорректный ответ."); }
  if (response.status === 401 && path !== "/api/health") {
    lockConsole(payload.error || "Токен доступа отклонён. Вставьте актуальный токен.");
  }
  if (!response.ok) {
    const error = new Error(payload.error || "Локальный запрос не выполнен.");
    error.status = response.status;
    throw error;
  }
  return payload;
}

function setNotice(message, type = "info") {
  const box = $("#notice");
  box.textContent = message;
  box.dataset.type = type;
}

function node(tag, className, text) {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== undefined) element.textContent = String(text);
  return element;
}

const STATUS_LABELS = {
  open: "Открыта", paused: "Пауза", closed: "Закрыта",
  queued: "В очереди", running: "В работе", waiting: "Ожидает",
  completed: "Завершена", failed: "Ошибка", cancelled: "Отменена",
};
let currentState = null;

function statusLabel(status) {
  return STATUS_LABELS[status] || status;
}

async function changeSessionStatus(session, status, button) {
  button.disabled = true;
  try {
    await api("/api/sessions/" + encodeURIComponent(session.session_id) + "/status", {
      status,
      expected_revision: session.revision,
    });
    await refresh();
    setNotice("Статус сессии изменён: " + statusLabel(status) + ".", "success");
  } catch (error) {
    setNotice(error.message, "error");
    button.disabled = false;
  }
}

function renderSessions(state) {
  const sessionsRoot = $("#sessions");
  sessionsRoot.replaceChildren();
  if (!state.sessions.length) {
    sessionsRoot.append(node("p", "empty", "Пока нет сессий."));
    return;
  }

  for (const session of state.sessions.slice().reverse()) {
    const card = node("article", "record-card");
    const heading = node("div", "record-heading");
    heading.append(node("strong", "", session.title));
    heading.append(node("span", "status " + session.status, statusLabel(session.status)));
    card.append(heading);
    card.append(node("small", "muted", session.session_id + " · ревизия " + session.revision));

    const relatedTasks = state.tasks.filter(item => item.session_id === session.session_id);
    const activeTasks = relatedTasks.filter(item =>
      !["completed", "failed", "cancelled"].includes(item.status)
    );
    card.append(node(
      "small", "muted",
      relatedTasks.length + " задач · активных: " + activeTasks.length
    ));

    const actions = node("div", "session-actions");
    if (session.status === "open") {
      const pause = node("button", "text-button", "Пауза");
      pause.type = "button";
      pause.addEventListener("click", () => changeSessionStatus(session, "paused", pause));
      actions.append(pause);
    } else if (session.status === "paused") {
      const resume = node("button", "text-button", "Продолжить");
      resume.type = "button";
      resume.addEventListener("click", () => changeSessionStatus(session, "open", resume));
      actions.append(resume);
    }

    if (session.status !== "closed") {
      const close = node("button", "text-button", "Закрыть");
      close.type = "button";
      close.disabled = activeTasks.length > 0;
      close.title = close.disabled
        ? "Сначала завершите или отмените все задачи сессии"
        : "Закрыть сессию";
      close.addEventListener("click", () => changeSessionStatus(session, "closed", close));
      actions.append(close);
    }
    if (actions.childElementCount) card.append(actions);
    if (session.status !== "closed" && activeTasks.length > 0) {
      card.append(node("small", "muted", "Для закрытия завершите активные задачи."));
    }
    sessionsRoot.append(card);
  }
}

function renderTaskList(state) {
  const tasksRoot = $("#tasks");
  tasksRoot.replaceChildren();

  const selectedStatus = $("#task-status-filter").value;
  const query = $("#task-search").value.trim().toLocaleLowerCase();
  const filtered = state.tasks.filter(task => {
    const matchesStatus = selectedStatus === "all" || task.status === selectedStatus;
    const haystack = (task.title + " " + task.task_id).toLocaleLowerCase();
    return matchesStatus && (!query || haystack.includes(query));
  });

  const counts = state.tasks.reduce((result, task) => {
    result[task.status] = (result[task.status] || 0) + 1;
    return result;
  }, {});
  $("#task-counts").textContent =
    "Показано " + filtered.length + " из " + state.tasks.length +
    " · в очереди " + (counts.queued || 0) +
    " · в работе " + (counts.running || 0) +
    " · ожидают " + (counts.waiting || 0);

  if (!state.tasks.length) {
    tasksRoot.append(node("p", "empty", "Пока нет задач."));
    return;
  }
  if (!filtered.length) {
    tasksRoot.append(node("p", "empty", "По выбранным фильтрам задач нет."));
    return;
  }

  const sessionTitles = new Map(state.sessions.map(item => [item.session_id, item.title]));
  for (const task of filtered.slice().reverse()) {
    const card = node("article", "record-card");
    const heading = node("div", "record-heading");
    heading.append(node("strong", "", task.title));
    heading.append(node("span", "status " + task.status, statusLabel(task.status)));
    card.append(heading);
    card.append(node(
      "small", "muted",
      (sessionTitles.get(task.session_id) || task.session_id) + " · " +
      task.task_id + " · ревизия " + task.revision
    ));

    if (task.failure_code) {
      card.append(node("small", "error-text", "Код ошибки: " + task.failure_code));
    }
    if (task.result) {
      const details = node("details", "task-details");
      details.append(node("summary", "", "Данные результата"));
      const result = node("pre", "result-json", JSON.stringify(task.result, null, 2));
      details.append(result);
      card.append(details);
    }

    if (task.status === "queued") {
      const cancel = node("button", "text-button", "Отменить");
      cancel.type = "button";
      cancel.addEventListener("click", async () => {
        cancel.disabled = true;
        try {
          await api("/api/tasks/" + encodeURIComponent(task.task_id) + "/cancel", {
            expected_revision: task.revision,
          });
          await refresh();
          setNotice("Задача отменена.", "success");
        } catch (error) {
          setNotice(error.message, "error");
          cancel.disabled = false;
        }
      });
      card.append(cancel);
    }
    tasksRoot.append(card);
  }
}

function renderState(state) {
  currentState = state;
  const activeCount = state.tasks.filter(item =>
    ["queued", "running", "waiting"].includes(item.status)
  ).length;
  $("#session-count").textContent = state.sessions.length;
  $("#task-count").textContent = state.tasks.length;
  $("#active-count").textContent = activeCount;
  $("#checkpoint-version").textContent = state.checkpoint_version;

  renderSessions(state);
  const openSessions = state.sessions.filter(item => item.status === "open");
  const select = $("#task-session");
  const previous = select.value;
  select.replaceChildren();
  if (!openSessions.length) {
    const option = node("option", "", "Нет открытых сессий");
    option.value = "";
    select.append(option);
  } else {
    for (const session of openSessions) {
      const option = node("option", "", session.title);
      option.value = session.session_id;
      select.append(option);
    }
    if (openSessions.some(item => item.session_id === previous)) {
      select.value = previous;
    }
  }

  renderTaskList(state);

  const eventsRoot = $("#events");
  eventsRoot.replaceChildren();
  if (!state.events.length) {
    eventsRoot.append(node("p", "empty", "Событий пока нет."));
  } else {
    for (const event of state.events.slice().reverse()) {
      const row = node("div", "event-row");
      row.append(node("span", "event-number", "#" + event.sequence));
      const kind = event.entity_type === "session" ? "Сессия" : "Задача";
      row.append(node("span", "event-description", kind + " · " + event.entity_id));
      row.append(node("span", "event-transition",
        (event.from_status ? statusLabel(event.from_status) : "Создана") +
        " → " + statusLabel(event.to_status)));
      eventsRoot.append(row);
    }
  }
}

let currentWorkspace = null;
let consolePlatform = "desktop";

function canonicalJson(value) {
  if (Array.isArray(value)) return "[" + value.map(canonicalJson).join(",") + "]";
  if (value !== null && typeof value === "object") {
    return "{" + Object.keys(value).sort().map(key =>
      JSON.stringify(key) + ":" + canonicalJson(value[key])
    ).join(",") + "}";
  }
  return JSON.stringify(value);
}

async function sealWorkspace(document) {
  delete document.integrity;
  const bytes = new TextEncoder().encode(canonicalJson(document));
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  const hex = Array.from(new Uint8Array(digest), byte =>
    byte.toString(16).padStart(2, "0")
  ).join("");
  document.integrity = { algorithm: "sha-256", digest: hex };
  return document;
}

function renderMemories() {
  const root = $("#memories");
  if (!root || !currentWorkspace) return;
  root.replaceChildren();
  const records = currentWorkspace.document.records.memories || [];
  const query = $("#memory-search").value.trim().toLocaleLowerCase();
  const visible = records.filter(item =>
    (item.content + " " + item.category).toLocaleLowerCase().includes(query)
  ).slice().reverse();
  $("#memory-revision").textContent = "REV " + currentWorkspace.revision;
  $("#memory-counts").textContent = visible.length + " из " + records.length + " воспоминаний";
  if (!visible.length) {
    root.append(node("p", "empty", records.length ? "Совпадений не найдено." : "Память пока пуста."));
    return;
  }
  for (const memory of visible) {
    const card = node("article", "record-card");
    const heading = node("div", "record-heading");
    heading.append(node("strong", "", memory.category || "general"));
    heading.append(node("span", "tag", "Подтверждено"));
    card.append(heading);
    card.append(node("p", "memory-content", memory.content));
    card.append(node("small", "muted", "Обновлено: " + memory.updated_at + " · " + memory.id));
    const remove = node("button", "text-button", "Удалить воспоминание");
    remove.type = "button";
    remove.addEventListener("click", () => deleteMemory(memory.id, remove));
    card.append(remove);
    root.append(card);
  }
}

async function refreshWorkspace() {
  const result = await api("/api/workspace");
  if (!result.workspace || !Number.isSafeInteger(result.revision)) {
    throw new Error("Сервис вернул некорректную версию общей памяти.");
  }
  currentWorkspace = { revision: result.revision, document: result.workspace };
  renderMemories();
  return currentWorkspace;
}

async function writeWorkspace(mutator) {
  if (!currentWorkspace) await refreshWorkspace();
  const baseRevision = currentWorkspace.revision;
  const document = JSON.parse(JSON.stringify(currentWorkspace.document));
  mutator(document);
  document.exported_at = new Date().toISOString();
  document.source_platform = consolePlatform;
  await sealWorkspace(document);
  try {
    const result = await api("/api/workspace", {
      expected_revision: baseRevision,
      workspace: document,
    });
    currentWorkspace = { revision: result.revision, document: result.workspace };
    renderMemories();
    return currentWorkspace;
  } catch (error) {
    if (error.status === 409) await refreshWorkspace();
    throw error;
  }
}

async function deleteMemory(memoryId, button) {
  if (!window.confirm("Удалить это воспоминание из общей памяти JARVIS?")) return;
  button.disabled = true;
  try {
    await writeWorkspace(document => {
      document.records.memories = document.records.memories.filter(item => item.id !== memoryId);
    });
    setNotice("Воспоминание удалено из общей памяти.", "success");
  } catch (error) {
    setNotice(error.message, "error");
    button.disabled = false;
  }
}

$("#memory-form").addEventListener("submit", async event => {
  event.preventDefault();
  const content = $("#memory-content").value.trim();
  const category = $("#memory-category").value.trim();
  if (!content || !category || !$("#memory-approval").checked) {
    setNotice("Подтвердите сохранение и заполните содержимое и категорию.", "error");
    return;
  }
  const submit = event.submitter;
  if (submit) submit.disabled = true;
  try {
    await writeWorkspace(document => {
      if (document.records.memories.length >= 10000) {
        throw new Error("Достигнут лимит в 10 000 воспоминаний.");
      }
      document.records.memories.push({
        id: crypto.randomUUID().replaceAll("-", ""),
        revision: 0,
        updated_at: new Date().toISOString(),
        content,
        category,
        user_approved: true,
      });
    });
    $("#memory-content").value = "";
    $("#memory-approval").checked = false;
    setNotice("Воспоминание сохранено в JPVR.", "success");
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    if (submit) submit.disabled = false;
  }
});

$("#memory-search").addEventListener("input", renderMemories);
$("#memory-refresh").addEventListener("click", async event => {
  const button = event.currentTarget;
  button.disabled = true;
  try {
    await refreshWorkspace();
    setNotice("Общая память обновлена.", "success");
  } catch (error) { setNotice(error.message, "error"); }
  finally { button.disabled = false; }
});



const TRANSFER_AAD = new TextEncoder().encode("JARVIS-WORKSPACE-ENCRYPTED-V1");
const TRANSFER_ITERATIONS = 310000;
const TRANSFER_MAX_PLAINTEXT = 16 * 1024 * 1024;
let pendingTransfer = null;

function bytesToBase64(bytes) {
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

function base64ToBytes(value, maximum) {
  if (typeof value !== "string" || value.length > maximum * 2) {
    throw new Error("Зашифрованный файл имеет недопустимый размер.");
  }
  let binary;
  try { binary = atob(value); }
  catch { throw new Error("Файл содержит некорректные Base64-данные."); }
  if (!binary.length || binary.length > maximum || btoa(binary) !== value) {
    throw new Error("Размер или кодировка данных в файле недопустимы.");
  }
  return Uint8Array.from(binary, character => character.charCodeAt(0));
}

async function deriveTransferKey(passphrase, salt) {
  const material = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(passphrase), "PBKDF2", false, ["deriveKey"]
  );
  return crypto.subtle.deriveKey(
    { name: "PBKDF2", hash: "SHA-256", salt, iterations: TRANSFER_ITERATIONS },
    material, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]
  );
}

function isTransferDateTime(value) {
  return typeof value === "string" &&
    /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value));
}

function hasOnlyKeys(value, allowed, required) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false;
  const keys = Object.keys(value);
  return keys.every(key => allowed.includes(key)) &&
    required.every(key => Object.hasOwn(value, key));
}

function validWorkspaceRecord(record, type) {
  const idPattern = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;
  if (!record || typeof record !== "object" || Array.isArray(record) ||
      !idPattern.test(record.id || "") ||
      !Number.isInteger(record.revision) || record.revision < 0 ||
      record.revision > 2147483647 || !isTransferDateTime(record.updated_at)) {
    return false;
  }
  if (type === "memories") {
    return hasOnlyKeys(record,
      ["id", "revision", "updated_at", "content", "category", "user_approved"],
      ["id", "revision", "updated_at", "content", "category", "user_approved"]) &&
      record.user_approved === true && typeof record.content === "string" &&
      record.content.trim().length > 0 && record.content.length <= 10000 &&
      typeof record.category === "string" && record.category.length <= 64;
  }
  if (type === "tasks") {
    return hasOnlyKeys(record,
      ["id", "revision", "updated_at", "title", "status", "result_summary"],
      ["id", "revision", "updated_at", "title", "status"]) &&
      typeof record.title === "string" && record.title.trim().length > 0 &&
      record.title.length <= 512 &&
      ["queued", "running", "paused", "completed", "failed", "cancelled"].includes(record.status) &&
      (!Object.hasOwn(record, "result_summary") ||
        (typeof record.result_summary === "string" && record.result_summary.length <= 4000));
  }
  if (type === "conversations") {
    if (!hasOnlyKeys(record,
      ["id", "revision", "updated_at", "title", "messages"],
      ["id", "revision", "updated_at", "title", "messages"]) ||
      typeof record.title !== "string" || record.title.length > 160 ||
      !Array.isArray(record.messages) || record.messages.length > 50000) return false;
    const messageIds = new Set();
    return record.messages.every(message =>
      hasOnlyKeys(message, ["id", "role", "created_at", "content"],
        ["id", "role", "created_at", "content"]) &&
      idPattern.test(message.id || "") && !messageIds.has(message.id) &&
      (messageIds.add(message.id), true) &&
      ["user", "assistant", "system"].includes(message.role) &&
      isTransferDateTime(message.created_at) &&
      typeof message.content === "string" && message.content.length <= 100000
    );
  }
  return false;
}

async function verifyPortableWorkspace(document) {
  if (!document || typeof document !== "object" || Array.isArray(document) ||
      Object.keys(document).sort().join(",") !==
        "export_id,exported_at,format,integrity,records,source_platform,version,workspace_id" ||
      document.format !== "jarvis.workspace" ||
      !document.version || Object.keys(document.version).sort().join(",") !== "major,minor" ||
      document.version.major !== 1 || document.version.minor !== 0 ||
      typeof document.workspace_id !== "string" ||
      !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(document.workspace_id) ||
      typeof document.export_id !== "string" ||
      !/^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/.test(document.export_id) ||
      typeof document.exported_at !== "string" || !Number.isFinite(Date.parse(document.exported_at)) ||
      !["desktop", "android"].includes(document.source_platform) ||
      !document.records || Object.keys(document.records).sort().join(",") !==
        "conversations,memories,settings,tasks" ||
      !document.integrity || Object.keys(document.integrity).sort().join(",") !== "algorithm,digest" ||
      document.integrity.algorithm !== "sha-256" ||
      typeof document.integrity.digest !== "string" ||
      !/^[a-f0-9]{64}$/.test(document.integrity.digest)) {
    throw new Error("В файле нет поддерживаемого рабочего пространства JARVIS V1.");
  }
  const records = document.records;
  for (const [name, maximum] of [
    ["conversations", 10000], ["memories", 10000], ["tasks", 10000], ["settings", 1000],
  ]) {
    if (!Array.isArray(records[name]) || records[name].length > maximum) {
      throw new Error("Коллекция " + name + " не прошла проверку размера.");
    }
  }
  const unsigned = JSON.parse(JSON.stringify(document));
  delete unsigned.integrity;
  const digest = await crypto.subtle.digest(
    "SHA-256", new TextEncoder().encode(canonicalJson(unsigned))
  );
  const actual = Array.from(new Uint8Array(digest), byte =>
    byte.toString(16).padStart(2, "0")
  ).join("");
  if (actual !== document.integrity.digest) {
    throw new Error("Контрольная сумма памяти не совпадает. Импорт отменён.");
  }
  const recordIds = new Set();
  for (const collection of ["conversations", "memories", "tasks"]) {
    for (const record of records[collection]) {
      if (!validWorkspaceRecord(record, collection) || recordIds.has(record.id)) {
        throw new Error("Коллекция " + collection + " содержит некорректную запись или повторяющийся ID.");
      }
      recordIds.add(record.id);
    }
  }
  const settingKeys = new Set();
  for (const setting of records.settings) {
    if (!hasOnlyKeys(setting, ["key", "value"], ["key", "value"]) ||
        typeof setting.key !== "string" ||
        !/^[a-z][a-z0-9._-]{0,63}$/.test(setting.key) ||
        settingKeys.has(setting.key)) {
      throw new Error("Настройки содержат недопустимые или повторяющиеся ключи.");
    }
    settingKeys.add(setting.key);
    const normalized = setting.key.replace(/[.-]/g, "_");
    if (/(password|passwd|secret|token|credential|api_key|private_key)/.test(normalized) ||
        normalized === "auth" || normalized.startsWith("auth_")) {
      throw new Error("Экспорт содержит запрещённый секретный параметр.");
    }
    const value = setting.value;
    if (!(value === null || typeof value === "string" || typeof value === "boolean" ||
        (typeof value === "number" && Number.isSafeInteger(value)))) {
      throw new Error("Значение настройки не является переносимым JSON-типом.");
    }
  }
  return document;
}

async function encryptWorkspaceDocument(document, passphrase) {
  const plaintext = new TextEncoder().encode(canonicalJson(document));
  if (!plaintext.length || plaintext.length > TRANSFER_MAX_PLAINTEXT) {
    throw new Error("Рабочее пространство превышает лимит переносимого файла 16 МиБ.");
  }
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveTransferKey(passphrase, salt);
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv: nonce, additionalData: TRANSFER_AAD, tagLength: 128 },
    key, plaintext
  );
  return {
    format: "jarvis.workspace.encrypted",
    version: { major: 1, minor: 0 },
    kdf: { name: "PBKDF2-HMAC-SHA256", iterations: TRANSFER_ITERATIONS, salt: bytesToBase64(salt) },
    cipher: { name: "AES-256-GCM", nonce: bytesToBase64(nonce), tag_bits: 128 },
    ciphertext: bytesToBase64(new Uint8Array(ciphertext)),
  };
}

async function decryptWorkspaceBundle(bundle, passphrase) {
  if (!bundle || typeof bundle !== "object" || Array.isArray(bundle) ||
      Object.keys(bundle).sort().join(",") !== "cipher,ciphertext,format,kdf,version" ||
      bundle.format !== "jarvis.workspace.encrypted" ||
      !bundle.version || Object.keys(bundle.version).sort().join(",") !== "major,minor" ||
      bundle.version.major !== 1 || bundle.version.minor !== 0 ||
      !bundle.kdf || Object.keys(bundle.kdf).sort().join(",") !== "iterations,name,salt" ||
      bundle.kdf.name !== "PBKDF2-HMAC-SHA256" ||
      bundle.kdf.iterations !== TRANSFER_ITERATIONS ||
      !bundle.cipher || Object.keys(bundle.cipher).sort().join(",") !== "name,nonce,tag_bits" ||
      bundle.cipher.name !== "AES-256-GCM" || bundle.cipher.tag_bits !== 128) {
    throw new Error("Формат зашифрованного файла не поддерживается.");
  }
  const salt = base64ToBytes(bundle.kdf.salt, 16);
  const nonce = base64ToBytes(bundle.cipher.nonce, 12);
  if (salt.length !== 16 || nonce.length !== 12) {
    throw new Error("Параметры шифрования имеют неверную длину.");
  }
  const ciphertext = base64ToBytes(bundle.ciphertext, TRANSFER_MAX_PLAINTEXT + 16);
  if (ciphertext.length < 17) throw new Error("Зашифрованные данные слишком короткие.");
  const key = await deriveTransferKey(passphrase, salt);
  let plaintext;
  try {
    plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv: nonce, additionalData: TRANSFER_AAD, tagLength: 128 },
      key, ciphertext
    );
  } catch {
    throw new Error("Не удалось расшифровать файл. Проверьте пароль или целостность файла.");
  }
  if (plaintext.byteLength > TRANSFER_MAX_PLAINTEXT) {
    throw new Error("Расшифрованное рабочее пространство превышает лимит 16 МиБ.");
  }
  let document;
  let plaintextText;
  try {
    plaintextText = new TextDecoder("utf-8", { fatal: true }).decode(plaintext);
    document = JSON.parse(plaintextText);
  } catch {
    throw new Error("Расшифрованные данные не являются корректным UTF-8 JSON.");
  }
  if (canonicalJson(document) !== plaintextText) {
    throw new Error("Рабочее пространство не сохранено в каноническом JSON-формате.");
  }
  return verifyPortableWorkspace(document);
}

function planWorkspaceMerge(localDocument, importedDocument) {
  if (localDocument.workspace_id !== importedDocument.workspace_id) {
    throw new Error("Файл относится к другому workspace_id. Объединение запрещено.");
  }
  const result = JSON.parse(JSON.stringify(localDocument.records));
  const summary = { added: 0, identical: 0, conflicts: 0 };
  for (const collection of ["conversations", "memories", "tasks"]) {
    const existing = new Map(result[collection].map(item => [item.id, item]));
    for (const item of importedDocument.records[collection]) {
      const old = existing.get(item.id);
      if (!old) {
        result[collection].push(item);
        existing.set(item.id, item);
        summary.added++;
      } else if (canonicalJson(old) === canonicalJson(item)) {
        summary.identical++;
      } else {
        summary.conflicts++;
      }
    }
  }
  const settings = new Map(result.settings.map(item => [item.key, item]));
  for (const item of importedDocument.records.settings) {
    const old = settings.get(item.key);
    if (!old) {
      result.settings.push(item);
      settings.set(item.key, item);
      summary.added++;
    } else if (canonicalJson(old) === canonicalJson(item)) {
      summary.identical++;
    } else {
      summary.conflicts++;
    }
  }
  return { records: result, summary };
}

function renderTransferPreview(plan) {
  const box = $("#transfer-preview");
  box.hidden = false;
  box.textContent = "Будет добавлено: " + plan.added +
    " · уже совпадает: " + plan.identical +
    " · конфликтов (останутся локальные версии): " + plan.conflicts;
  $("#transfer-confirm").hidden = plan.added === 0;
}

$("#transfer-export").addEventListener("click", async event => {
  const button = event.currentTarget;
  const passwordInput = $("#transfer-passphrase");
  const passphrase = passwordInput.value;
  if (passphrase.length < 12) {
    setNotice("Для экспорта нужна парольная фраза длиной не менее 12 символов.", "error");
    return;
  }
  button.disabled = true;
  try {
    const workspace = await refreshWorkspace();
    await verifyPortableWorkspace(workspace.document);
    const bundle = await encryptWorkspaceDocument(workspace.document, passphrase);
    const blob = new Blob([JSON.stringify(bundle)], { type: "application/vnd.jarvis.workspace+json" });
    const url = URL.createObjectURL(blob);
    const anchor = node("a");
    anchor.href = url;
    anchor.download = "jarvis-workspace-" + new Date().toISOString().slice(0, 10) + ".jws";
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
    setNotice("Зашифрованный файл создан. Храните пароль отдельно от файла.", "success");
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    passwordInput.value = "";
    button.disabled = false;
  }
});

$("#transfer-choose").addEventListener("click", () => $("#transfer-file").click());
$("#transfer-file").addEventListener("change", async event => {
  const file = event.target.files && event.target.files[0];
  const passwordInput = $("#transfer-passphrase");
  const passphrase = passwordInput.value;
  $("#transfer-confirm").hidden = true;
  $("#transfer-preview").hidden = true;
  pendingTransfer = null;
  if (!file) return;
  try {
    if (file.size > 24 * 1024 * 1024) {
      throw new Error("Файл превышает допустимый размер 24 МиБ.");
    }
    if (passphrase.length < 12) {
      throw new Error("Для импорта введите парольную фразу длиной не менее 12 символов.");
    }
    const bundle = JSON.parse(await file.text());
    const imported = await decryptWorkspaceBundle(bundle, passphrase);
    const local = currentWorkspace || await refreshWorkspace();
    const plan = planWorkspaceMerge(local.document, imported);
    pendingTransfer = imported;
    renderTransferPreview(plan.summary);
    setNotice("Файл расшифрован и проверен. Изменения ещё не внесены.", "success");
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    passwordInput.value = "";
    event.target.value = "";
  }
});

$("#transfer-confirm").addEventListener("click", async event => {
  const button = event.currentTarget;
  if (!pendingTransfer) return;
  button.disabled = true;
  try {
    const imported = pendingTransfer;
    let finalPlan = null;
    await writeWorkspace(document => {
      finalPlan = planWorkspaceMerge(document, imported);
      document.records = finalPlan.records;
    });
    pendingTransfer = null;
    $("#transfer-preview").hidden = true;
    button.hidden = true;
    setNotice("Перенос завершён: добавлено " + finalPlan.summary.added +
      ", совпадений " + finalPlan.summary.identical +
      ", конфликтов без перезаписи " + finalPlan.summary.conflicts + ".", "success");
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    button.disabled = false;
  }
});

async function refresh() {
  const state = await api("/api/state");
  renderState(state);
  await refreshWorkspace();
  return state;
}

$("#session-form").addEventListener("submit", async event => {
  event.preventDefault();
  const input = $("#session-title");
  try {
    await api("/api/sessions", { title: input.value });
    input.value = "";
    await refresh();
    setNotice("Сессия сохранена в JPVR.", "success");
  } catch (error) { setNotice(error.message, "error"); }
});

$("#task-search").addEventListener("input", () => {
  if (currentState) renderTaskList(currentState);
});
$("#task-status-filter").addEventListener("change", () => {
  if (currentState) renderTaskList(currentState);
});

$("#task-form").addEventListener("submit", async event => {
  event.preventDefault();
  const title = $("#task-title");
  try {
    await api("/api/tasks", {
      session_id: $("#task-session").value,
      title: title.value,
    });
    title.value = "";
    await refresh();
    setNotice("Задача создана и сохранена в JPVR.", "success");
  } catch (error) { setNotice(error.message, "error"); }
});

function splitFacts(value) {
  return value.split(",").map(item => item.trim()).filter(Boolean);
}

function addActionRow(values = {}) {
  const row = node("div", "action-row");
  const grid = node("div", "action-grid");
  const fields = [
    ["action_id", "ID", values.action_id || "step-" + ($$("#action-rows .action-row").length + 1)],
    ["summary", "Название", values.summary || ""],
    ["preconditions", "Требует", values.preconditions || ""],
    ["add_facts", "Добавляет", values.add_facts || ""],
    ["delete_facts", "Удаляет", values.delete_facts || ""],
  ];
  for (const [name, label, value] of fields) {
    const wrapper = node("div");
    wrapper.append(node("label", "", label));
    const input = node("input");
    input.dataset.field = name;
    input.value = value;
    input.maxLength = name === "summary" ? 256 : 512;
    wrapper.append(input);
    grid.append(wrapper);
  }
  const remove = node("button", "remove-action", "×");
  remove.type = "button";
  remove.setAttribute("aria-label", "Удалить шаг");
  remove.addEventListener("click", () => row.remove());
  row.append(grid, remove);
  $("#action-rows").append(row);
}

$("#action-rows").addEventListener("click", event => {
  const target = event.target;
  if (target instanceof HTMLElement && target.classList.contains("remove-action")) {
    target.closest(".action-row")?.remove();
  }
});
$("#add-action").addEventListener("click", () => addActionRow());

$("#reasoning-form").addEventListener("submit", async event => {
  event.preventDefault();
  const resultBox = $("#reasoning-result");
  resultBox.textContent = "Планирование…";
  resultBox.className = "reasoning-result";
  const actions = $$("#action-rows .action-row").map(row => {
    const values = {};
    for (const input of $$("[data-field]", row)) values[input.dataset.field] = input.value.trim();
    return {
      action_id: values.action_id,
      summary: values.summary,
      preconditions: splitFacts(values.preconditions),
      add_facts: splitFacts(values.add_facts),
      delete_facts: splitFacts(values.delete_facts),
    };
  });
  try {
    const result = await api("/api/reasoning/preview", {
      task_id: $("#reasoning-task").value.trim(),
      goal_description: $("#goal-description").value.trim(),
      initial_facts: splitFacts($("#initial-facts").value),
      goal_facts: splitFacts($("#goal-facts").value),
      actions,
    });
    resultBox.replaceChildren();
    resultBox.append(node("div", "result-status " + result.status, result.status.toUpperCase()));
    resultBox.append(node("p", "muted", "Посещено состояний: " + result.expanded_states));
    if (result.plan.length) {
      const list = node("ol", "plan-list");
      for (const step of result.plan) {
        const item = node("li");
        item.append(node("strong", "", step.summary));
        item.append(node("small", "muted", step.action_id + " · шаг " + step.index));
        item.append(node("small", "muted", "Факты после шага: " + step.facts_after_step.join(", ")));
        list.append(item);
      }
      resultBox.append(list);
    } else {
      resultBox.append(node("p", "muted", "План не найден в пределах заданных ограничений."));
    }
    resultBox.append(node("p", "notice-inline", result.notice));
    setNotice("Preview готов. Действия не исполнялись.", "success");
  } catch (error) {
    resultBox.textContent = error.message;
    resultBox.className = "reasoning-result error-text";
    setNotice(error.message, "error");
  }
});

async function enterConsole(token) {
  accessToken = token.trim();
  try {
    const health = await api("/api/health");
    if (health.api_schema !== "jarvis.local-console" ||
        health.api_version !== 2 || health.auth_required !== true ||
        health.auth_scheme !== "Bearer" || health.workspace_api_version !== 1 ||
        !["desktop", "android"].includes(health.platform)) {
      throw new Error("Версия локальной консоли не поддерживается.");
    }
    consolePlatform = health.platform;
    await refresh();
    sessionStorage.setItem("jarvis.console.access.v2", accessToken);
    unlockConsole();
    setNotice("Ядро подключено · " + health.storage.toUpperCase() + " · защищённый локальный режим", "success");
    if (refreshTimer !== null) window.clearInterval(refreshTimer);
    refreshTimer = window.setInterval(() => {
      refresh().catch(error => setNotice(error.message, "error"));
    }, 5000);
  } catch (error) {
    lockConsole(error.message || "Не удалось проверить токен доступа.");
  }
}

$("#auth-form").addEventListener("submit", event => {
  event.preventDefault();
  const value = $("#access-token").value.trim();
  if (!/^[A-Za-z0-9_-]{43}$/.test(value)) {
    setNotice("Токен должен состоять ровно из 43 символов Base64URL.", "error");
    return;
  }
  $("#access-token").value = "";
  enterConsole(value);
});

async function boot() {
  document.body.classList.add("auth-required");
  $("#auth-panel").hidden = false;
  try {
    const health = await api("/api/health");
    if (health.api_schema !== "jarvis.local-console" ||
        health.api_version !== 2 || health.auth_required !== true ||
        health.auth_scheme !== "Bearer" || health.workspace_api_version !== 1 ||
        !["desktop", "android"].includes(health.platform)) {
      throw new Error("Сервер не поддерживает защищённый API v2.");
    }
    if (accessToken) {
      await enterConsole(accessToken);
    } else {
      setNotice("Вставьте токен доступа, показанный при запуске консоли в Termux.", "info");
    }
  } catch (error) {
    lockConsole(error.message);
  }
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }
}

boot();
