# JARVIS Task Loop V0.1

**Статус:** реализован как ограниченный последовательный Python reference coordinator; 20 focused tests AUTHORED, NOT RUN. Он связывает TaskSessionManager, ReasoningEngine, PermissionSafetyGate и внедряемый ActionExecutor. Это ещё не автономный помощник, production NCC или Android-приложение.

## Основной проход

JARVISTaskLoop.run() принимает активный TaskSessionManager и ID задачи; структурированный ReasoningProblem с исходными/целевыми фактами и готовыми PlanningAction; точную карту ActionBinding для каждого action ID (capability, scope, effect, requested budget); текущую SafetyPolicy и внешний ActionExecutor; caller-owned now_ns, опциональные approval grants и CAS-версию JPVR task checkpoint.

Последовательность:
1. Проверить задачу/сессию и входные привязки. Каждое planning action должно иметь ровно одну binding; пропущенные и лишние bindings запрещены.
2. Перевести QUEUED (или ранее WAITING) в RUNNING. Если задан JPVR store, сначала проверить текущую checkpoint version и точное равенство canonical manager snapshot; переход в RUNNING сохранить до первого side effect.
3. Запустить bounded deterministic ReasoningEngine. NO_PLAN, DEPTH_LIMIT, SEARCH_LIMIT или некорректный structured input не маскируются под успех.
4. Для каждого шага вычислить стабильные plan ID, step ID и action idempotency key.
5. Создать AuthorizationRequest с точными manager/task/session revisions и передать его PermissionSafetyGate непосредственно перед каждым действием.
6. Только после ALLOWED создать детерминированную дочернюю задачу шага, перевести её в RUNNING и сохранить общий TaskSessionManager snapshot в JPVR до вызова executor. Это durable intent шага.
7. Только после подтверждённой записи intent вызвать executor.execute(action, request).
8. Получить полный снимок observed facts. После известного SUCCEEDED сохранить дочернюю задачу шага как COMPLETED с детерминированным digest ответа. Для FAILED_NO_EFFECT сохранить FAILED. Продолжить только при наличии всех ожидаемых facts_after_step. В конце ReasoningEngine.verify_result() должен подтвердить goal facts.
9. Перевести родительскую задачу в COMPLETED или FAILED и, если включён JPVR store, сохранить итоговый task/session snapshot с CAS.

## Ожидание подтверждения и стабильная версия задачи

TaskLoopStatus.WAITING — это результат orchestration-вызова, а не переход TaskSessionManager в TaskStatus.WAITING. Реестр остаётся в RUNNING с той же revision, пока ожидается approval или reconciliation неопределённого результата.

Так сделано намеренно: TaskLoopResult.pending_authorization возвращает точный запрос, для которого можно сформировать ApprovalGrant. Если бы цикл сначала переводил задачу в WAITING, а затем обратно в RUNNING, revision изменилась бы и только что созданное approval устарело бы до исполнения. Нужно смотреть на TaskLoopResult.status, а не трактовать сохранённый TaskStatus.RUNNING как доказательство, что инструмент прямо сейчас работает.

Для каждого шага approval передаётся в approvals по ключу step-000001, step-000002 и т. д. Approval одного шага не разрешает другой шаг.

## Durable per-step intent/result и восстановление

Каждый action step представлен детерминированной дочерней TaskRecord в том же TaskSessionManager и том же JPVR checkpoint, что и родительская задача. ID и idempotency key дочерней записи выводятся из manager/task/plan/step identity. Записи связываются с родителем, планом, шагом и action через ограниченные input_refs.

Перед первым вызовом executor дочерняя задача переводится QUEUED -> RUNNING и сохраняется отдельным JPVR COMMIT. Это durable intent: после рестарта видно, что попытка могла уже привести к внешнему эффекту. Известный SUCCEEDED фиксируется переходом child RUNNING -> COMPLETED и сохранением digest outcome и observed-facts. Сам список observed facts остаётся ответом executor и восстанавливается при повторе по тому же idempotency key. Известный FAILED_NO_EFFECT фиксируется как child FAILED. UNKNOWN/исключение оставляет child RUNNING, и loop возвращает WAITING.

При восстановлении child RUNNING повторно проходит authorization gate и обращается к executor с тем же action idempotency key. Если используется DurableIdempotencyExecutor и запись осталась IN_PROGRESS/UNKNOWN, wrapper не вызывает delegate повторно и возвращает UNKNOWN; для продолжения нужен authoritative reconciliation. Если child уже COMPLETED, wrapper возвращает сохранённый полный ответ; его digest сравнивается с JPVR записью. Несовпадение приводит к fail-closed результату step_replay_result_mismatch. Без wrapper или аналогичного executor ledger прямой повтор delegate может снова выполнить side effect. Wrapper не доказывает exactly-once у downstream provider. JPVR хранит intent и digest, но не заменяет executor ledger и не хранит весь observed-facts payload.

При исчерпании лимита задач или lifecycle events child intent не создаётся/не переводится в RUNNING, поэтому executor не вызывается. Каждый intent/result checkpoint — отдельный атомарный commit TaskSessionManager. Между внешним действием и записью результата всё ещё существует crash window; он разрешается только за счёт устойчивого idempotency ledger внешнего executor. Это не общая транзакция между JPVR и внешним сервисом и не exactly-once guarantee.

## Идемпотентность и восстановление

Plan ID детерминирован по task ID, исходным/целевым фактам, описаниям/preconditions/effects actions, capability, scope и effect. Step ID определяется индексом шага. Action idempotency key основан на manager ID, task ID и task idempotency key, plan ID, step ID и action ID. При повторе того же плана используется тот же ключ. Budget ceilings и retry_index не меняют action key; при этом retry_index входит в authorization intent digest и поэтому может требовать новое approval.

Контракт ActionExecutor критичен: повтор одного idempotency key должен возвращать ранее зафиксированный результат. DurableIdempotencyExecutor выполняет локальную фиксацию claim/result и блокирует blind redispatch для уже существующего ключа; это безопасно останавливает неоднозначный повтор, но может оставить задачу в UNKNOWN даже если delegate не успел запустить действие. Если исполнитель не знает outcome, он должен вернуть UNKNOWN или поднять исключение. Loop оставляет задачу в RUNNING и возвращает WAITING с тем же запросом. FAILED_NO_EFFECT допустим только когда executor может гарантировать, что side effect не произошёл.

## JPVR и границы надёжности

Любое действие с effect != READ_ONLY требует JPVRTaskSessionCheckpointStore и обязательный expected_checkpoint_version. Контейнер должен быть выделен только для TaskSessionManager: у JPVR MANIFEST один active CHECKPOINT pointer. Перед продолжением уже RUNNING task loop проверяет, что caller version совпадает с persisted record version и что persisted manager snapshot точно совпадает с in-memory manager. CAS conflict, невалидный checkpoint или несовпадение состояния блокируют запуск executor.

Lifecycle snapshot фиксируется при входе родительской задачи в RUNNING, при каждом child-task intent/result и при terminal COMPLETED/FAILED. TaskLoop V0.1 хранит durable child-task intent/result в JPVR, но не хранит полный observed-facts payload, scheduler lease или approvals. Локальный executor ledger вынесен в отдельный wrapper и не является частью JPVR transaction. Во время одного многошагового плана checkpoint остаётся в RUNNING; после сбоя вызывающая сторона должна повторно передать тот же structured problem/bindings и открыть executor с его durable idempotency ledger. Если используется только JPVR child-task ledger и прямой executor без durable wrapper, повтор может снова вызвать уже совершённое действие. При использовании DurableIdempotencyExecutor старый unresolved key остаётся UNKNOWN до reconciliation; downstream service всё равно должен поддерживать собственную idempotency semantics. JPVR и локальный wrapper сами по себе не обеспечивают exactly-once external side effects.

## Что пока не реализовано

- Natural-language parsing, task decomposition из свободного текста и генерация безопасных action bindings.
- Встроенные браузер/файловые/системные инструменты, credentials, сеть или UI approval provider.
- Подлинная identity authentication и подпись/проверка approval grants. Отдельный локальный CAS policy/revocation reference registry добавлен в tools/neuron_runtime/policy_registry.py; его можно подключить как JARVISTaskLoop(permission_gate=PersistentPolicySafetyGate(registry)). Это не identity service и не защищает от подделки caller-supplied ApprovalGrant.
- Production downstream idempotency guarantees, authoritative trusted reconciliation provider, scheduler leases, concurrent workers, общая atomic transaction с внешним side effect, production NCC и multi-host coordination. Local SQLite DurableIdempotencyExecutor — reference wrapper, а не end-to-end exactly-once system.
- Реальное enforcement CPU/RAM/time quota внутри executor, Android sandbox и resource measurements.
- Persistence самого ReasoningResult/plan; plan детерминированно пересчитывается по caller-supplied input.

## Проверки

```sh
python3 -m unittest tools.neuron_runtime.test_task_loop -v
python3 -m unittest tools.neuron_runtime.test_permission_safety_gate -v
```

21 Task Loop tests покрывают multi-step completion, unreachable goal, deny-by-default, uncertain executor reply + stable idempotency key, known no-effect failure, missing postcondition, JPVR requirement for side effects, exact multi-step approval flow, stale checkpoint CAS, reopen of terminal state и восстановление UNKNOWN через DurableIdempotencyExecutor reconciliation. Gate имеет 26 focused tests; persistent policy registry — 17; DurableIdempotencyExecutor — 18 focused tests. Все группы AUTHORED, NOT RUN.