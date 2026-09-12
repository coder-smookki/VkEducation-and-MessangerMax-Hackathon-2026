# Разработка и запуск

## Полный локальный Compose

Понадобится Docker с Compose; зависимости приложения собираются в контейнерах. Это среда разработки на loopback, с синтетическим UI и реальной пустой PostgreSQL. Состояние настоящих обращений пока не записывается.

Из корня репозитория, только если `.env` ещё не создан:

```sh
cp .env.example .env
docker compose up --build -d
python3 scripts/smoke.py
```

Существующий `.env` не перезаписывать. Альтернатива без копирования файла: `docker compose --env-file .env.example up --build -d` для локальных учебных данных.

| Адрес | Назначение |
|---|---|
| `http://127.0.0.1:5173` | Учебный интерфейс и прокси `/api` |
| `http://127.0.0.1:8080/actuator/health` | Health Java и БД |
| `127.0.0.1:5432` | PostgreSQL; значения из `.env` |
| `http://ml:8000` | Python только внутри Docker-сети |

Для диагностики: `docker compose ps`, `docker compose logs --tail=60 <service>`. Не сохранять логи с реальными обращениями в Git. Остановка `docker compose down` сохраняет volume БД; `down -v` удаляет данные и не является обычной командой остановки.

## Без Docker: быстрый пример

Нужны JDK 21, Node.js 24, Python 3.12 и uv. Maven устанавливает проверенный Wrapper; npm и uv используют lock-файлы. Сначала проверить `java -version`, `node --version`, `uv --version`. Если системный JDK не установлен, выбрать JDK 21 в IDE или установить его самостоятельно; каркас не использует машинно-специфические пути.

В отдельных терминалах из корня:

```sh
# Терминал 1 - Python, без загрузки весов
cd ml
uv sync --frozen
uv run uvicorn app.main:app --host 127.0.0.1 --port 8000 --no-access-log
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

Этот путь **не проверяет Flyway, PostgreSQL или сохранение данных**. После остановки Python кнопка анализа должна показать ручной режим, а пример обращения остаться доступным.

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

Значения выше соответствуют `.env.example`, не production. Если они изменены, использовать свои локальные тестовые настройки. `PostgresSchemaTest` запускает Flyway и выполняет rollback фикстур; указывать только тестовую БД, которую разрешено мигрировать.

```sh
cd ml
uv sync --frozen
uv run pytest -q
uv run ruff check .
uv run python -m scripts.evaluate
```

```sh
cd frontend
npm ci
npm run build
```

Сквозная проверка из корня: `python3 scripts/smoke.py`. Она обращается к Java напрямую и через Vite, сверяет общий JSON-пример с настоящим ответом Python. При намеренно остановленном ML использовать `python3 scripts/smoke.py --expect-mode MANUAL`.

## Обновление контракта

1. Изменить `ml/app/contracts.py` и Java `InferenceContract` совместно с владельцами.
2. Выполнить `uv run --directory ml python -m scripts.export_openapi`.
3. Обновить `contracts/examples`, документацию и тесты несовместимых случаев.
4. Запустить Python, Java и smoke; изменение не завершено, пока один потребитель понимает старую схему.

## Работа с БД и выпуск

Flyway сам применяет миграции при обычном запуске Java. V1 и V2 уже есть и применялись; следующий свободный номер сейчас V3. Перед работой сверить актуальную `main` и номер с Семёном по [правилам параллельной работы](parallel-development.md). Применённые миграции не редактировать. `clean` выключен. Перед миграцией непустой среды сделать резервную копию и проверить восстановление; для отката запускать предыдущую совместимую сборку либо восстановление по плану, не удалять строки `flyway_schema_history`.

Публичный deployment отдельно готовится в K5: статический `frontend/dist`, один HTTPS origin для web/API, настоящий вход, ограничение доступа к БД/Python, demo выключен. Vite dev server не является production-hosting. Для MAX нужны токен, роль/бот и проверенный HTTPS-домен; эти внешние ресурсы не создавались в ходе подготовки.
