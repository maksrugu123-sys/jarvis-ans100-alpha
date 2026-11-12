const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

async function api(path, body) {
  const response = await fetch(path, body === undefined ? {
    headers: { "Accept": "application/json" },
    cache: "no-store",
  } : {
    method: "POST",
    headers: { "Content-Type": "application/json", "Accept": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });
  let payload;
  try { payload = await response.json(); }
  catch { throw new Error("Сервер вернул некорректный ответ."); }
  if (!response.ok) throw new Error(payload.error || "Локальный запрос не выполнен.");
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
async function refresh() {
  const state = await api("/api/state");
  renderState(state);
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

async function boot() {
  try {
    const health = await api("/api/health");
    await refresh();
    setNotice("Ядро подключено · " + health.storage.toUpperCase() + " · режим только для локальных задач", "success");
  } catch (error) {
    setNotice(error.message, "error");
  }
  if ("serviceWorker" in navigator) {
    navigator.serviceWorker.register("/sw.js").catch(() => {});
  }
  window.setInterval(() => refresh().catch(error => setNotice(error.message, "error")), 5000);
}

boot();
