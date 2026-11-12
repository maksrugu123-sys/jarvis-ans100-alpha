# JARVIS Durable Executor Idempotency Ledger V1

**Статус:** реализован как локальный SQLite/Python reference wrapper для caller-supplied ActionExecutor. `tools/neuron_runtime/test_executor_idempotency.py` содержит focused regression tests, **AUTHORED, NOT RUN**. Это локальный журнал резервирования и результатов вызова, не распределённая exactly-once транзакция и не production external executor.

## 1. Назначение

Task Loop уже сохраняет дочернюю задачу шага до вызова executor, но внешний исполнитель должен самостоятельно поддерживать устойчивую идемпотентность по `AuthorizationRequest.idempotency_key`. Если executor не сохранил собственный результат, после рестарта повторный вызов может снова применить side effect.

`DurableIdempotencyExecutor` оборачивает существующий объект с методом `execute(action, request)` и добавляет локальную SQLite-запись:
- атомарно резервирует idempotency key до передачи управления delegate;
- связывает key с digest точного authorization intent и полного PlanningAction;
- сохраняет известный SUCCEEDED или FAILED_NO_EFFECT result;
- повторно возвращает сохранённый результат, не вызывая delegate;
- никогда автоматически не redispatch-ит ключ, оставшийся IN_PROGRESS/UNKNOWN.

Это ограниченный reference-механизм для одного устройства и одной SQLite database. Он не предоставляет встроенные browser/file/network tools.

## 2. API

Типы находятся в `tools/neuron_runtime/executor_idempotency.py` и экспортируются из `tools.neuron_runtime`:

- `DurableIdempotencyExecutor(path, delegate, busy_timeout_ms=5000, max_records=256)`;
- `DurableExecutionRecord`;
- `DurableExecutorLedgerError`, `DurableExecutorLedgerConflict`, `DurableExecutorLedgerCorrupt`, `DurableExecutorLedgerCapacity`.

Подключение к существующему Task Loop не меняет его planner или safety gate:

```python
ledger_executor = DurableIdempotencyExecutor(
    private_dir / "executor-ledger.sqlite3",
    delegate=your_existing_executor,
)
result = task_loop.run(
    task_manager=manager,
    task_id=task_id,
    problem=problem,
    action_bindings=bindings,
    policy=policy,
    executor=ledger_executor,
    # остальные обязательные параметры Task Loop остаются прежними
)
```

Реестр совместим с протоколом `ActionExecutor`, но сам не является исполнителем. Все реальные capabilities по-прежнему принадлежат переданному delegate. Вызов wrapper не обходит Permission & Safety Gate: Task Loop должен сначала разрешить шаг и только затем вызвать executor.

## 3. Ключ и проверка идентичности запроса

Перед резервированием вычисляется domain-separated SHA-256 от:
- `authorization_intent_digest(request)`, который связывает manager/task/session IDs и revisions, plan/step/action IDs, capability, scope, idempotency key, effect и RequestedBudget;
- полного `PlanningAction`: action ID, summary, rationale, preconditions, add_facts и delete_facts.

Повторное использование ключа с иным request/action приводит к `DurableExecutorLedgerConflict`. Изменить scope, budget, revisions или payload действия под прежним ключом нельзя.

Результат сохраняется как строгий bounded JSON:
- outcome SUCCEEDED или FAILED_NO_EFFECT;
- полный отсортированный observed_facts snapshot;
- failure_code для FAILED_NO_EFFECT.

Result SHA-256 позволяет обнаружить случайное повреждение сохранённого ответа. Как и другие local checksums в reference store, это не MAC и не защита от владельца файла, способного изменить database.

## 4. State machine и crash recovery

Состояния одной записи:

| Состояние | Значение | Повтор `execute()` |
|---|---|---|
| `in_progress` | ключ зарезервирован и delegate мог быть вызван | возвращает UNKNOWN, delegate не вызывается |
| `unknown` | delegate бросил исключение или вернул UNKNOWN | возвращает UNKNOWN, delegate не вызывается |
| `succeeded` | известный полный успешный результат сохранён | возвращает сохранённый result |
| `failed_no_effect` | delegate гарантировал, что side effect не произошёл | возвращает сохранённый no-effect result |

Порядок первой попытки:
1. Открыть SQLite transaction с `BEGIN IMMEDIATE`.
2. Проверить, что key отсутствует и capacity не исчерпана.
3. Сохранить `in_progress` и commit.
4. Только после успешного commit вызвать delegate.
5. Для известного SUCCEEDED/FAILED_NO_EFFECT сохранить полный result и digest второй транзакцией.
6. При исключении, UNKNOWN или проблеме сохранения результата не выдавать ложный успех; следующая попытка останется UNKNOWN.

Это закрывает слепое повторное делегирование того же ключа через этот wrapper, в том числе при двух конкурирующих локальных вызовах. Но остаётся crash window между резервированием и side effect, а также между side effect и сохранением result. Запись после рестарта может застрять в IN_PROGRESS/UNKNOWN, даже если delegate вообще не успел выполнить действие. Это сознательный fail-closed выбор в пользу безопасности над доступностью.

## 5. Reconciliation

Если оператор/адаптер независимо запросил authoritative состояние внешней системы и точно установил исход прежней попытки, caller может вызвать:

```python
ledger_executor.record_reconciled_result(
    action,
    request,
    verified_execution_result,
    reconciliation_ref="provider-check-123",
)
```

Метод требует уже существующий exact request/key и известный SUCCEEDED/FAILED_NO_EFFECT result. Он не создаёт новый вызов delegate; после публикации повторный `execute()` возвращает сохранённый результат.

**Критическая оговорка:** `reconciliation_ref` — только audit identifier, обычная строка. Метод записывает утверждение caller и сам не обращается к provider, не аутентифицирует оператора, не проверяет подпись и не доказывает реальное внешнее состояние. Вызвать его можно только из будущего доверенного reconciliation boundary. Не строить на этом методе публичный HTTP endpoint или UI, пока не появятся identity/signature проверки.

## 6. Файловая безопасность и ограничения ресурсов

- SQLite database хранится в owner-only directory (0700); database file имеет mode 0600.
- SQLite uses `BEGIN IMMEDIATE`, busy timeout and `synchronous=FULL` transactions.
- Действует лимит до 256 записей по умолчанию; можно уменьшить `max_records`, но нельзя увеличить выше hard cap V1.
- Serialized result ограничен 128 KiB; database ограничивается 16 384 страницами SQLite (при стандартной 4 KiB page size это около 64 MiB).
- При заполнении ledger новая неизвестная операция отвергается до вызова delegate. Автоматическое удаление старых ключей запрещено: удаление unresolved/completed key может разрешить повтор side effect.
- Для начала требуется выделить ledger отдельный файл в приватном каталоге Termux. Не хранить его в общей папке или Git.

SQLite обеспечивает локальную сериализацию конкурирующих writers, но это не multi-host consensus и не transaction coordinator для внешних сервисов. Удаление/подмена database владельцем устройства не защищено криптографически. Состояние ledger следует архивировать/удалять только после завершённой отдельной политики retention, reconciliation и истечения допустимого периода повторов.

## 7. Что не гарантируется

- Wrapper гарантирует, что **он сам** не вызовет delegate повторно для уже существующего unresolved или final key. Он не гарантирует, что произвольный внешний delegate/сервис выполнил side effect ровно один раз.
- Если delegate имеет собственный idempotency API, его contract остаётся обязательным; downstream idempotency — дополнительная защита, а не необязательная замена ledger.
- Если ledger уничтожен или заменён, он больше не знает о старых ключах.
- UNKNOWN не разрешается автоматическим retry. Нужен independent reconciliation.
- Локальный checksum не защищает от владельца DB-файла.
- Этот компонент не создаёт credentials, identity, approvals, signed grants, browser/network executor, sandbox или Android integration.
- Android runtime bridge не подключён к Task Loop или этому wrapper. Внешние действия остаются выключены.

Не заявляется exactly-once. Корректное название гарантии V1 — **durable local invocation claim + result replay + fail-closed unresolved-key handling**.

## 8. Тесты и проверки

Запланированная команда:

```sh
python3 -m unittest tools.neuron_runtime.test_executor_idempotency -v
```

19 методов покрывают persistence/reopen, SUCCEEDED и FAILED_NO_EFFECT replay, exact key binding, exception/UNKNOWN handling, сохранение unresolved UNKNOWN через reopen, caller reconciliation, contradictory result rejection, concurrent duplicate calls, private file modes, symlink/unsafe-directory rejection, schema/result-digest corruption, action ID validation и capacity fail-closed.

Статус: **AUTHORED, NOT RUN**. GitHub source readback/static inspection only. Python execution/import checks, multi-process race tests, SQLite fault injection, Android/Termux measurements, security audit, Gradle/Android builds, APK/AAB, CI and GitHub Actions **NOT RUN**.
