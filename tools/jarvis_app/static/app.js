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

function setNavHealth(message, modelMessage, networkMessage, ready = false) {
  const core = $("#nav-core-state");
  const model = $("#nav-model-state");
  const network = $("#nav-network-state");
  const dot = $(".nav-health-dot");
  if (core) core.textContent = message;
  if (model) model.textContent = modelMessage;
  if (network) network.textContent = networkMessage;
  if (dot) dot.classList.toggle("ready", ready);
}

function initWorkspaceNavigation() {
  const links = $("[data-nav-target]");
  if (!links.length) return;
  const activate = id => {
    for (const link of links) {
      const active = link.dataset.navTarget === id;
      link.classList.toggle("active", active);
      if (active) link.setAttribute("aria-current", "location");
      else link.removeAttribute("aria-current");
    }
  };
  for (const link of links) {
    link.addEventListener("click", () => activate(link.dataset.navTarget));
  }
  if ("IntersectionObserver" in window) {
    const observer = new IntersectionObserver(entries => {
      const visible = entries
        .filter(entry => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0];
      if (visible) activate(visible.target.id);
    }, {rootMargin: "-18% 0px -68% 0px", threshold: [0, .15, .35, .6]});
    for (const link of links) {
      const target = document.getElementById(link.dataset.navTarget);
      if (target) observer.observe(target);
    }
  }
}

function initLocalUiPreferences() {
  const compact = $("#compact-ui-toggle");
  const motion = $("#motion-ui-toggle");
  if (!compact || !motion) return;
  let preferences = {};
  try {
    const stored = JSON.parse(localStorage.getItem("jarvis.ui.preferences.v1") || "{}");
    if (stored && typeof stored === "object" && !Array.isArray(stored)) preferences = stored;
  } catch { /* Use safe defaults when local preferences are unavailable. */ }
  compact.checked = preferences.compact === true;
  motion.checked = preferences.reducedMotion === true;
  document.body.classList.toggle("compact-ui", compact.checked);
  document.body.classList.toggle("reduced-motion", motion.checked);
  const save = () => {
    const next = {compact: compact.checked, reducedMotion: motion.checked};
    document.body.classList.toggle("compact-ui", next.compact);
    document.body.classList.toggle("reduced-motion", next.reducedMotion);
    try {
      localStorage.setItem("jarvis.ui.preferences.v1", JSON.stringify(next));
    } catch {
      setNotice("Настройка применена только до закрытия окна: локальное хранилище недоступно.", "info");
    }
  };
  compact.addEventListener("change", save);
  motion.addEventListener("change", save);
}

initWorkspaceNavigation();
initLocalUiPreferences();

const STATUS_LABELS = {
  open: "Открыта", paused: "Пауза", closed: "Закрыта",
  queued: "В очереди", running: "В работе", waiting: "Ожидает",
  completed: "Завершена", failed: "Ошибка", cancelled: "Отменена",
};
let currentState = null;
let currentConversation = null;
let conversationReady = false;
let conversationMode = "unavailable";

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
  if (backupSavedRevision !== null && backupSavedRevision !== result.revision) {
    backupSavedRevision = null;
    $("#backup-ack").checked = false;
    $("#backup-ack-row").hidden = true;
    pendingRestore = null;
    $("#backup-preview").hidden = true;
    $("#backup-restore-confirm").hidden = true;
    $("#backup-restore-cancel").hidden = true;
  }
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
    backupSavedRevision = null;
    $("#backup-ack").checked = false;
    $("#backup-ack-row").hidden = true;
    pendingRestore = null;
    $("#backup-preview").hidden = true;
    $("#backup-restore-confirm").hidden = true;
    $("#backup-restore-cancel").hidden = true;
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
const BACKUP_AAD = new TextEncoder().encode("JARVIS-WORKSPACE-BACKUP-ENCRYPTED-V1");
const TRANSFER_ITERATIONS = 310000;
const TRANSFER_MAX_PLAINTEXT = 16 * 1024 * 1024;
let pendingTransfer = null;
let pendingRestore = null;
let backupSavedRevision = null;

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
  const passwordBytes = new TextEncoder().encode(passphrase);
  try {
    const material = await crypto.subtle.importKey(
      "raw", passwordBytes, "PBKDF2", false, ["deriveKey"]
    );
    return await crypto.subtle.deriveKey(
      { name: "PBKDF2", hash: "SHA-256", salt, iterations: TRANSFER_ITERATIONS },
      material, { name: "AES-GCM", length: 256 }, false, ["encrypt", "decrypt"]
    );
  } finally {
    passwordBytes.fill(0);
  }
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
      document.export_id !== "workspace-current" ||
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

async function createWorkspaceBackup(document) {
  await verifyPortableWorkspace(document);
  const backup = {
    format: "jarvis.workspace.backup",
    version: { major: 1, minor: 0 },
    backup_id: crypto.randomUUID().replaceAll("-", ""),
    created_at: new Date().toISOString(),
    source_platform: consolePlatform,
    workspace: JSON.parse(JSON.stringify(document)),
  };
  const digest = await crypto.subtle.digest(
    "SHA-256", new TextEncoder().encode(canonicalJson(backup))
  );
  backup.integrity = {
    algorithm: "sha-256",
    digest: Array.from(new Uint8Array(digest), byte => byte.toString(16).padStart(2, "0")).join(""),
  };
  return backup;
}

async function verifyWorkspaceBackup(backup) {
  if (!backup || typeof backup !== "object" || Array.isArray(backup) ||
      Object.keys(backup).sort().join(",") !==
        "backup_id,created_at,format,integrity,source_platform,version,workspace" ||
      backup.format !== "jarvis.workspace.backup" ||
      !backup.version || Object.keys(backup.version).sort().join(",") !== "major,minor" ||
      backup.version.major !== 1 || backup.version.minor !== 0 ||
      typeof backup.backup_id !== "string" || !/^[a-f0-9]{32}$/.test(backup.backup_id) ||
      !isTransferDateTime(backup.created_at) ||
      !["desktop", "android"].includes(backup.source_platform) ||
      !backup.integrity || Object.keys(backup.integrity).sort().join(",") !== "algorithm,digest" ||
      backup.integrity.algorithm !== "sha-256" ||
      typeof backup.integrity.digest !== "string" || !/^[a-f0-9]{64}$/.test(backup.integrity.digest)) {
    throw new Error("Файл не соответствует схеме резервной копии JARVIS V1.");
  }
  await verifyPortableWorkspace(backup.workspace);
  const unsigned = JSON.parse(JSON.stringify(backup));
  delete unsigned.integrity;
  const digest = await crypto.subtle.digest(
    "SHA-256", new TextEncoder().encode(canonicalJson(unsigned))
  );
  const actual = Array.from(new Uint8Array(digest), byte =>
    byte.toString(16).padStart(2, "0")
  ).join("");
  if (actual !== backup.integrity.digest) {
    throw new Error("Контрольная сумма резервной копии не совпадает. Восстановление отменено.");
  }
  return backup;
}

async function encryptWorkspaceDocument(document, passphrase, purpose = "transfer") {
  const backupMode = purpose === "backup";
  const aad = backupMode ? BACKUP_AAD : TRANSFER_AAD;
  const format = backupMode ? "jarvis.workspace.backup.encrypted" : "jarvis.workspace.encrypted";
  const plaintext = new TextEncoder().encode(canonicalJson(document));
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const nonce = crypto.getRandomValues(new Uint8Array(12));
  let ciphertext = null;
  try {
    if (!plaintext.length || plaintext.length > TRANSFER_MAX_PLAINTEXT) {
      throw new Error("Рабочее пространство превышает лимит переносимого файла 16 МиБ.");
    }
    const key = await deriveTransferKey(passphrase, salt);
    ciphertext = new Uint8Array(await crypto.subtle.encrypt(
      { name: "AES-GCM", iv: nonce, additionalData: aad, tagLength: 128 },
      key, plaintext
    ));
    return {
      format,
      version: { major: 1, minor: 0 },
      kdf: { name: "PBKDF2-HMAC-SHA256", iterations: TRANSFER_ITERATIONS, salt: bytesToBase64(salt) },
      cipher: { name: "AES-256-GCM", nonce: bytesToBase64(nonce), tag_bits: 128 },
      ciphertext: bytesToBase64(ciphertext),
    };
  } finally {
    plaintext.fill(0);
    salt.fill(0);
    nonce.fill(0);
    ciphertext?.fill(0);
  }
}

async function decryptWorkspaceBundle(bundle, passphrase, purpose = "transfer") {
  const backupMode = purpose === "backup";
  const aad = backupMode ? BACKUP_AAD : TRANSFER_AAD;
  const expectedFormat = backupMode ? "jarvis.workspace.backup.encrypted" : "jarvis.workspace.encrypted";
  if (!bundle || typeof bundle !== "object" || Array.isArray(bundle) ||
      Object.keys(bundle).sort().join(",") !== "cipher,ciphertext,format,kdf,version" ||
      bundle.format !== expectedFormat ||
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
  let nonce = null;
  let ciphertext = null;
  let plaintext = null;
  try {
    nonce = base64ToBytes(bundle.cipher.nonce, 12);
    if (salt.length !== 16 || nonce.length !== 12) {
      throw new Error("Параметры шифрования имеют неверную длину.");
    }
    ciphertext = base64ToBytes(bundle.ciphertext, TRANSFER_MAX_PLAINTEXT + 16);
    if (ciphertext.length < 17) throw new Error("Зашифрованные данные слишком короткие.");
    const key = await deriveTransferKey(passphrase, salt);
    try {
      plaintext = new Uint8Array(await crypto.subtle.decrypt(
        { name: "AES-GCM", iv: nonce, additionalData: aad, tagLength: 128 },
        key, ciphertext
      ));
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
    return backupMode ? verifyWorkspaceBackup(document) : verifyPortableWorkspace(document);
  } finally {
    salt.fill(0);
    nonce?.fill(0);
    ciphertext?.fill(0);
    plaintext?.fill(0);
  }
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
  $("#transfer-cancel").hidden = false;
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
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
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
  $("#transfer-cancel").hidden = true;
  $("#transfer-preview").hidden = true;
  pendingTransfer = null;
  if (!file) {
    passwordInput.value = "";
    return;
  }
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

$("#transfer-cancel").addEventListener("click", () => {
  pendingTransfer = null;
  $("#transfer-preview").hidden = true;
  $("#transfer-confirm").hidden = true;
  $("#transfer-cancel").hidden = true;
  setNotice("Импорт отменён. Локальная память не изменена.", "info");
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
    $("#transfer-cancel").hidden = true;
    setNotice("Перенос завершён: добавлено " + finalPlan.summary.added +
      ", совпадений " + finalPlan.summary.identical +
      ", конфликтов без перезаписи " + finalPlan.summary.conflicts + ".", "success");
  } catch (error) {
    if (error.status === 409) {
      pendingTransfer = null;
      $("#transfer-preview").hidden = true;
      $("#transfer-confirm").hidden = true;
      $("#transfer-cancel").hidden = true;
      setNotice("Локальная память изменилась после предпросмотра. Обновите память и выберите файл заново.", "error");
    } else {
      setNotice(error.message, "error");
    }
  } finally {
    button.disabled = false;
  }
});


$("#backup-export").addEventListener("click", async event => {
  const button = event.currentTarget;
  const passwordInput = $("#transfer-passphrase");
  const passphrase = passwordInput.value;
  if (passphrase.length < 12) {
    setNotice("Для резервной копии нужна парольная фраза длиной не менее 12 символов.", "error");
    return;
  }
  button.disabled = true;
  try {
    const workspace = await refreshWorkspace();
    const backup = await createWorkspaceBackup(workspace.document);
    const bundle = await encryptWorkspaceDocument(backup, passphrase, "backup");
    const blob = new Blob([JSON.stringify(bundle)], { type: "application/vnd.jarvis.workspace-backup+json" });
    const url = URL.createObjectURL(blob);
    const anchor = node("a");
    anchor.href = url;
    anchor.download = "jarvis-workspace-backup-" + new Date().toISOString().slice(0, 10) + ".jwb";
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    backupSavedRevision = workspace.revision;
    pendingRestore = null;
    $("#backup-ack").checked = false;
    $("#backup-ack-row").hidden = false;
    $("#backup-preview").hidden = true;
    $("#backup-restore-confirm").hidden = true;
    $("#backup-restore-cancel").hidden = true;
    setNotice("Экспорт .jwb инициирован для REV " + workspace.revision +
      ". Убедитесь, что файл действительно сохранён, затем подтвердите резервную копию ниже.", "success");
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    passwordInput.value = "";
    button.disabled = false;
  }
});

function updateRestoreConfirmation() {
  const valid = !!pendingRestore && backupSavedRevision !== null &&
    currentWorkspace && backupSavedRevision === currentWorkspace.revision &&
    pendingRestore.expectedRevision === currentWorkspace.revision &&
    $("#backup-ack").checked;
  $("#backup-restore-confirm").disabled = !valid;
}

$("#backup-ack").addEventListener("change", updateRestoreConfirmation);

$("#backup-restore-choose").addEventListener("click", () => {
  if (backupSavedRevision === null || !currentWorkspace ||
      backupSavedRevision !== currentWorkspace.revision) {
    setNotice("Сначала создайте и сохраните backup текущей ревизии .jwb.", "error");
    return;
  }
  $("#backup-file").click();
});

$("#backup-file").addEventListener("change", async event => {
  const file = event.target.files && event.target.files[0];
  const passwordInput = $("#transfer-passphrase");
  const passphrase = passwordInput.value;
  pendingRestore = null;
  $("#backup-preview").hidden = true;
  $("#backup-restore-confirm").hidden = true;
  $("#backup-restore-cancel").hidden = true;
  updateRestoreConfirmation();
  if (!file) {
    passwordInput.value = "";
    return;
  }
  try {
    if (file.size > 24 * 1024 * 1024) {
      throw new Error("Файл backup превышает допустимый размер 24 МиБ.");
    }
    if (passphrase.length < 12) {
      throw new Error("Для восстановления введите парольную фразу длиной не менее 12 символов.");
    }
    const bundle = JSON.parse(await file.text());
    const backup = await decryptWorkspaceBundle(bundle, passphrase, "backup");
    const local = currentWorkspace || await refreshWorkspace();
    if (backup.workspace.workspace_id !== local.document.workspace_id) {
      throw new Error("Backup относится к другому workspace_id. Замена запрещена.");
    }
    if (backupSavedRevision !== local.revision) {
      throw new Error("После создания backup изменилась текущая ревизия. Сохраните новый backup.");
    }
    const records = backup.workspace.records;
    pendingRestore = { workspace: backup.workspace, expectedRevision: local.revision };
    $("#backup-preview").hidden = false;
    $("#backup-preview").textContent =
      "ПРОВЕРЕННАЯ КОПИЯ от " + backup.created_at +
      " · диалогов: " + records.conversations.length +
      " · воспоминаний: " + records.memories.length +
      " · сводок задач: " + records.tasks.length +
      " · настроек: " + records.settings.length +
      ". Подтверждение ЗАМЕНИТ все четыре коллекции текущей памяти.";
    $("#backup-restore-confirm").hidden = false;
    $("#backup-restore-cancel").hidden = false;
    updateRestoreConfirmation();
    setNotice("Backup расшифрован и проверен. Данные ещё не изменены.", "success");
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    passwordInput.value = "";
    event.target.value = "";
  }
});

$("#backup-restore-cancel").addEventListener("click", () => {
  pendingRestore = null;
  $("#backup-preview").hidden = true;
  $("#backup-restore-confirm").hidden = true;
  $("#backup-restore-cancel").hidden = true;
  $("#backup-ack").checked = false;
  updateRestoreConfirmation();
  setNotice("Восстановление отменено. Локальная память не изменена.", "info");
});

$("#backup-restore-confirm").addEventListener("click", async event => {
  const button = event.currentTarget;
  if (!pendingRestore) return;
  updateRestoreConfirmation();
  if (button.disabled) {
    setNotice("Для восстановления нужен сохранённый backup текущей ревизии и явное подтверждение.", "error");
    return;
  }
  if (!window.confirm("ВНИМАНИЕ: вся текущая общая память будет ЗАМЕНЕНА выбранной резервной копией. Продолжить?")) {
    return;
  }
  button.disabled = true;
  const plan = pendingRestore;
  try {
    const restored = JSON.parse(JSON.stringify(plan.workspace));
    restored.exported_at = new Date().toISOString();
    restored.source_platform = consolePlatform;
    await sealWorkspace(restored);
    const result = await api("/api/workspace/restore", {
      expected_revision: plan.expectedRevision,
      workspace: restored,
    });
    currentWorkspace = { revision: result.revision, document: result.workspace };
    renderMemories();
    pendingRestore = null;
    backupSavedRevision = null;
    $("#backup-ack").checked = false;
    $("#backup-ack-row").hidden = true;
    $("#backup-preview").hidden = true;
    $("#backup-restore-confirm").hidden = true;
    $("#backup-restore-cancel").hidden = true;
    setNotice("Восстановление завершено. Текущая память заменена; сохраните backup до проверки результата.", "success");
  } catch (error) {
    if (error.status === 409) {
      pendingRestore = null;
      backupSavedRevision = null;
      $("#backup-ack").checked = false;
      $("#backup-ack-row").hidden = true;
      $("#backup-preview").hidden = true;
      $("#backup-restore-confirm").hidden = true;
      $("#backup-restore-cancel").hidden = true;
      try { await refreshWorkspace(); } catch { /* Preserve the original conflict report. */ }
      setNotice("Ревизия изменилась. Preview отменён. Обновите память и создайте новый backup перед повторной попыткой.", "error");
    } else {
      try {
        await refreshWorkspace();
        if (canonicalJson(currentWorkspace.document.records) === canonicalJson(plan.workspace.records)) {
          pendingRestore = null;
          backupSavedRevision = null;
          $("#backup-ack").checked = false;
          $("#backup-ack-row").hidden = true;
          $("#backup-preview").hidden = true;
          $("#backup-restore-confirm").hidden = true;
          $("#backup-restore-cancel").hidden = true;
          setNotice("Сервер подтвердил восстановленные данные после неоднозначного ответа. Проверьте память и сохраните новый backup.", "success");
        } else {
          pendingRestore = null;
          backupSavedRevision = null;
          $("#backup-ack").checked = false;
          $("#backup-ack-row").hidden = true;
          $("#backup-preview").hidden = true;
          $("#backup-restore-confirm").hidden = true;
          $("#backup-restore-cancel").hidden = true;
          setNotice("Не удалось подтвердить результат восстановления: " + error.message +
            ". Предпросмотр аннулирован. Обновите память и проверьте результат перед новой попыткой.", "error");
        }
      } catch {
        pendingRestore = null;
        backupSavedRevision = null;
        $("#backup-ack").checked = false;
        $("#backup-ack-row").hidden = true;
        $("#backup-preview").hidden = true;
        $("#backup-restore-confirm").hidden = true;
        $("#backup-restore-cancel").hidden = true;
        setNotice("Результат восстановления неизвестен: " + error.message +
          ". Предпросмотр аннулирован. Не повторяйте операцию вслепую; сначала восстановите соединение и проверьте память.", "error");
      }
    }
  } finally {
    button.disabled = false;
  }
});

function renderConversation(state) {
  currentConversation = state.conversation || null;
  conversationMode = state.chat_mode || "unavailable";
  $("#chat-backend").textContent = state.backend_label || "Локальная модель не настроена.";
  const modelStatus = state.chat_available
    ? "Диалог: " + (state.backend_label || state.chat_mode || "доступен")
    : "Диалог: runtime модели недоступен";
  setNavHealth(
    $("#nav-core-state")?.textContent || "Ядро: подключено",
    modelStatus,
    $("#nav-network-state")?.textContent || "ANS-100: проверка",
    true
  );
  const button = $("#chat-send");
  conversationReady = Boolean(state.chat_available ?? (state.model_configured && state.runtime_available));
  button.disabled = !conversationReady;
  button.textContent = conversationMode === "fallback"
    ? "Отправить JARVIS"
    : "Отправить локальной модели";
  const root = $("#chat-messages");
  root.replaceChildren();
  const messages = currentConversation?.messages || [];
  root.classList.toggle("empty", messages.length === 0);
  if (!messages.length) {
    if (conversationMode === "fallback") {
      root.textContent = "Базовый офлайн-режим активен. Попробуйте /help или /status. Для свободного диалога можно подключить локальную модель.";
    } else if (button.disabled) {
      root.textContent = "Модель указана, но runtime LiteRT-LM недоступен. Установите litert-lm-api==0.17.1 и проверьте файл модели.";
    } else {
      root.textContent = "История локального диалога пока пуста.";
    }
    return;
  }
  for (const message of messages.slice(-30)) {
    const item = node("article", "chat-message " + message.role);
    item.append(node("strong", "", message.role === "user" ? "Вы" : "JARVIS"));
    item.append(node("p", "", message.content));
    root.append(item);
  }
  root.scrollTop = root.scrollHeight;
}

async function refreshConversation() {
  const state = await api("/api/conversation");
  renderConversation(state);
  return state;
}

function renderDialogTurnTrace(trace) {
  const root = $("#chat-neuron-trace");
  if (!trace || typeof trace !== "object") {
    root.textContent = "Диалоговая нейронная сводка для последнего хода недоступна.";
    return;
  }
  const count = Number(trace.dialog_neuron_count || 0).toLocaleString("ru-RU");
  const active = Number(trace.dialog_neuron_active_features || 0).toLocaleString("ru-RU");
  const trained = Number(trace.dialog_neuron_trained_examples || 0).toLocaleString("ru-RU");
  const label = trace.dialog_neuron_predicted_label || "нет уверенной метки";
  const classifier = trace.dialog_neuron_context_invoked
    ? "10 000-признаковый слой: " + count + " адресуемых нейронов · активно признаков: " + active +
      " · метка: " + label + " · обучающих примеров: " + trained
    : "Диалоговый классификатор не смог обработать этот ход.";
  const signal = trace.ans100_signal_invoked
    ? "ANS-100: сигнальный проход выполнен · attention gate: " +
      (trace.ans100_attention_gate_active ? "активен" : "не активен")
    : "ANS-100: сигнальный проход недоступен для этого хода.";
  root.textContent = classifier + ". " + signal + ". Классификация признаков не является пониманием смысла.";
}

async function refreshAns100Status() {
  const root = $("#ans100-desktop-status");
  try {
    const status = await api("/api/ans100");
    const topology = status.desktop_topology || {};
    const count = Number(topology.neuron_count || 0).toLocaleString("ru-RU");
    const edges = Number(topology.declared_connection_count || 0).toLocaleString("ru-RU");
    const populations = Array.isArray(topology.populations) ? topology.populations.length : 0;
    const active = status.active_in_chat === true;
    root.textContent =
      count + " нейронов · " + edges + " заявленных связей · " + populations +
      " популяций · версия " + String(topology.version || "—") + ". " +
      (active
        ? "Топология подключена к активному диалогу."
        : "Топология описана в исходном коде, но пока не запущена и не подключена к диалогу.") +
      " Текущий диалоговый сигнальный путь остаётся отдельным.";
    setNavHealth(
      $("#nav-core-state")?.textContent || "Ядро: подключено",
      $("#nav-model-state")?.textContent || "Диалог: проверка",
      "ANS-100 V4: " + count + " · не активна в чате",
      true
    );
    return status;
  } catch (error) {
    root.textContent = "Состояние desktop ANS-100 недоступно: " + error.message;
    return null;
  }
}

async function refreshDialogNeurons() {
  const root = $("#dialog-neuron-status");
  try {
    const [legacy, rstdp] = await Promise.all([
      api("/api/dialog-neurons"),
      api("/api/dialog-rstdp"),
    ]);
    const legacyCount = Number(legacy.neuron_count || 0).toLocaleString("ru-RU");
    const training = Object.entries(rstdp.trained_examples_per_label || {})
      .map(([label, count]) => label + ": " + Number(count).toLocaleString("ru-RU"))
      .join(" · ");
    root.textContent =
      "Старый разреженный классификатор: " + legacyCount + " признаковых слотов (отдельно от спайковой сети). " +
      "R-STDP: " + Number(rstdp.output_spiking_neurons || 0) + " выходных LIF-DT нейрона · " +
      Number(rstdp.input_feature_neurons || 0) + " признаковых синапсов на выход · обучений: " +
      (training || "пока нет") + ". Текст примеров не сохраняется.";
    setNavHealth(
      $("#nav-core-state")?.textContent || "Ядро: подключено",
      $("#nav-model-state")?.textContent || "Диалог: проверка",
      "R-STDP: " + Number(rstdp.output_spiking_neurons || 0) + " LIF-DT · " +
        Number(rstdp.input_feature_neurons || 0) + " входов на нейрон",
      true
    );
    return rstdp;
  } catch (error) {
    root.textContent = "Состояние диалогового R-STDP недоступно: " + error.message;
    return null;
  }
}

$("#dialog-neuron-form").addEventListener("submit", async event => {
  event.preventDefault();
  const textValue = $("#dialog-neuron-sample").value.trim();
  const expectedLabel = $("#dialog-neuron-label").value;
  const button = $("#dialog-neuron-train");
  if (!textValue) return;
  button.disabled = true;
  try {
    const feedbackId = crypto.randomUUID();
    const result = await api("/api/dialog-rstdp/learn", {
      text: textValue,
      expected_label: expectedLabel,
      feedback_id: feedbackId,
    });
    $("#dialog-neuron-sample").value = "";
    await refreshDialogNeurons();
    const before = Number(result.scores_before?.[expectedLabel] || 0).toFixed(6);
    const after = Number(result.scores_after?.[expectedLabel] || 0).toFixed(6);
    const predicted = result.predicted_label || "пока нет уверенной метки";
    setNotice(
      "R-STDP применил явную метку. Счёт целевого класса: " + before + " → " + after +
      "; прогноз: " + predicted + ". Сохранён feedback ID " + result.feedback_id +
      ". Исходный текст не записывался.",
      "success"
    );
  } catch (error) {
    setNotice(error.message, "error");
  } finally {
    button.disabled = false;
  }
});

$("#chat-form").addEventListener("submit", async event => {
  event.preventDefault();
  const input = $("#chat-input");
  const button = $("#chat-send");
  const text = input.value.trim();
  if (!text || button.disabled) return;
  button.disabled = true;
  button.textContent = "Модель отвечает…";
  try {
    if (await routeBrowserCommand(text)) {
      input.value = "";
      return;
    }
    const before = await api("/api/conversation");
    const result = await api("/api/conversation", {text, expected_revision: before.revision});
    input.value = "";
    renderConversation(result);
    renderDialogTurnTrace(result.turn_trace);
    setNotice("Ответ JARVIS сохранён в локальном workspace.", "success");
  } catch (error) {
    setNotice(error.message, "error");
    if (error.status === 409) {
      try { await refreshConversation(); } catch { /* Preserve the revision conflict. */ }
    }
  } finally {
    button.disabled = !conversationReady;
    button.textContent = conversationMode === "fallback"
      ? "Отправить JARVIS"
      : "Отправить локальной модели";
  }
});
let browserState = null;

function publicBrowserCommandUrl(rawTarget) {
  const target = rawTarget.trim().replace(/[.,!?]+$/u, "");
  const knownSites = {
    youtube: "https://www.youtube.com/",
    google: "https://www.google.com/",
    bing: "https://www.bing.com/",
    github: "https://github.com/",
    wikipedia: "https://www.wikipedia.org/",
    minecraft: "https://www.minecraft.net/",
  };
  let raw = knownSites[target.toLocaleLowerCase()] || target;
  if (!/^[a-z][a-z0-9+.-]*:/iu.test(raw)) raw = "https://" + raw;
  let parsed;
  try { parsed = new URL(raw); }
  catch { throw new Error("Не удалось распознать публичный адрес сайта."); }
  const host = parsed.hostname.toLowerCase();
  const localHost = host === "localhost" || host.endsWith(".localhost") ||
    host.endsWith(".local") || host.endsWith(".internal") ||
    host === "0.0.0.0" || host === "[::1]" || host.endsWith(".test") || host.endsWith(".invalid");
  const ipv4 = /^\d{1,3}(?:\.\d{1,3}){3}$/u.test(host);
  if (!["http:", "https:"].includes(parsed.protocol) || !host.includes(".") ||
      parsed.username || parsed.password || localHost || ipv4) {
    throw new Error("Разрешены только публичные доменные адреса HTTP/HTTPS без IP-адресов, логинов и локальных хостов.");
  }
  return parsed.href;
}

async function routeBrowserCommand(rawText) {
  const command = rawText.trim();
  const normalBrowserSearch = command.match(/^(?:найди|поищи)\s+(?:в\s+)?(?:моём\s+)?(?:обычном\s+)?браузере\s+(.+)$/iu);
  const normalBrowserOpen = command.match(/^(?:открой|перейди)\s+(?:в\s+)?(?:моём\s+)?(?:обычном\s+)?браузере\s+(.+)$/iu);
  if (normalBrowserSearch) {
    const query = normalBrowserSearch[1].trim();
    if (!query || query.length > 500) throw new Error("Поисковый запрос должен содержать не более 500 символов.");
    window.open("https://www.bing.com/search?q=" + encodeURIComponent(query), "_blank", "noopener,noreferrer");
    setNotice("Поиск передан обычному браузеру. Если вкладка не появилась, разрешите всплывающие окна для JARVIS.", "success");
    return true;
  }
  if (normalBrowserOpen) {
    const url = publicBrowserCommandUrl(normalBrowserOpen[1]);
    window.open(url, "_blank", "noopener,noreferrer");
    setNotice("Сайт передан обычному браузеру. Если вкладка не появилась, разрешите всплывающие окна для JARVIS.", "success");
    return true;
  }
  const searchMatch = command.match(/^(?:открой(?:\s+браузер)?\s+и\s+)?(?:найди|поищи|поиск)\s+(?:в\s+интернете\s+)?(.+)$/iu);
  const openMatch = command.match(/^(?:открой|перейди\s+на)\s+(?:сайт\s+)?(.+)$/iu);
  const readMatch = command.match(/^(?:прочитай|прочти)\s+(?:открытую\s+)?страницу$/iu);
  const launchMatch = command.match(/^(?:открой|запусти)\s+браузер$/iu);
  if (!searchMatch && !openMatch && !readMatch && !launchMatch) return false;

  if (browserState?.paused) {
    throw new Error("Сначала верните управление браузером JARVIS.");
  }

  const panel = document.querySelector(".browser-panel");
  const reveal = () => panel?.scrollIntoView({behavior: "smooth", block: "start"});

  if (searchMatch) {
    const query = searchMatch[1].trim();
    if (!query || query.length > 500) throw new Error("Поисковый запрос должен содержать не более 500 символов.");
    $("#browser-query").value = query;
    $("#browser-status").textContent = "Команда из чата: поиск в видимом Chromium…";
    const result = await api("/api/browser/search", {query});
    renderBrowserState(result);
    renderBrowserSources(result.sources);
    $("#browser-page-text").textContent = "Поисковая выдача открыта в отдельном окне браузера. Выберите источник ниже.";
    $("#browser-page-text").classList.add("empty");
    setNotice("JARVIS выполнил команду поиска в видимом браузере.", "success");
    reveal();
    return true;
  }

  if (launchMatch) {
    const result = await api("/api/browser/open", {url: "https://www.bing.com"});
    renderBrowserState(result);
    $("#browser-page-text").textContent = "Браузер открыт. Напишите «Найди в интернете …», чтобы выполнить поиск.";
    $("#browser-page-text").classList.add("empty");
    setNotice("Видимый браузер открыт по команде.", "success");
    reveal();
    return true;
  }

  if (openMatch) {
    const target = openMatch[1].trim().replace(/[.,!?]+$/u, "");
    const knownSites = {
      youtube: "https://www.youtube.com/",
      google: "https://www.google.com/",
      bing: "https://www.bing.com/",
      github: "https://github.com/",
      wikipedia: "https://www.wikipedia.org/",
      minecraft: "https://www.minecraft.net/",
    };
    const url = knownSites[target.toLocaleLowerCase()] ||
      (/^https?:\/\//iu.test(target) ? target :
        /^(?:www\.)?[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:\/[^\s]*)?$/iu.test(target)
          ? "https://" + target : null);
    if (!url) {
      throw new Error("Укажите адрес сайта или известное имя: YouTube, Google, GitHub, Wikipedia.");
    }
    const result = await api("/api/browser/open", {url});
    renderBrowserState(result);
    $("#browser-page-text").textContent = "Страница открыта. Напишите «Прочитай страницу», чтобы JARVIS извлёк её текст.";
    $("#browser-page-text").classList.add("empty");
    setNotice("JARVIS открыл сайт в видимом браузере.", "success");
    reveal();
    return true;
  }

  const result = await api("/api/browser/read", {});
  $("#browser-page-meta").textContent = result.title + " · " + result.url +
    (result.truncated ? " · текст ограничен лимитом" : "");
  $("#browser-page-text").textContent = result.text;
  $("#browser-page-text").classList.remove("empty");
  setNotice("JARVIS прочитал открытую страницу. Её содержимое считается недоверенным.", "success");
  reveal();
  return true;
}

function renderBrowserState(state) {
  browserState = state;
  const status = $("#browser-status");
  const supported = !state || state.supported !== false;
  const open = Boolean(state && state.browser_open);
  const paused = Boolean(state && state.paused);
  const page = state && state.page;
  if (!supported) {
    status.textContent = "Видимый Chromium доступен только в режиме desktop на ПК. Android продолжает работать без этого расширения.";
  } else if (!state || !open) {
    status.textContent = "Видимый браузер закрыт. Поиск откроет отдельное окно Chromium.";
  } else if (paused) {
    status.textContent = "Управление передано вам. JARVIS не читает и не меняет открытую страницу.";
  } else if (page) {
    status.textContent = "Браузер открыт: " + (page.title || "страница без заголовка") + " · " + page.url;
  } else {
    status.textContent = "Видимый браузер открыт.";
  }
  $("#browser-query").disabled = !supported || paused;
  $("#browser-search").disabled = !supported || paused;
  $("#browser-read").disabled = !supported || !open || paused;
  $("#browser-ask").disabled = !supported || !open || paused;
  $("#browser-takeover").disabled = !supported || !open;
  $("#browser-takeover").textContent = paused ? "Вернуть управление JARVIS" : "Забрать управление";
  $("#browser-stop").disabled = !supported || !open;
  if (page) {
    $("#browser-page-meta").textContent = page.title + " · " + page.url;
  } else if (!open) {
    $("#browser-page-meta").textContent = "Открытая страница не выбрана.";
  }
}

async function refreshBrowserState() {
  try {
    const state = await api("/api/browser");
    renderBrowserState(state);
    return state;
  } catch (error) {
    if (error.status === 401) throw error;
    $("#browser-status").textContent = error.message;
    return null;
  }
}

function renderBrowserSources(sources) {
  const root = $("#browser-sources");
  root.replaceChildren();
  root.classList.toggle("empty", !sources || sources.length === 0);
  if (!sources || sources.length === 0) {
    root.textContent = "Источники не найдены. Попробуйте уточнить запрос.";
    return;
  }
  for (const source of sources) {
    const card = node("article", "browser-source");
    const title = node("strong", "", source.title);
    const link = node("small", "muted", source.url);
    const open = node("button", "secondary-button", "Открыть в видимом браузере");
    open.type = "button";
    open.addEventListener("click", async () => {
      open.disabled = true;
      try {
        const state = await api("/api/browser/open", {url: source.url});
        renderBrowserState(state);
        $("#browser-page-text").textContent = "Страница открыта. Нажмите «Прочитать открытую страницу», чтобы передать её текст JARVIS.";
        $("#browser-page-text").classList.add("empty");
        setNotice("Страница открыта в видимом браузере. Вы можете прочитать её сами или запросить чтение JARVIS.", "success");
      } catch (error) {
        setNotice(error.message, "error");
      } finally {
        open.disabled = false;
      }
    });
    card.append(title, link, open);
    root.append(card);
  }
}

$("#browser-search-form").addEventListener("submit", async event => {
  event.preventDefault();
  const query = $("#browser-query").value.trim();
  if (!query || browserState?.paused) return;
  const button = $("#browser-search");
  button.disabled = true;
  button.textContent = "Ищу…";
  $("#browser-sources").textContent = "Поиск выполняется в видимом окне Chromium…";
  try {
    const result = await api("/api/browser/search", {query});
    renderBrowserState(result);
    renderBrowserSources(result.sources);
    $("#browser-page-text").textContent = "Поисковая выдача открыта в окне браузера. Выберите источник, чтобы открыть страницу.";
    $("#browser-page-text").classList.add("empty");
    setNotice("Поиск завершён. Найденные ссылки показаны в панели JARVIS.", "success");
  } catch (error) {
    $("#browser-sources").textContent = error.message;
    setNotice(error.message, "error");
  } finally {
    button.textContent = "Искать";
    button.disabled = Boolean(browserState?.paused);
  }
});

$("#browser-read").addEventListener("click", async () => {
  const button = $("#browser-read");
  button.disabled = true;
  $("#browser-page-text").textContent = "Чтение открытой страницы…";
  $("#browser-page-text").classList.remove("empty");
  try {
    const result = await api("/api/browser/read", {});
    $("#browser-page-meta").textContent =
      result.title + " · " + result.url + " · " + result.retrieved_at +
      (result.truncated ? " · текст ограничен лимитом" : "");
    $("#browser-page-text").textContent = result.text;
    setNotice("Текст страницы прочитан. Содержимое сайта считается недоверенными данными.", "success");
  } catch (error) {
    $("#browser-page-text").textContent = error.message;
    setNotice(error.message, "error");
  } finally {
    await refreshBrowserState();
  }
});

$("#browser-takeover").addEventListener("click", async () => {
  if (!browserState?.browser_open) return;
  const paused = !browserState.paused;
  try {
    const state = await api("/api/browser/control", {paused});
    renderBrowserState(state);
    setNotice(
      paused
        ? "Управление передано вам. JARVIS приостановил навигацию и чтение."
        : "Управление возвращено JARVIS.",
      "success",
    );
  } catch (error) {
    setNotice(error.message, "error");
  }
});

$("#browser-stop").addEventListener("click", async () => {
  const button = $("#browser-stop");
  button.disabled = true;
  try {
    const state = await api("/api/browser/stop", {});
    renderBrowserState(state);
    $("#browser-sources").replaceChildren();
    $("#browser-sources").textContent = "Браузер закрыт. Поиск можно начать заново.";
    $("#browser-page-text").textContent = "Прочитанный текст страницы появится здесь.";
    $("#browser-page-text").classList.add("empty");
    $("#browser-page-meta").textContent = "Открытая страница не выбрана.";
    setNotice("Видимый браузер закрыт.", "success");
  } catch (error) {
    setNotice(error.message, "error");
    await refreshBrowserState();
  }
});

$("#browser-refresh").addEventListener("click", async () => {
  const state = await refreshBrowserState();
  if (state) setNotice("Состояние видимого браузера обновлено.", "success");
});

$("#browser-ask").addEventListener("click", async () => {
  const question = $("#browser-question").value.trim();
  const button = $("#browser-ask");
  if (!question || button.disabled || !browserState?.browser_open || browserState.paused) return;
  button.disabled = true;
  button.textContent = "JARVIS анализирует страницу…";
  try {
    const before = await api("/api/conversation");
    const result = await api("/api/browser/ask", {
      question,
      expected_revision: before.revision,
    });
    renderConversation(result);
    $("#browser-question").value = "";
    const source = result.browser_source;
    if (source) {
      $("#browser-page-meta").textContent =
        "Источник ответа: " + source.title + " · " + source.url +
        (source.retrieved_at ? " · " + source.retrieved_at : "");
    }
    setNotice("Ответ JARVIS сохранён в диалоге вместе со ссылкой на страницу.", "success");
  } catch (error) {
    setNotice(error.message, "error");
    if (error.status === 409) {
      try { await refreshConversation(); } catch { /* Preserve the revision conflict. */ }
    }
  } finally {
    button.textContent = "Спросить JARVIS";
    await refreshBrowserState();
  }
});

async function refresh() {
  const state = await api("/api/state");
  renderState(state);
  await refreshWorkspace();
  await refreshConversation();
  await refreshDialogNeurons();
  await refreshAns100Status();
  await refreshBrowserState();
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
    $("#settings-platform").textContent = health.platform === "desktop" ? "Windows / PC (режим desktop)" : "Android";
    setNavHealth("Ядро: онлайн · " + health.platform, "Диалог: проверка", "ANS-100: проверка", true);
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
