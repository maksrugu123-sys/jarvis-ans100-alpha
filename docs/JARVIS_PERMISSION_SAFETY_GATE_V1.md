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

## Signed approval verification V1 — source-level verifier

Добавлен отдельный режим `TrustedApprovalSafetyGate` в `tools/neuron_runtime/signed_approval.py`. Он принимает только `SignedApprovalEnvelope`; старый `PermissionSafetyGate` по-прежнему существует как unsigned reference API для unit/reference workflows. Подмена signed envelope на обычный caller-created `ApprovalGrant` в trusted gate отклоняется.

Формат V1 использует Ed25519. Подписываются domain-separated канонические ASCII JSON bytes с prefix `JARVIS:SIGNED-APPROVAL:V1\\0`. В подписанный материал входят schema/version/algorithm, key ID, approver ID, UTC issue/expiry timestamps и все поля `ApprovalGrant`. Grant содержит точные manager/task/session revisions, plan/step/action, capability, scope, idempotency key, policy digest и authorization intent digest. Task Loop plan ID — hash полного canonical structured plan, включая action summaries/rationale/preconditions/fact changes и capability bindings; verifier затем сверяет intent digest с текущим AuthorizationRequest.

Verifier проверяет key ID и approver binding, active/revoked status, validity interval ключа, signature, UTC expiry, future-clock skew до 30 секунд и максимальную продолжительность grant 5 минут. JSON envelope ограничен 32 KiB; неизвестные/повторяющиеся поля отклоняются. Signature в JSON — unpadded Base64URL (86 символов). Для проверки нужен optional dependency `cryptography`; отсутствующая библиотека даёт fail-closed отказ, без fallback на HMAC/unsigned режим. `requirements-trusted-approval.txt` фиксирует эту optional dependency.

**Граница доверия пока не завершена:** `ApprovalTrustStore` — in-memory snapshot, который должен быть получен из out-of-band pinned trust root. `trust_epoch` пока только метаданные и не защищает от rollback. Не реализованы private-key signer, Android Keystore enrollment/biometric confirmation, desktop signer, подписанный trust bundle, persistent key rotation/revocation, anti-rollback counter, policy authority и native UI. Поэтому сам verifier — IMPLEMENTED source-level, но он не является завершённым production identity/approval service; Android/PWA его пока не используют, внешние действия остаются disabled. Для `TrustedApprovalSafetyGate` значение `now_ns` должно быть UTC Unix nanoseconds, не monotonic process time.

## Ресурсные ограничения

Проверка сравнивает compute_units, memory_bytes, timeout_ms, output_bytes и retry_index с policy ceilings. Эти поля являются preflight contract: gate сам не измеряет CPU/RAM и не может принудительно остановить исполнитель. Executor обязан соблюдать переданные бюджеты. Значения compute_units пока не калиброваны для конкретного CPU или устройства.

## Fail-closed и ограничения

- Неизвестная capability, scope, effect или отключённая policy не разрешаются.
- Устаревшая task/session revision отвергается.
- Approval для другого manager, task, revision, step, intent или policy digest не переиспользуется.
- Gate не меняет task/session registry и не вызывает capability.
- Legacy `PermissionSafetyGate` не проверяет подписи; отдельный `TrustedApprovalSafetyGate` проверяет Ed25519 envelope по pinned in-memory key snapshot. Ни один Gate не хранит approval records. Отдельный PersistentSafetyPolicyRegistry в tools/neuron_runtime/policy_registry.py сохраняет versioned policy и revoked approval IDs; PersistentPolicySafetyGate проверяет текущий policy digest и блокирует отозванные IDs. Контракт: docs/JARVIS_PERSISTENT_POLICY_REGISTRY_V1.md.
- `PersistentSafetyPolicyRegistry` не аутентифицирует approver и не проверяет подпись сам по себе. Signed verifier отделён от него; подмена trust snapshot или rollback пока не предотвращены. SHA-256 state digest обнаруживает случайное повреждение, но не защищает от владельца локального файла, который может переписать запись и пересчитать digest.
- Нет production trust-root provisioning, signing UI/private-key provider, signed trust updates, anti-rollback revocation authority, secrets isolation, runtime quota enforcement, multi-process coordination внешних действий или системной sandbox.

## Проверки

```sh
python3 -m unittest tools.neuron_runtime.test_permission_safety_gate -v
python3 -m unittest tools.neuron_runtime.test_signed_approval -v
```

26 тестовых методов legacy Gate покрывают allow/deny-by-default, exact scopes/effects, каждый resource ceiling, retries, stale task/session revisions, paused/closed states, чистоту authorize(), обязательные approvals, истечение grant, повторное использование для другого intent, смену policy version/digest, manager binding и retry-index binding. Новые 16 методов `test_signed_approval.py` покрывают Ed25519 signature binding, tampering, key identity/status/validity, expiry/clock skew, strict JSON parsing и запрет unsigned grants в trusted gate; они AUTHORED, NOT RUN. Дополнительные 17 тестов test_policy_registry.py покрывают disk round-trip, permissions, CAS, monotonic policy versions, idempotent revocation, повреждённые записи и интеграцию subclass с Task Loop. Все группы AUTHORED, NOT RUN. Ed25519/runtime verification не запускалась.