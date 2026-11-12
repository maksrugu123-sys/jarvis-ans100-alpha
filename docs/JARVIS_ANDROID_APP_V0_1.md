# JARVIS Android App V0.2 — persistent local draft shell

**Статус:** Android Compose shell V0.2 реализован как локальный интерфейс с сохранением сессий и черновиков в private SharedPreferences. Девять unit-тестов repository/codec AUTHORED, NOT RUN. Gradle sync/build, APK/AAB, установка и UI instrumentation NOT RUN.

## Проект и совместимость

- Application ID / namespace: `org.jarvis.ans100.alpha`.
- Kotlin + Jetpack Compose + Material 3.
- Android Gradle Plugin 8.7.3; Kotlin 2.0.21.
- compileSdk / targetSdk 35; minSdk 29; JVM target 17.
- Android entry point: `app/src/main/java/org/jarvis/ans100/alpha/MainActivity.kt`.

Корневой Gradle wrapper в текущем репозитории не обнаружен. Проект имеет Gradle Kotlin DSL build files, но фактическая Android Studio sync / system-Gradle compatibility ещё не проверена.

## Экран V0.2

- Локальная панель состояния и два счётчика: сессии и черновики.
- Создание сессий и выбор активного контекста.
- Переходы OPEN -> PAUSED -> OPEN и OPEN/PAUSED -> CLOSED с проверкой текущей revision. CLOSED terminal.
- Создание черновиков только в открытой сессии.
- Поиск черновиков в выбранной сессии.
- Просмотр деталей черновика и удаление локальной записи.
- Компактная вертикальная компоновка со скроллом для небольшого экрана.

Каждая задача помечается как **локальный черновик**. UI не показывает QUEUED/RUNNING/COMPLETED и не заявляет, что Python Reasoning Engine или Task Loop выполнили работу.

## Android local repository V1

Файлы:
- `app/src/main/java/org/jarvis/ans100/alpha/data/LocalDraftRepository.kt` — versioned codec, bounded models, session state transitions, repository operations.
- `app/src/main/java/org/jarvis/ans100/alpha/data/SharedPreferencesDraftStorage.kt` — private application SharedPreferences adapter.
- `app/src/main/java/org/jarvis/ans100/alpha/JarvisViewModel.kt` — StateFlow UI state and lifecycle owner.

Persisted text format begins with `JARVIS_ANDROID_DRAFTS\t1`; each session/task record is a type-tagged, tab-separated line with UTF-8 text fields encoded as URL-safe Base64. Decoder rejects unknown schema, unknown record types, malformed Base64/UTF-8, duplicate IDs, invalid counters/statuses, dangling session references and out-of-range sizes before returning state.

Hard limits:
- 32 sessions;
- 500 local task drafts;
- 240 characters per title;
- 512 KiB encoded snapshot;
- positive timestamps and revisions;
- session status revision compare-and-swap.

Mutations encode a complete candidate snapshot and use synchronous SharedPreferences `commit()`. An unsuccessful commit throws and does not report the candidate as saved. The synchronous disk write is deliberately bounded but has not been profiled on the target phone; this is a measurement gate, not a performance guarantee.

## Trust and storage boundary

This is an Android-only local draft repository. It is **not** the Python TaskSessionManager, JPVR, JNVM or an interoperable shared database. The Android state does not flow into the Python process. It contains no external executor, shell, filesystem/network capability, credentials, approval provider or autonomous background task runner.

The repository is app-private SharedPreferences; the manifest disables Android backup. This design is a first persistence shell, not encrypted storage against a compromised device and not a multi-process transaction protocol.

The separate `tools/jarvis_app` PWA remains an independent loopback Python console backed by JPVR; it is not automatically connected to this Compose application.

## Verification

```sh
python3 -m unittest tools.jarvis_app.test_server -v
```

The Android repository suite is authored at:

```text
app/src/test/java/org/jarvis/ans100/alpha/data/LocalDraftRepositoryTest.kt
```

Nine tests cover repository recreation/round-trip, session transitions, stale revision rejection, closed/paused task-creation denial, no-op commit avoidance, failed storage commit, unknown schema rejection, session bound and invalid title rejection. Tests are AUTHORED, NOT RUN. No Kotlin compile, Gradle sync/build, APK/AAB, emulator/device install, Compose UI test or Android performance measurement was run.

## Next integration boundary

Implement a narrow, versioned Android-facing bridge to the local Python runtime. It must reconcile the separate Android draft model with TaskSessionManager identity/revisions and the JPVR checkpoint version before showing authoritative task states. The next UI increment must also provide a trusted approval surface bound to the exact AuthorizationRequest; do not turn draft actions into real tool calls before this trust boundary exists.
