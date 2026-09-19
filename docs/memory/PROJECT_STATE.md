# Текущее состояние КОРНЯ

Обновлено: 19.09.2026, Europe/Moscow.
Репозиторий: `coder-smookki/VkEducation-and-MessangerMax-Hackathon-2026`.
Рабочая ветка этого изменения: **dev**, интеграция трёх веток `codex/s1-drafts`, `codex/k1-max-auth`, `codex/n1-baseline` поверх `3904b4f`. Исправления не означают интеграцию в main.
Стадия: **работающий стартовый каркас до хакатона**, не готовый пользовательский продукт.

## Подтверждённые факты

Самостоятельный продукт КОРЕНЬ 2.0: Java 21 / Spring Boot 4.1.1, React 19.3.0 / TypeScript / Vite 8.3.0, Python 3.12 / FastAPI 0.141.1, PostgreSQL 17. Версии закреплены POM/Wrapper и lock-файлами; MAX 2025 не переносился. Схема V1/V2 не изменена.

Есть Java-приложение, правила ItemState/CaseProgress, Flyway, изолированный demo API, React-экран, inference-клиенты с валидаторами/fallback, пять общих контрактов, Compose, CI и сквозной smoke. Синтетический пример проходит frontend proxy → Java → Python и сохраняет отдельный статус света и двери. Compare STUB не подтверждает ремонт; MANUAL честно обозначает отказ анализа.

Состояние [пяти контрактов](../../contracts/README.md):

| Контракт | Реальная основа в dev | Что ещё предстоит |
|---|---|---|
| 01 extract | HTTP/DTO/валидация, честный STUB | Извлечение проблем из текста |
| 02 clarify | RULES по недостающим полям, вопросы только AUTHOR | Рабочая форма и доставка автору |
| 03 review/confirm | SCHEMA_ONLY, примеры и ConfirmedCaseContext | Авторизованные Java-команды и сохранение |
| 04 similar | Java-выборка с Membership, Kafka/Chroma, сопоставление отдельных пунктов | Подключение к CRUD/outbox, оценка качества на русском |
| 05 compare | Совместимый HTTP STUB, проверка IDs/версий/evidence | Измеренная NLI |

READY_FOR_REVIEW означает заполненный список проблем; CONFIRMED создаёт только будущая команда автора в Java. Эти признаки не означают готовность ремонта.

## Исправления review dev - 13.09.2026

1. Java запускается с `KAFKA_ENABLED=true`: подключён Boot Kafka starter и Jackson 3 serializer. Publisher отдаёт future подтверждения/ошибки; новая конфигурация топика сохраняет историю.
2. SimilarCasesService читает query и до 20 разрешённых CONFIRMED кандидатов из PostgreSQL с Membership. Полный контекст доступен автору или диспетчеру своего дома. HTTP выполняется вне транзакции; после ответа повторно проверяются права и полные snapshots всех входов. Java отвергает пустые/чужие IDs, старые версии, посторонние или повторно использованные itemId и неполное POSSIBLE_DUPLICATE. `InferenceClient.similar` доступен внутри пакета, рабочая точка входа - сервис.
3. Python сравнивает пункты отдельно; совпавшая дверь связана с queryItemId двери, включая второй пункт списка. Совпадение части проблем даёт RELATED. Полный POSSIBLE_DUPLICATE требует взаимно однозначного покрытия обоих списков.
4. Chroma хранит атомарный snapshot обращения с векторами пунктов. Версия 6 после 7 игнорируется, одинаковая версия с новым содержимым отклоняется, ошибка вычисления сохраняет прежнюю запись. Коллекция `_snapshots_v2` восстанавливается из Kafka с начала даже для прежней группы; offset подтверждается после записи. История сохраняется целиком, поскольку compaction по последнему offset могла бы оставить меньшую caseVersion. Это осознанная цена dev, не политика production.
5. Выключенный/неполный/устаревший индекс или ошибка дают MANUAL/NOT_ANALYZED. Слабое сходство ниже порога не превращается в RELATED. Пороги 0.85/0.95 - неизмеренные настройки dev; качество не заявляется.

Также исправлены доступность домашнего cache для ONNX в контейнере и совместимость Java HTTP/1.1 с Uvicorn. Base Compose оставляет Python внутри сети, тестовый override публикует loopback-порт 18081. Полные правила - [контракт 04](../../contracts/04-similar-cases.md), причины - [D13](DECISIONS.md#d13-исправление-kafkachroma-в-dev---13092026).

## Проверки

Локально на изменениях поверх 9dbed68, 13.09.2026:

- Java: `DB_TEST_URL=... INFERENCE_TEST_URL=... KAFKA_TEST_BOOTSTRAP=localhost:9092 ./mvnw -B verify` - 42 теста, 0 ошибок и пропусков. Настоящие PostgreSQL, Python HTTP и Java → Kafka → consumer → Chroma с реальной стандартной ONNX-моделью. Также проверены отзыв доступа/гонки снимков и доступность ошибки публикации.
- Python: `KAFKA_TEST_BOOTSTRAP=localhost:9092 uv run pytest -q` - 49 тестов, 2 прежних предупреждения TestClient. Проверен replay накопленной истории и нового пустого индекса с прежней группой; в Python-тестах векторы детерминированные синтетические.
- `ruff check .`, `ruff format --check app scripts tests` - прошли; `pylint app` - 10/10. OpenAPI/context schema и примеры проверяются pytest.
- Compose пересобран с включённой Kafka: smoke STUB прошёл; при остановленном ML прошёл MANUAL. Сервис возвращён к обычному Compose без тестового опубликованного порта. Volumes сохранены.
- Проверены 191 локальная Markdown-ссылка, JSON-контракты и `git diff --check`. Топик реально имеет cleanup.policy=delete и retention.ms/bytes=-1.

Команды, smoke и границы доказательства - [testing.md](../testing.md). Локальные проверки не заменяют GitHub Actions для конкретного commit. Новая CI integration повторяет PostgreSQL/HTTP/Kafka/Chroma/replay и отказ Python в одноразовой среде. История проверок первоначального каркаса остаётся в Git и testing.md, не описывает текущую готовность.

## Интеграция 19.09.2026 - S1 + K1-ядро + N0/N1

1. **S1 (черновики)**: `cases/application/CaseService` + `cases/persistence/CaseRepository` на Spring JDBC. Create/get/list/patchSource/patchItems/confirm в одной транзакции с audit и processed_command; идемпотентность по actor+operation+key с SHA-256 канонического тела; CAS по expectedCaseVersion; DEFERRABLE FK проходят commit; DRAFT виден только автору; список со стабильным cursor и повторной проверкой Membership на странице. HTTP-контроллеров нет: рабочие routes остаются закрыты до K1 (инвариант). Миграций нет, V1/V2 неизменны.
2. **K1-ядро (MAX-вход)**: `identity/MaxInitDataValidator` - проверка подписи по официальному алгоритму MAX (HMAC-SHA256, ключ `WebAppData`; в data-check-string идут URL-декодированные значения - перечитана страница dev.max.ru, шаг 4), собственный строгий percent-decoder, constant-time сравнение, политика свежести auth_date (5 минут/+60 секунд, внешний Clock). `MaxUserProvisioner` - find-or-create app_user по max_user_id, устойчив к гонке уникальности. Endpoints/сессия/CSRF не реализованы.
3. **N0/N1 (ML baseline)**: `ml/data/README.md` со схемой разметки; development-набор расширен до 16 диалогов/29 пунктов/16 templateGroup с gold evidence. Детерминированный `rules_analyzer.py` (mode=RULES, `rules-baseline-v1`) включается только env `KOREN_COMPARE_ANALYZER=rules`; поведение по умолчанию остаётся STUB, общие контрактные примеры не изменены. Evaluator прогоняет все анализаторы по одному протоколу и пишет машинные результаты в `ml/results/` с числителями/знаменателями.

## Проверки 19.09.2026

Локально на объединённом dev, Europe/Moscow:

- Java: `DB_TEST_URL=jdbc:postgresql://127.0.0.1:55432/koren ... ./mvnw -B verify` - 76 тестов, 0 ошибок, 2 честных пропуска внешних Kafka/inference env. Новые: CaseServicePostgresTest 12, MaxInitDataValidatorTest 19, MaxUserProvisionerTest 3 - на реальной PostgreSQL 16 (локально; CI использует 17). Тестовые векторы HMAC сгенерированы независимым python3-скриптом, не Java-кодом проверяемого.
- Python: `uv run pytest -q` - 63 passed, 1 прежний skip; `ruff check`, `ruff format --check`, `pylint app` 10/10; `uv run python -m scripts.evaluate` пишет `ml/results/`. RULES на development-наборе: coverage 16/29, label precision 16/16 - это не held-out и не доказательство полезности (правила и разметка проектировались вместе).
- Frontend: `npm run build` прошёл. `git diff --check` чист.

Локальные проверки не заменяют GitHub Actions для конкретного commit. Реальный MAX-клиент, сессии, HTTP CRUD, NLI, нагрузка и held-out оценка по-прежнему не проверены.

## Что ещё отсутствует

Рабочий CRUD/история через API, настоящая аутентификация/сессии и проверка Membership на endpoints, назначения исполнителей, idempotent handlers, workers inbox/outbox, надёжные analysis jobs, opt-out, NLI, UI создания/редактирования/диспетчеризации и публичный deployment. Таблицы, внутренние сервисы (включая новые CaseService/MaxInitDataValidator) и publisher не означают готовность этих функций.

CaseEventPublisher ещё не вызывается из рабочих команд сохранения: сначала реализовать CRUD и транзакционную постановку событий. Успешная ручная отправка в Kafka не доказывает durable доставку изменений БД. Хранение intake/similar результатов требует согласованной структуры: model_suggestion привязана к report_id, выдумывать отчёт нельзя.

Для Chroma остаются измерение русского similarity, подбор порогов, политика хранения/backfill и эксплуатационная обработка poison events. Поиск ограничен переданными Java кандидатами; пустой ответ не доказывает отсутствие дубликатов во всём доме. Реальный MAX, NLI и нагрузка не проверены.

## План и следующий шаг

Подробные планы Кирилла/Семёна/Никиты, C1-C4 и точки I1-I3 учитывают параллельную разработку. Миграции координирует Семён, общий статус после интеграции обновляет Кирилл. Реестр в docs/plan.md остаётся TODO: подготовка отдельных механизмов не закрывает целые задачи S1/S4/K1/N2.

В T0 сверить закрытый кейс и допуски заготовки, получить доступы MAX. Параллельно: Кирилл K1 (вход), Семён S1 (сохранение с Membership, затем outbox), Никита N0/N1 (разметка/baseline и оценка similarity). Первая общая приёмка - ручной процесс «лампа + дверь» без Python. [Передача](HANDOFF.md).

## Правило обновления

Заменять устаревшее состояние проверенными фактами: дата/commit, команда, результат, предел доказательства и ближайший незавершённый шаг. Не выдавать NOT_RUN или FAIL за успешную проверку. Перед изменением перечитывать файл и сохранять чужую работу. Существенные причины решений - DECISIONS, история - Git, без отдельной памяти для каждого помощника.

Не хранить в Git личные данные, токены, закрытое ТЗ или полные разговоры. Это общая командная память по прямому поручению пользователя; личная карточка проекта ведётся в Obsidian отдельно.
