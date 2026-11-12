# JARVIS Task Loop V0.1

**Статус:** реализован как ограниченный последовательный Python reference coordinator; 13 focused tests AUTHORED, NOT RUN. Он связывает TaskSessionManager, ReasoningEngine, PermissionSafetyGate и внедряемый ActionExecutor. Это ещё не автономный помощник, production NCC или Android-приложение.

## Основной проход

JARVISTaskLoop.run() принимает активный TaskSessionManager и ID задачи; структурированный ReasoningProblem с исходными/целевыми фактами и готовыми PlanningAction; точную карту ActionBinding для каждого action ID (capability, scope, effect, requested budget); текущую SafetyPolicy и внешний ActionExecutor; caller-owned now_ns, опциональные approval grants и CAS-версию JPVR task checkpoint.

Последовательность:
1. Проверить задачу/сессию и входные привязки. Каждое planning action должно иметь ровно одну binding; пропущенные и лишние bindings запрещены.
2. Перевести QUEUED (или ранее WAITING) в RUNNING. Если задан JPVR store, сначала проверить текущую checkpoint version и точное равенство canonical manager snapshot; переход в RUNNING сохранить до первого side effect.
3. Запустить bounded deterministic ReasoningEngine. NO_PLAN, DEPTH_LIMIT, SEARCH_LIMIT или некорректный structured input не маскируются под успех.
4. Для каждого шага вычислить стабильные plan ID, step ID и action idempotency key.
5. Создать AuthorizationRequest с точными manager/task/session revisions и передать его PermissionSafetyGate непосредственно перед каждым действием.
6. Только после ALLOWED вызвать executor.execute(action, request).
7. Получить полный снимок observed facts после действия. Продолжить только при наличии всех ожидаемых facts_after_step. В конце ReasoningEngine.verify_result() должен подтвердить goal facts.
8. Перевести задачу в COMPLETED или FAILED и, если включён JPVR store, сохранить итоговый task/session snapshot с CAS.

## Ожидание подтверждения и стабильная версия задачи

TaskLoopStatus.WAITING — это результат orchestration-вызова, а не переход TaskSessionManager в TaskStatus.WAITING. Реестр остаётся в RUNNING с той же revision, пока ожидается approval или reconciliation неопределённого результата.

Так сделано намеренно: TaskLoopResult.pending_authorization возвращает точный запрос, для которого можно сформировать ApprovalGrant. Если бы цикл сначала переводил задачу в WAITING, а затем обратно в RUNNING, revision изменилась бы и только что созданное approval устарело бы до исполнения. Нужно смотреть на TaskLoopResult.status, а не трактовать сохранённый TaskStatus.RUNNING как доказательство, что инструмент прямо сейчас работает.

Для каждого шага approval передаётся в approvals по ключу step-000001, step-000002 и т. д. Approval одного шага не разрешает другой шаг.

## Идемпотентность и восстановление

Plan ID детерминирован по task ID, исходным/целевым фактам, описаниям/preconditions/effects actions, capability, scope и effect. Step ID определяется индексом шага. Action idempotency key основан на manager ID, task ID и task idempotency key, plan ID, step ID и action ID. При повторе того же плана используется тот же ключ. Budget ceilings и retry_index не меняют action key; при этом retry_index входит в authorization intent digest и поэтому может требовать новое approval.

Контракт ActionExecutor критичен: повтор одного idempotency key должен вернуть ранее зафиксированный результат, не выполняя внешний эффект второй раз. Если исполнитель не знает, завершилось ли действие, он должен вернуть ExecutionOutcome.UNKNOWN или поднять исключение. Loop оставляет задачу в RUNNING и возвращает WAITING с тем же запросом; повтор должен пройти через executor idempotency ledger. FAILED_NO_EFFECT допустим только когда исполнитель может гарантировать, что side effect не произошёл.

## JPVR и границы надёжности

Любое действие с effect != READ_ONLY требует JPVRTaskSessionCheckpointStore и обязательный expected_checkpoint_version. Контейнер должен быть выделен только для TaskSessionManager: у JPVR MANIFEST один active CHECKPOINT pointer. Перед продолжением уже RUNNING task loop проверяет, что caller version совпадает с persisted record version и что persisted manager snapshot точно совпадает с in-memory manager. CAS conflict, невалидный checkpoint или несовпадение состояния блокируют запуск executor.

Lifecycle snapshot фиксируется при входе в RUNNING и при terminal COMPLETED/FAILED. Сам TaskLoop V0.1 не хранит durable per-step intent/result ledger, scheduler lease или approvals. Во время одного многошагового плана checkpoint остаётся в RUNNING; после сбоя вызывающая сторона должна повторно передать тот же structured problem/bindings и открыть executor с его durable idempotency ledger. Если внешний executor не сохраняет идемпотентность вне процесса, повтор может повторно выполнить уже совершённое действие. JPVR task snapshot сам по себе не обеспечивает exactly-once external side effects.

## Что пока не реализовано

- Natural-language parsing, task decomposition из свободного текста и генерация безопасных action bindings.
- Встроенные браузер/файловые/системные инструменты, credentials, сеть или UI approval provider.
- Подлинная identity authentication, подпись/проверка approval grants, durable policy/approval registry и revocation.
- Durable step ledger/outbox, scheduler leases, concurrent workers, общая atomic transaction с внешним side effect, production NCC или multi-process locks.
- Реальное enforcement CPU/RAM/time quota внутри executor, Android sandbox и resource measurements.
- Persistence самого ReasoningResult/plan; plan детерминированно пересчитывается по caller-supplied input.

## Проверки

```sh
python3 -m unittest tools.neuron_runtime.test_task_loop -v
python3 -m unittest tools.neuron_runtime.test_permission_safety_gate -v
```

13 Task Loop tests покрывают multi-step completion, unreachable goal, deny-by-default, uncertain executor reply + stable idempotency key, known no-effect failure, missing postcondition, JPVR requirement for side effects, exact multi-step approval flow, stale checkpoint CAS и reopen of terminal state. Gate имеет 26 focused tests. Обе группы AUTHORED, NOT RUN. GitHub readback/static cross-reference only; Python execution/import checks, Android I/O, benchmarks, builds, CI и GitHub Actions не выполнялись.