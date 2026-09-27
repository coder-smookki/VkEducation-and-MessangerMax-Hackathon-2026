# Разработка и запуск

## Mini App с настоящим Java API (26.09)

Основная проверка теперь `python3 scripts/check_miniapp.py` из корня после `npm --prefix frontend ci` и установки Chromium из README. Она создаёт отдельный Compose project со случайным тестовым ключом, PostgreSQL и release-сборкой frontend, запускает три сквозных теста и удаляет собственный project с volume. Рабочие базы не используются. Это настоящий HTTP/SQL и эмуляция только стартовых данных MAX, не проверка клиента MAX.

```sh
npm --prefix frontend run api:check
npm --prefix frontend run format:check
npm --prefix frontend run build
npm --prefix frontend test
python3 scripts/check_miniapp.py
```

Для ручной разработки Vite: `npm --prefix frontend run dev`. `API_PROXY_TARGET` задаёт адрес разрешённого локального Java API; по умолчанию `http://127.0.0.1:8080`. Пользовательские маршруты требуют серверную сессию. У UI нет публичного обхода входа или переключателя роли; фикстуры и синтетический Bridge существуют только в Playwright.

Отдельный постоянный стенд поднимается по [release](release.md). Для локального HTTP задать `SESSION_COOKIE_SECURE=false` и `SESSION_COOKIE_SAMESITE=Lax` только в закрытом тестовом env. На HTTPS нужны `true` и `None`: MAX Web открывает Mini App в iframe с другого сайта. Эти настройки применяются и к session cookie, и к CSRF cookie; CSRF-заголовок всё равно обязателен. Не открывать Vite или профили demo/no-db на публичном домене.

## Исторический учебный Compose для smoke Java/Python

Понадобится Docker с Compose; зависимости приложения собираются в контейнерах. Это среда разработки на loopback с PostgreSQL, Kafka, производным Chroma-индексом и включёнными demo API. React уже заменён на рабочее Mini App; обычное открытие в браузере потребует входа через MAX. `scripts/smoke.py` по-прежнему проверяет учебные API, а не полный пользовательский интерфейс.

Из корня репозитория, только если `.env` ещё не создан:

```sh
cp .env.example .env
docker compose up --build -d
python3 scripts/smoke.py
```

Kafka и индекс похожих обращений по умолчанию не запускаются: они нужны только функции похожих обращений, которая в кандидате выключена. Включить: `COMPOSE_PROFILES=similar KAFKA_ENABLED=true docker compose up --build -d`.

Существующий `.env` не перезаписывать. Альтернатива без копирования файла: `docker compose --env-file .env.example up --build -d` для локальных учебных данных.

| Адрес | Назначение |
|---|---|
| `http://127.0.0.1:5173` | Mini App и прокси `/api`; без сессии показан вход |
| `http://127.0.0.1:8080/actuator/health` | Health Java и БД |
| `127.0.0.1:5432` | PostgreSQL; значения из `.env` |
| `http://ml:8000` | Python только внутри Docker-сети в обычном Compose |
| `127.0.0.1:9092` | Kafka, только с профилем `similar`; внутри сети `kafka:29092` |

Для диагностики: `docker compose ps`, `docker compose logs --tail=60 <service>`. Не сохранять логи с реальными обращениями в Git. Остановка `docker compose down` сохраняет volume БД; `down -v` удаляет данные и не является обычной командой остановки.

## Без Docker: быстрый пример

Нужны JDK 21, Node.js 24, Python 3.12 и uv. Maven устанавливает проверенный Wrapper; npm и uv используют lock-файлы. Сначала проверить `java -version`, `node --version`, `uv --version`. Если системный JDK не установлен, выбрать JDK 21 в IDE или установить его самостоятельно; каркас не использует машинно-специфические пути.

В отдельных терминалах из корня:

```sh
# Терминал 1 - Python, без загрузки весов
cd ml
uv sync --frozen
KOREN_CHROMA_ENABLED=false uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log
```

```sh
# Терминал 2 - Java, profile local = demo + no-db
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

```sh
# Терминал 3 - React
cd frontend
npm ci
npm run dev
```

Этот путь **не проверяет Flyway, PostgreSQL или сохранение данных** и не обслуживает рабочие команды Mini App. Он оставлен для `scripts/smoke.py` и учебных demo API; рабочий React больше не содержит учебной кнопки анализа.

## Проверки

```sh
cd backend
./mvnw -B verify
```

В обычном запуске PostgreSQL-тесты явно пропускаются. Для полной проверки поднять `db` и выполнить из `backend/`:

```sh
DB_TEST_URL=jdbc:postgresql://127.0.0.1:5432/koren \
DB_TEST_USER=koren DB_TEST_PASSWORD=koren_local_only ./mvnw -B verify
```

Значения выше соответствуют `.env.example`, не production. Если они изменены, использовать свои локальные тестовые настройки. `PostgresSchemaTest` запускает Flyway и выполняет rollback фикстур; SimilarCasesServiceTest создаёт отдельные синтетические записи и удаляет их после проверки. Указывать только тестовую БД, которую разрешено мигрировать.

```sh
cd ml
uv sync --frozen
uv run pytest -q
uv run ruff check .
uv run ruff format --check app scripts tests
uv run pylint app
uv run python -m scripts.evaluate
```

```sh
cd frontend
npm ci
npm run build
```

Сквозная проверка из корня: `python3 scripts/smoke.py`. Она обращается к Java напрямую и через Vite, сверяет общий JSON-пример с настоящим ответом Python. При намеренно остановленном ML использовать `python3 scripts/smoke.py --expect-mode MANUAL`.

## Обновление контракта

1. Изменить схемы в `ml/app/features/inference/schemas/` или `ml/app/features/intake/contracts.py` и Java `InferenceContract`/`IntakeContract` совместно с владельцами. Каталог - [пять контрактов](../contracts/README.md).
2. Выполнить `uv run --directory ml python -m scripts.export_openapi`.
3. Обновить `contracts/examples`, документацию и тесты несовместимых случаев.
4. Запустить Python, Java и smoke; изменение не завершено, пока один потребитель понимает старую схему.

После изменения Java OpenAPI выполнить `npm --prefix frontend run api:generate`, закоммитить `frontend/src/api/schema.d.ts` и проверить `api:check`/build/UI. Не править generated TypeScript вручную.

Экспорт обновляет inference OpenAPI и confirmed-context JSON Schema. `author-review.openapi.json` поддерживается отдельно с Java REST-спецификацией, потому что Python не реализует команды автора. Python dev-зависимость jsonschema проверяет общие примеры и фактические HTTP-ответы, включая ошибки 422/413.

Для проверки новых Java → Python вызовов на настоящем локальном сервисе запустить Python как выше и из `backend/` выполнить:

```sh
INFERENCE_TEST_URL=http://127.0.0.1:8000 ./mvnw -B -Dtest=IntakeContractTest test
```

Без INFERENCE_TEST_URL этот внешний тест явно пропускается; остальные проверки используют локальный HTTP-имитатор и общие JSON. Запуск с сервисом подтверждает передачу контрактов и RULES/STUB, а не качество ML. Python не публикуется наружу ради проверки.

## Полная проверка исправлений Kafka/Chroma в dev

Из корня, с локальной тестовой конфигурацией:

```sh
export COMPOSE_PROFILES=similar KAFKA_ENABLED=true
docker compose --env-file .env.example -f compose.yaml -f compose.test.yaml up --build -d
python3 scripts/smoke.py
```

Override открывает Python только на `127.0.0.1:18081` для тестового Java-клиента; обычный Compose не публикует этот порт. После запуска выполнить:

```sh
cd backend
DB_TEST_URL=jdbc:postgresql://127.0.0.1:5432/koren \
DB_TEST_USER=koren DB_TEST_PASSWORD=koren_local_only \
INFERENCE_TEST_URL=http://127.0.0.1:18081 KAFKA_TEST_BOOTSTRAP=localhost:9092 \
./mvnw -B verify
```

```sh
cd ml
uv sync --frozen
KAFKA_TEST_BOOTSTRAP=localhost:9092 uv run pytest -q
```

KafkaIndexIntegrationTest отправляет из Java синтетические snapshots в настоящий Kafka, ждёт подтверждения consumer и проверяет Chroma через HTTP: дверь сопоставлена со вторым пунктом, версия 6 не заменяет 7. Используется настоящая стандартная ONNX-модель Chroma, а не тестовая подмена векторов. Её первый вызов загружает веса в домашний cache пользователя `koren` внутри ML-контейнера; нужен исходящий доступ для загрузки. На холодном старте HTTP может временно вернуть MANUAL. Образ имеет непривилегированного пользователя с доступным домашним каталогом. Версии/пары на синтетическом примере не измеряют качество русского поиска.

Python test_kafka_replay использует настоящий broker, отдельный уникальный тестовый topic/group и настоящую Chroma с детерминированными тестовыми векторами. События создаются до старта consumer; затем проверяется новый пустой индекс с той же уже подтверждавшей offsets группой. Удаляется только созданный этим тестом topic. Остальные Python-тесты не требуют загрузки весов.

Без KAFKA_TEST_BOOTSTRAP оба внешних Kafka-теста явно пропускаются; Java-тест также требует INFERENCE_TEST_URL. Первый Java-тест сохраняет синтетические события с уникальными ID в локальном dev-топике, продуктовые таблицы он не меняет.

Из корня проверить отказ и вернуть обычную сетевую конфигурацию:

```sh
docker compose --env-file .env.example stop ml
python3 scripts/smoke.py --expect-mode MANUAL
docker compose --env-file .env.example up -d ml
python3 scripts/smoke.py
```

Не удалять volumes ради обычной проверки. В dev топик сохраняет всю историю для восстановления монотонного индекса; до эксплуатации нужно спроектировать backfill из Java/PostgreSQL и границы хранения. Рестарт consumer перечитывает историю; детали и ограничения масштабирования - [контракт 04](../contracts/04-similar-cases.md). CI выполняет те же внешние проверки в одноразовой среде.

## Работа с БД и выпуск

Flyway сам применяет миграции при обычном запуске Java. V1 и V2 уже есть и применялись; следующий свободный номер сейчас V3. Перед работой сверить актуальную интеграционную `dev` и номер с Семёном по [правилам параллельной работы](parallel-development.md). Применённые миграции не редактировать. `clean` выключен. Перед миграцией непустой среды сделать резервную копию и проверить восстановление; для отката запускать предыдущую совместимую сборку либо восстановление по плану, не удалять строки `flyway_schema_history`.

Публичный deployment готовится в S-07/K-07 по [release.md](release.md): статический `frontend/dist`, один HTTPS origin для web/API, настоящий вход, ограничение доступа к БД/Python, demo выключен. Vite dev server не является production-hosting. Для MAX нужны токен, роль/бот и проверенный HTTPS-домен; эти внешние ресурсы не создавались в ходе подготовки.
