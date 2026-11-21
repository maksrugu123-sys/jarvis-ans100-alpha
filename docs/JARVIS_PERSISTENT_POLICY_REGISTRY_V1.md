# JARVIS Persistent Safety Policy Registry V1

**Статус:** реализован как локальный POSIX-файловый Python reference store и fail-closed wrapper над Permission & Safety Gate V1. В `tools/neuron_runtime/test_policy_registry.py` добавлено 17 focused tests: **AUTHORED, NOT RUN**. Это не production identity service, не signed-approval system и не разрешение включить внешние действия.

## 1. Назначение и границы

Gate V1 проверяет один `AuthorizationRequest` относительно переданной `SafetyPolicy` и optional `ApprovalGrant`. До этого этапа у policy/revocation не было постоянного владельца: policy передавалась вызывающим кодом, а отозванные approval IDs нельзя было сохранить между процессами.

Новый компонент решает ровно две задачи:

1. хранит полный canonical snapshot `SafetyPolicy` и требует CAS при его изменении;
2. хранит постоянный ограниченный список отозванных `approval_id`.

Он не выдаёт grants, не запускает executor, не меняет TaskSessionManager/JPVR task checkpoint и не предоставляет HTTP/Android management API. Хранилище отдельное от JPVR: не смешивать его файл с контейнером task/session checkpoint.

## 2. Файловый формат и целостность

Реализация находится в `tools/neuron_runtime/policy_registry.py`.

Один JSON envelope содержит строго заданные поля:

- `schema_version` — сейчас 1;
- `record_version` — возрастающая версия записи CAS;
- `policy` — полный SafetyPolicy: policy ID, версия, enabled и полный список capability rules;
- `policy_digest` — canonical digest, рассчитанный существующей функцией `safety_policy_digest()`;
- `revoked_approval_ids` — отсортированный уникальный список ID;
- `state_digest` — SHA-256 от domain-separated canonical JSON остальных полей.

Decode path проверяет точный набор полей, дублирующиеся JSON-ключи, schema/record versions, все правила Gate V1, порядок/уникальность revoked IDs, оба digest и максимальный размер файла 512 KiB. Список revocation ограничен 8 192 ID. Неизвестная schema или повреждённая запись не трактуется как пустая policy: чтение завершается ошибкой, а persistent Gate отказывает.

**Важно:** SHA-256 здесь обнаруживает случайное повреждение и несогласованность записи. Это не HMAC, не цифровая подпись и не защита от локального владельца файла, который может изменить содержимое и пересчитать digest. Для защиты от злонамеренного изменения нужен отдельный root-of-trust/signature provider; этот reference registry его не создаёт.

## 3. Права и атомарная запись

Для совместимости с Linux/Android Termux используется POSIX `fcntl.flock`:

- отдельный lock-файл для чтения/записи;
- shared lock при `load()`, exclusive lock при mutation;
- файл policy и lock-файл — mode 0600;
- непосредственный родительский каталог — owner-only mode 0700, с проверкой типа и владельца;
- временный файл создаётся эксклюзивно в том же каталоге;
- contents записываются и fsync-ятся до `os.replace()`;
- после атомарной замены выполняется fsync каталога.

Это однопроцессно согласованный локальный POSIX reference mechanism с межпроцессной блокировкой файла, а не распределённая транзакционная БД. Корректность конкретной файловой системы/устройства и crash behavior ещё нужно проверить на Termux. Каталог должен находиться в приватном хранилище приложения/пользователя, не в общей папке.

## 4. API и CAS

Основные типы экспортируются также из `tools.neuron_runtime` (`__init__.py`):

- `PersistentSafetyPolicyRegistry(path)`
- `SafetyPolicyRegistrySnapshot`
- `SafetyPolicyRegistryConflict`
- `SafetyPolicyRegistryCorrupt`
- `PersistentPolicySafetyGate(registry)`

Инициализация:

```python
registry = PersistentSafetyPolicyRegistry(private_dir / "safety-policy.json")
snapshot = registry.save_policy(initial_policy, expected_record_version=0)
```

Первое сохранение требует `expected_record_version=0`; результат имеет `record_version=1`. Изменение policy требует одновременно:

1. ожидаемую актуальную `record_version`;
2. строго большую `SafetyPolicy.version`, если содержимое действительно изменилось.

Stale writer получает `SafetyPolicyRegistryConflict`. Повторное сохранение идентичной policy не создаёт лишнюю запись. Любое успешное изменение увеличивает `record_version`.

Отзыв:

```python
updated = registry.revoke_approval(
    "approval-123",
    expected_record_version=snapshot.record_version,
)
```

Revocation сохраняется между повторными открытиями процесса. Повтор уже имеющегося ID идемпотентен; новый ID увеличивает registry record version. Отзыв постоянный в рамках этого V1 API: список не имеет un-revoke операции. После отзыва новый grant должен получить новый approval ID и пройти отдельную проверку доверенным provider. При достижении 8 192 записей операция отклоняется; автоматическая очистка не проводится.

## 5. Подключение к PermissionSafetyGate и Task Loop

`PersistentPolicySafetyGate` наследует существующий `PermissionSafetyGate`, поэтому может передаваться через штатный параметр конструктора Task Loop:

```python
from tools.neuron_runtime.policy_registry import (
    PersistentPolicySafetyGate,
    PersistentSafetyPolicyRegistry,
)
from tools.neuron_runtime.task_loop import JARVISTaskLoop

registry = PersistentSafetyPolicyRegistry(private_dir / "safety-policy.json")
registry.save_policy(initial_policy, expected_record_version=0)

gate = PersistentPolicySafetyGate(registry)
loop = JARVISTaskLoop(permission_gate=gate)
```

Перед базовой проверкой Gate wrapper:
1. загружает актуальный snapshot из диска;
2. требует точного совпадения digest с policy, переданной caller;
3. отказывает с `policy_registry_missing` при неинициализированном хранилище;
4. отказывает с `policy_registry_unavailable` при I/O, corruption или некорректной записи;
5. отказывает с `policy_registry_mismatch` при несовпадении policy;
6. отказывает с `approval_revoked`, если ID переданного ApprovalGrant отозван.

После этого запускается обычная проверка Gate V1: task/session identity и revisions, OPEN/RUNNING состояние, exact capability/scope/effect, resource ceilings, approval binding и expiry.

Wrapper читает registry заново при каждом authorize(), чтобы не кэшировать уже отозванное разрешение. Это увеличивает файловые операции и latency; измерения пока нет. Между успешным authorize() и последующим executor вызовом остаётся TOCTOU окно: отзыв или смена policy после проверки не отменяет уже переданное исполнителю действие. Исполнитель должен выполнять собственную финальную проверку/lease в будущем production contract.

## 6. Чего этот слой НЕ доказывает

- `ApprovalGrant` по-прежнему caller-supplied; registry не проверяет подпись, ключ или identity approver.
- Поле `approved_by` — обычная строка, а не подтверждённая учётная запись.
- Наличие Bearer token у локальной Android/PWA панели не означает, что пользователь подтвердил конкретный action.
- SHA-256 state digest не защищает от злоумышленника, который может переписать файл и пересчитать хеш.
- Policy-файл не синхронизирован с Android Keystore/biometric flow и не имеет отдельной административной UI.
- Registry не создаёт trusted approval UI, signing keys, identity provider, remote authorization service, capability executor, sandbox, CPU/RAM enforcement или exactly-once guarantees.
- Registry не подключён к Android runtime bridge и не даёт приложению возможность выполнять Task Loop.

**Внешние эффекты остаются запрещены** до создания отдельного доверенного identity/signature provider и durable executor-owned idempotency/reconciliation. Не считать этот локальный registry достаточной production защитой.

## 7. Тесты и проверка

Команда для последующего запуска в репозитории:

```sh
python3 -m unittest tools.neuron_runtime.test_policy_registry -v
```

17 authored test methods покрывают:

- round-trip policy snapshot и permission modes;
- неинициализированное хранилище;
- stale CAS и monotonic policy versions;
- persistence/idempotency approval revocation;
- повреждённый digest и schema;
- symlink policy path и небезопасный parent directory;
- missing/corrupt/mismatched policy fail-closed;
- revoked/non-revoked approval behavior;
- возможность передать wrapper в Task Loop.

Текущий статус — **AUTHORED, NOT RUN**. Выполнялись только GitHub source readback и статическая сверка контрактов. Python imports/tests, concurrency/race tests, filesystem fault injection, Android/Termux runtime, security audit, Gradle sync/build, APK/AAB, CI и GitHub Actions **NOT RUN**.
