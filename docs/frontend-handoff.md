# Backend для frontend: как подключиться

26.09.2026, Семён → Кирилл. Короткая инструкция, чтобы Mini App работал с настоящим backend, а не с demo. Полные поля и коды - [api.md](api.md) и [author-review.openapi.json](../contracts/author-review.openapi.json); схема и маршруты сверяются тестом, так что в них нет ни одного несуществующего пути.

## Поднять backend локально

```
cp .env.example .env            # один раз; в .env вписать MAX_BOT_TOKEN
docker compose up -d --build db ml backend
python3 scripts/stand_smoke.py --base-url http://127.0.0.1:8080 --local
```

Kafka больше не стартует по умолчанию (профиль `similar`), backend её не ждёт. `SPRING_PROFILES_ACTIVE=demo` по умолчанию оставляет учебные `/api/v1/demo/**` для разработки; рабочий UI на них опираться не должен. Если Docker Hub не отдаёт образы (TLS handshake timeout), в Docker Desktop → Settings → Docker Engine добавить `"registry-mirrors": ["https://mirror.gcr.io"]`.

Тестовые роли без ручного SQL:

```
python3 scripts/seed_stand.py house "Синтетическая, 1"      # печатает houseId
python3 scripts/seed_stand.py invite <houseId> --count 2     # коды жильцов
python3 scripts/seed_stand.py dispatcher <houseId> <maxUserId>   # после первого входа этого пользователя
```

Жилец получает доступ только вводом кода в Mini App (POST `/memberships/claim`), как в жизни.

## Вход и CSRF

1. GET `/api/v1/csrf` → `{headerName, parameterName, token}`. Брать `token` **из тела**, не из cookie `XSRF-TOKEN` - в cookie сырое значение, оно не подходит.
2. POST `/api/v1/auth/max` с `{initData: window.WebApp.initData}` ровно как дал MAX (не декодировать и не пересобирать) и заголовком `headerName: token`. Ответ - MeView; cookie сессии ставит сервер.
3. Дальше каждый POST/PUT/PATCH - с тем же заголовком CSRF. Вход токен не меняет. Запросы с `credentials: "same-origin"` (или `include`, если API на другом origin - но по плану origin один).
4. GET `/api/v1/me` при открытии - есть ли сессия и какие дома и роли. Пустой `memberships` - вошёл, но доступа нет: показать ввод приглашения.

403 `CSRF_TOKEN_INVALID` → заново взять токен и повторить ту же команду с тем же Idempotency-Key. 401 `AUTH_REQUIRED` → войти заново, сохранив ввод.

## Команды

- Каждая команда - заголовок `Idempotency-Key` (UUID на одно намерение пользователя). Повтор после обрыва сети - с тем же ключом и телом: сервер вернёт сохранённый ответ, а не сделает второе действие.
- Версии не считать на клиенте: передавать `expectedCaseVersion`/`expectedItemVersion` из последней карточки. 409 `CASE_VERSION_CONFLICT` или `STALE_CONTEXT` - перечитать карточку, сохранить ввод, спросить пользователя.
- Почти все команды отвечают полной CaseView - перерисовывать из неё, не угадывать.

## Экран → запрос

| Экран | Запрос |
|---|---|
| Список своих обращений | GET `/cases?limit=20`, дальше `cursor` из `nextCursor` |
| Новое обращение | POST `/cases` → PATCH `/cases/{id}/items` (весь список) → POST `/cases/{id}/confirm` |
| Карточка | GET `/cases/{id}`; прогресс - `progress`, пункты по порядку ввода |
| Очередь диспетчера | GET `/cases?houseId=&queueState=NEEDS_ACCEPTANCE` (или NEEDS_ACTION, AWAITING_AUTHOR, ARCHIVED) |
| Принять | POST `/cases/{id}/acceptance` |
| Следующий шаг по пункту | PUT `/items/{itemId}/next-action` |
| Отчёт о работах | POST `/cases/{id}/reports` → связи POST `/reports/{reportId}/links` выбирает диспетчер |
| Ответ автора по пункту | POST `/items/{itemId}/observations` |
| Отозвать (автор) / архив (диспетчер) | POST `/items/{itemId}/administrative-decisions` с причиной |
| История | GET `/cases/{id}/history` |

Сохранение отчёта ничего не меняет в пунктах - только подтверждённая диспетчером связь. Подсказки анализа (GET `/reports/{id}/suggestions`) в кандидате выключены и честно отвечают MANUAL_REQUIRED: ручной выбор связей - основной путь, не запасной. Если когда-то включатся - показывать только READY и никогда не отмечать связь автоматически.

## Чего нет - не рисовать

Назначение произвольного исполнителя, настройки уведомлений, возврат из архива пока отсутствуют. Имена nullable: UI показывает только полученное от Java значение и не придумывает имя. HttpMaxSender и webhook подключены в dev (PR10/11); включение workers на конкретном стенде и реальная доставка проверяются при выпуске.

## Для стенда (K-07)

- `SPRING_PROFILES_ACTIVE=release` - иначе demo открыт наружу; `SESSION_COOKIE_SECURE=true` на HTTPS.
- Выпуск с версией: `RELEASE_SHA=$(git rev-parse HEAD) docker compose up -d --build`; SHA виден в `/actuator/info`.
- Прокси: `/api/v1` и `/max/webhook` - на backend, остальное - статика. Ошибка API не должна подменяться index.html: `stand_smoke.py` это проверяет.
- Наружу только HTTPS. Порты 5432, 8000, 9092 закрыты: smoke проверяет и их.
- Проверить заранее, что сервер стенда скачивает образы с Docker Hub - иначе выкладка 29.09 встанет на `docker pull`.
- После выкладки: `python3 scripts/stand_smoke.py --expect-sha <SHA>` с любой машины, все строки PASS.
