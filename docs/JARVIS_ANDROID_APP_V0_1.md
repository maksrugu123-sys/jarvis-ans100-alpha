# JARVIS Android App V0.1 — UI shell

**Статус:** начальный Android Compose UI shell добавлен в репозиторий; сборка и установка NOT RUN. Экран не подключён к Python runtime и не является работающим автономным ассистентом.

## Назначение

Это первый отдельный Android-проект JARVIS ANS-100 Alpha. Он задаёт минимальную точку входа на телефоне и проверяет направление пользовательского интерфейса до переноса/подключения вычислительного ядра.

- Package/application ID: `org.jarvis.ans100.alpha`.
- Namespace: `org.jarvis.ans100.alpha`.
- UI: Kotlin + Jetpack Compose + Material 3.
- minSdk 29, compileSdk/targetSdk 35, JVM target 17.
- Максимум 100 локальных черновиков в текущем состоянии экрана; длина названия ограничена 240 символами.
- Интерфейс на русском языке.

## Первый экран

- Заголовок JARVIS и состояние UI.
- Явное сообщение, что Reasoning Engine и Task Loop пока не подключены.
- Поле ввода задачи.
- Кнопка добавления локального черновика.
- Список созданных черновиков с явным статусом «ещё не отправлена в движок».

Черновики пока существуют только в Compose state процесса. Они исчезают после уничтожения процесса/перезапуска и не записываются в TaskSessionManager или JPVR. UI не запускает действия, не вызывает сеть, не просит разрешения и не утверждает, что задача выполнена.

## Границы архитектуры

Python-модули `ReasoningEngine`, `PermissionSafetyGate`, `JARVISTaskLoop` и JPVR store остаются отдельным reference runtime. Android UI не может напрямую импортировать Python-модули. Следующий интеграционный этап должен выбрать узкий локальный IPC/embedded boundary и версионированный DTO-контракт, а не переносить весь Python код в Activity.

Перед подключением исполнителя обязательны:
1. durable Android task/session repository или надёжный локальный runtime bridge;
2. сериализуемые task/session/result DTO с версиями и строгими лимитами;
3. UI отображение QUEUED/RUNNING/WAITING/COMPLETED/FAILED без вывода статуса из текста;
4. подтверждение пользователя, связанное с точным AuthorizationRequest и revision;
5. безопасное восстановление после process death и reconciliation неизвестного результата.

Не добавлять произвольные capabilities, shell execution, file/network access или background actions в UI shell.

## Проверка

- GitHub source readback после создания файлов: выполнен.
- Kotlin/Gradle static compile check: NOT RUN.
- Gradle sync/build, APK/AAB, emulator/device install: NOT RUN по ограничению проекта.
- UI/instrumentation tests: пока не добавлены и не запускались.

## Следующий шаг

Добавить отдельный versioned Android-facing domain/repository contract и экран деталей задачи/сессии; затем связать его с безопасным локальным runtime bridge. До появления реального bridge интерфейс обязан продолжать помечать записи как локальные черновики.
