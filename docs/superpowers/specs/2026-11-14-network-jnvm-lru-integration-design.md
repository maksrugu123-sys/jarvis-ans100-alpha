# Интеграция JNVM/LRU с основной сетью — проектное решение V1

**Статус:** PROPOSED. Исходники пока не изменены; реализация и проверки не выполнены.
**Ветка:** `experiment/jarvis-integrated-prototype-v1`
**Дата:** 14 ноября 2026 года.

## 1. Цель

Подключить ограниченный LRU-working-set к основному Python `NetworkRuntime` и `NetworkJNVMCoordinator`, чтобы холодные состояния нейронов загружались из JPVR по требованию, а не удерживались полным объектным графом в RAM. При этом атомарность временного шага должна остаться неизменной: все затронутые состояния нейронов, очередь событий и часы сети публикуются одним групповым JPVR COMMIT.

## 2. Что есть сейчас

- `NetworkRuntime` глубоко копирует и хранит все `NeuronRuntime` в `_neurons`.
- `NetworkJNVMCoordinator.restore()` вызывает `JPVRNetworkJNVMStore.restore_network()`, который декодирует все состояния нейронов в один `NetworkRuntime`.
- `run_next_timestamp()` делает полную копию `NetworkRuntime`, выполняет один полный timestamp batch, затем сохраняет только изменённые neuron records вместе с полным scheduler checkpoint.
- `NeuronWorkingSet` уже умеет компактный F64/LRU и write-back, но использует 128 отдельных JPVR shard-файлов и commits по одному нейрону. Его нельзя напрямую включать в сетевой scheduler: это разрушит атомарность общего timestamp.
- Android уже имеет отдельный SQLite-backed LRU с атомарным timestamp transaction. Этот проект не заменяет и не мигрирует Android-хранилище.

## 3. Рассмотренные подходы

1. **Подключить текущий 128-shard `NeuronWorkingSet` напрямую.** Отклонён: независимые JPVR-контейнеры не могут атомарно зафиксировать несколько нейронов, очередь и часы сети.
2. **Добавить LRU-кэш к существующему полному `NetworkRuntime`.** Отклонён как решение задачи памяти: полный граф нейронов останется в RAM, а второй кэш лишь продублирует состояния.
3. **Сделать ленивый нейронный mapping поверх одного сетевого JPVR-контейнера.** Рекомендуемый вариант: topology/scheduler остаются отдельными bounded metadata; neuron state fault-in по требованию; candidate cache копируется для транзакции; изменённые записи и scheduler публикуются одним существующим group COMMIT.

## 4. Выбранная архитектура

### 4.1. Один сетевой контейнер остаётся источником истины

Использовать существующие `JPVRNetworkJNVMStore`, neuron INDEX и scheduler V2 version manifest. Не использовать 128 shard-файлов для основной сети. Не менять математические уравнения Numerical Oracle и не менять durable neuron record format в этом этапе.

### 4.2. Чтение из одного pinned snapshot

Добавить read-view, который открывает один активный JPVR snapshot и даёт ограниченный доступ к scheduler metadata и выбранным neuron records. Каждый fault-in проверяет:
- идентификатор сети и clock epoch;
- neuron record version против scheduler V2 version manifest;
- locator, generation/commit identity и целостность neuron record;
- корректность восстановленного `NeuronRuntime`.

В одном timestamp candidate все fault-in должны использовать тот же committed base snapshot. После обнаружения несовместимого поколения или версии операция завершается fail-closed; смешанный snapshot не может быть сохранён.

### 4.3. Ограниченный LRU

Добавить сетевой `NetworkNeuronWorkingSet`, использующий существующий lossless JPCS F64 codec. Начальная вместимость — 64 резидентных состояния; пользовательская конфигурация допускает диапазон 1..10 000. Холодное состояние загружается через pinned read-view. LRU содержит только чистые committed base states; он не делает независимых per-neuron JPVR commits.

### 4.4. Candidate timestamp и атомарная публикация

- Координатор создаёт изолированную candidate-копию scheduler state и LRU metadata/cache.
- NetworkRuntime вычисляет один полный timestamp batch без разделения одинаковых timestamps.
- Все затронутые neuron candidates удерживаются в отдельном bounded transaction staging set до commit. Размер staging ограничивается фактическим лимитом событий одного timestamp/run; нельзя молча вытеснить незакоммиченный candidate.
- `save_timestamp()` записывает все изменённые neuron records и полный scheduler snapshot одним JPVR typed transaction.
- Только после подтверждённого COMMIT координатор публикует candidate scheduler и обновляет LRU. При обычной ошибке прежние scheduler/cache/clock остаются авторитетными. При неопределённом commit запись блокируется до существующего reopen/reconciliation протокола.
- Внешний input и reward mutations используют ту же candidate/group-commit границу.

### 4.5. Пиковая RAM и ограничения

64 — предел обычного resident LRU, а не обещание, что вся транзакция использует только 64 neuron states. Полный текущий timestamp требует staging всех затронутых нейронов до общего COMMIT; этот набор отдельно ограничивается scheduler event budget и должен учитываться в измерениях peak RAM. JPVR snapshot/catalog и topology metadata также потребляют память. Текущая граница page catalog (по умолчанию 4 096 страниц), append-only рост и отсутствие compaction остаются отдельными ограничениями. Этот этап не должен заявлять «10 000 нейронов в 64 состояниях RAM» или гарантированное уменьшение RAM до конкретного числа без измерений.

## 5. Цели и не-цели

**Цели**
- Ленивая загрузка neuron state и ограниченный resident LRU.
- Сохранение атомарности group commit на один полный timestamp.
- Сохранение version checks, clock epoch, CAS, backpressure и fail-closed reconciliation.
- Детерминированная эквивалентность с обычным in-memory NetworkRuntime на тех же входах.
- Отдельное измерение resident cache, transaction staging, page catalog, I/O и peak RAM.

**Не-цели**
- Не подключать 128-shard working set к сетевому scheduler.
- Не менять Android SQLite LRU и не требовать Termux.
- Не менять neuron/STDP/R-STDP equations.
- Не добавлять compaction/free-page reuse, multi-process leadership или production NCC.
- Не заявлять runtime/device readiness, 10k performance или task-level intelligence.

## 6. Критерии приёмки

1. Restore загружает bounded topology/scheduler metadata, а полные neuron runtimes fault-in только по требованию.
2. Число обычных resident neuron states никогда не превышает конфигурированный LRU capacity.
3. Полный same-timestamp batch никогда не разделяется и все изменённые neuron records + scheduler/queue/clock используют один COMMIT ID.
4. Cache fault-in использует согласованный committed base snapshot; version/epoch mismatch fail-closed.
5. Pre-commit ошибка не публикует candidate и не теряет queued input.
6. Uncertain commit замораживает мутации до exact reopen/reconciliation; не допускаются слепые повторы.
7. Невалидные события, ошибки fault-in и backpressure не оставляют частично изменённое состояние.
8. Differential tests сравнивают spikes, weights, neuron clocks, queue и scheduler clock с in-memory reference.
9. Scale-профили 1/10/100/1 000/10 000 измеряют resident entries, staging peak, page catalog, disk growth, read/write amplification, latency и peak RAM на названном PC.

## 7. Безопасный порядок

Сначала tests-first для lazy read-view и cache; затем адаптер NetworkRuntime; затем координатор/group store; затем failpoint, differential и scale coverage. До разрешения пользователя на тестовые запуски тесты можно только авторить и помечать AUTHORED, NOT RUN. Gradle/Android builds, APK/AAB, CI и GitHub Actions не входят в этот этап и требуют отдельного разрешения.
