# JARVIS Permission & Safety Gate V1

**Статус:** реализован как deny-by-default Python reference gate; 26 focused tests AUTHORED, NOT RUN. Это проверка допуска к одному действию, а не сервис аутентификации и не исполнитель инструментов.

## Назначение

PermissionSafetyGate проверяет, может ли конкретный шаг плана быть передан исполнителю. Он не запускает действие, не вызывает сеть или файловую систему, не выдаёт credentials и не меняет TaskSessionManager.

Решение строится по текущей SafetyPolicy, точным task/session revisions, запрошенному действию и ограничению ресурсов. Отсутствие правила означает DENIED.

## Capability policy

CapabilityRule содержит стабильное имя capability, непустой список точных scope, разрешённые классы эффекта (READ_ONLY, REVERSIBLE, EXTERNAL, IRREVERSIBLE), флаг requires_approval и верхние границы compute units, memory bytes, timeout ms, output bytes и retry count.

Префиксы scope не считаются совпадением. Повторяющиеся capability запрещены. SafetyPolicy версионирует набор правил. safety_policy_digest() считает domain-separated SHA-256 по полному каноническому содержимому policy, включая enabled, version, scope, effect и resource ceilings. Изменение правила при том же номере версии меняет digest.

## Authorization request и approvals

AuthorizationRequest связывает запрос с manager_id, task_id/revision, session_id/revision, plan_id, step_id, action_id, capability, точным scope, idempotency key, effect и RequestedBudget.

Перед разрешением gate требует:
1. Включённую policy и совпадение manager identity.
2. Существующую задачу и её owning session.
3. Точные task/session revisions, TaskStatus.RUNNING и SessionStatus.OPEN.
4. Совпадение capability, scope и effect с allow-list.
5. Соблюдение всех resource/retry ceilings.

READ_ONLY не требует approval по умолчанию. EXTERNAL и IRREVERSIBLE требуют approval всегда. REVERSIBLE также требует approval, если правило capability явно задаёт requires_approval=True.

ApprovalGrant связывает approval ID, approver/ref, manager/task/session identity и revisions, plan/step/action, capability/scope/idempotency key, policy ID/version/digest, intent digest и expires_at_ns. authorization_intent_digest() включает все поля запроса, в том числе retry_index. Grant отвергается при любом расхождении или когда now_ns >= expires_at_ns.

build_approval_grant() только формирует объект с нужными полями. Он не доказывает, что реальный пользователь подтвердил действие, что approver подлинный или что approval_ref достоверен. Создание и аутентификация approval должны происходить на отдельной доверенной границе — в будущем UI/approval provider или защищённом локальном компоненте.

## Ресурсные ограничения

Проверка сравнивает compute_units, memory_bytes, timeout_ms, output_bytes и retry_index с policy ceilings. Эти поля являются preflight contract: gate сам не измеряет CPU/RAM и не может принудительно остановить исполнитель. Executor обязан соблюдать переданные бюджеты. Значения compute_units пока не калиброваны для конкретного CPU или устройства.

## Fail-closed и ограничения

- Неизвестная capability, scope, effect или отключённая policy не разрешаются.
- Устаревшая task/session revision отвергается.
- Approval для другого manager, task, revision, step, intent или policy digest не переиспользуется.
- Gate не меняет task/session registry и не вызывает capability.
- Approval records не подписываются и не хранятся внутри самого Gate. Отдельный PersistentSafetyPolicyRegistry в tools/neuron_runtime/policy_registry.py сохраняет versioned policy и revoked approval IDs; PersistentPolicySafetyGate проверяет текущий policy digest и блокирует отозванные IDs. Контракт: docs/JARVIS_PERSISTENT_POLICY_REGISTRY_V1.md.
- Реестр не аутентифицирует approver и не проверяет подписи ApprovalGrant: caller всё ещё может создать поддельный grant. SHA-256 state digest обнаруживает случайное повреждение, но не защищает от владельца локального файла, который может переписать запись и пересчитать digest.
- Нет trusted identity/authentication service, signed grants, secrets isolation, production policy/revocation service, runtime quota enforcement, multi-process coordination внешних действий или системной sandbox.

## Проверки

```sh
python3 -m unittest tools.neuron_runtime.test_permission_safety_gate -v
```

26 тестовых методов Gate покрывают allow/deny-by-default, exact scopes/effects, каждый resource ceiling, retries, stale task/session revisions, paused/closed states, чистоту authorize(), обязательные approvals, истечение grant, повторное использование для другого intent, смену policy version/digest, manager binding и retry-index binding. Дополнительные 17 тестов test_policy_registry.py покрывают disk round-trip, permissions, CAS, monotonic policy versions, idempotent revocation, повреждённые записи и интеграцию subclass с Task Loop. Все AUTHORED, NOT RUN.