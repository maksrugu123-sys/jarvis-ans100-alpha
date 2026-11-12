# JARVIS Local Console V1

**Статус:** реализована loopback-only PWA-консоль на Python HTTP server + HTML/CSS/JavaScript. Функциональные проверки локального console API добавлены в `tools/jarvis_app/test_server.py` (5 test methods), AUTHORED, NOT RUN. Это не нативный Android APK и не исполнитель произвольных действий.

## Запуск на Android / Termux

Из корня репозитория:

```sh
python3 -m tools.jarvis_app --port 8765
```

Открыть на этом же устройстве:

```text
http://127.0.0.1:8765/
```

При желании браузер может добавить PWA на главный экран. Сервер привязан только к loopback `127.0.0.1`; он не принимает соединения из локальной сети или интернета. JPVR-файл по умолчанию находится в `~/.jarvis-ans100-alpha/local-console.jpvr`; путь можно переопределить аргументом `--data`. Остановка: Ctrl+C в Termux-сессии.

## Возможности V1

- Создание сессий и задач; TaskSessionManager snapshot после каждой мутации сохраняется через dedicated JPVRTaskSessionCheckpointStore.
- Пауза и продолжение сессии с обязательной expected-revision проверкой.
- Закрытие сессии только когда все связанные задачи terminal; закрытая сессия не может быть возобновлена.
- Отмена только QUEUED задач с expected-revision проверкой.
- Поиск задач по названию/ID, фильтр по статусу, просмотр количества задач в работе и раскрываемых result records.
- История последних 100 lifecycle events.
- Structured Reasoning Engine preview по пользовательским фактам и действиям; preview не исполняет actions и явно сообщает, что это только план.
- PWA shell cache для HTML/CSS/JS; API всегда запрашивается у локального процесса и не кэшируется.

## API contract

- `GET /api/health` — локальный статус сервиса и вид хранилища.
- `GET /api/state` — сессии, задачи, последние lifecycle events, JPVR checkpoint version и capability flags.
- `POST /api/sessions` — `{"title":"..." }`.
- `POST /api/tasks` — `{"session_id":"...","title":"..." }`.
- `POST /api/sessions/{session_id}/status` — `{"status":"paused|open|closed","expected_revision":N }`.
- `POST /api/tasks/{task_id}/cancel` — `{"expected_revision":N }`.
- `POST /api/reasoning/preview` — bounded structured ReasoningProblem payload.

POST routes require JSON objects with exact documented fields. All mutating state operations are serialized under the service lock, applied to a detached candidate manager, and published through JPVR compare-and-swap before the in-memory manager reference is replaced. A failed save does not publish the candidate state.

## Safety and trust boundary

The console explicitly reports `external_actions=false`, `tool_execution=false`, and `remote_access=false`. It exposes no shell, filesystem browsing, browser automation, credentials, network connector, approval provider, or ActionExecutor. The local console does not run JARVIS Task Loop; it only creates lifecycle records and previews structured plans.

The server checks the exact Host header and rejects a foreign Origin. Bind only to `127.0.0.1`; do not change to `0.0.0.0` without a separately designed authentication and network security boundary. The loopback check is not an identity/authentication system and assumes the local device/process environment is trusted.

The PWA may be installed to a home screen by a compatible mobile browser, but this repository contains no Gradle project, AndroidManifest, Kotlin or Java Android application module. A native APK, Android lifecycle integration, OS-level permissions and verified approval UI remain unimplemented.

## Verification

```sh
python3 -m unittest tools.jarvis_app.test_server -v
```

Five tests cover session pause/resume/close revision flow, blocking close with nonterminal tasks, idempotent no-op status update, the HTTP status endpoint, and rejection of foreign Origin. Tests are AUTHORED, NOT RUN. No Python execution, syntax/import checks, browser/device check, Android build, APK, CI or GitHub Actions run was performed.
