# Backend для frontend: как подключиться

Обновлено 27.09.2026, передача Семён → Кирилл. Короткая инструкция, чтобы Mini App работал с настоящим backend, а не с demo. Полные поля и коды - [api.md](api.md) и [author-review.openapi.json](../contracts/author-review.openapi.json); схема и маршруты сверяются тестом, так что в них нет ни одного несуществующего пути.

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

403 `CSRF_TOKEN_INVALID` → заново взять токен и повторить ту же команду с тем же Idempotency-Key. 401 `AUTH_REQUIRED` → предложить повторный вход, сохранив ввод на странице. Если initData старше пяти минут или сервер ответил INVALID_MAX_INIT_DATA, нужен новый запуск из MAX. В диалоге доступно read-only поле для копирования ввода, даже когда исходная форма заблокирована; закрытие Mini App само по себе не сохраняет форму. Проверка подписи и срока остаётся в Java.

## Команды

- Каждая предметная команда обращения - заголовок `Idempotency-Key` (UUID на одно намерение пользователя). Повтор после обрыва сети - с тем же ключом и телом: сервер вернёт сохранённый ответ, а не сделает второе действие.
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
| История | GET `/cases/{id}/history`, следующая страница по cursor; первый запрос при раскрытии |
| Доступ / профиль | GET `/me`, POST `/memberships/claim`, POST `/auth/logout`; отказ приглашения INVITATION_REJECTED |
| О сервисе (`/#/about`) | Статическая справка до входа, 112, удаление по smookki@yandex.ru; mailto, не API-команда |

Сохранение отчёта ничего не меняет в пунктах - только подтверждённая диспетчером связь. Подсказки анализа читаются GET `/reports/{id}/suggestions`: QUEUED/RUNNING показывают загрузку, READY даёт точные цитаты, STALE/MANUAL_REQUIRED сохраняют ручной выбор. В выпуске 27.09 analysis и Python RULES включены; по умолчанию в новой release-конфигурации ANALYSIS_ENABLED=false. Готовая подсказка не выбирает и не отправляет связь автоматически.

## Чего нет - не рисовать

Назначение произвольного исполнителя, настройки уведомлений, возврат из архива пока отсутствуют. Фото/вложения обращений, приоритеты и публичные extract/clarify/similar также отсутствуют. Профиль nullable: UI использует только поля MeView, полученные после HMAC-проверки. HttpMaxSender и webhook работают в опубликованном выпуске; состояние конкретного SHA и границы настоящей MAX-проверки - в [журнале](operations-log.md).

## Для стенда (K-07)

- Использовать отдельный `compose.release.yaml` и приватный env по [release.md](release.md), не объединять его с dev `compose.yaml`, включающим demo. `RELEASE_SHA` обязателен; SHA виден в `/actuator/info`.
- Учебный стенд: `TRAINING_MODE=true` при сборке web и `CASE_NUMBER_PREFIX=ДЕМО-` для Java. Данные и права по-прежнему приходят из API.
- HTTPS: `SESSION_COOKIE_SECURE=true`, `SESSION_COOKIE_SAMESITE=None` одновременно для session и CSRF cookie. Эта конфигурация проверена в MAX Web iframe. Рабочий web-порт привязывается к loopback; публичный HTTPS обслуживает существующий reverse proxy.
- Прокси: `/api/v1`, `/max/webhook` и ровно GET `/actuator/health`/`/actuator/info` - на backend; остальной `/actuator` возвращает JSON 404; пользовательские маршруты - статика. Ошибка API не должна подменяться index.html: `stand_smoke.py` это проверяет.
- Наружу только HTTPS. Порты 5432, 8000, 9092 закрыты: smoke проверяет и их.
- Проверить заранее, что сервер стенда скачивает образы с Docker Hub - иначе выкладка 29.09 встанет на `docker pull`.
- После выкладки: `python3 scripts/stand_smoke.py --expect-sha <SHA>` с любой машины, все строки PASS.

## Профиль MAX - 27.09

После HMAC/time проверки backend сохраняет displayName, username, avatarUrl в серверном Authentication.details. `/me` и claim больше не теряют имя после login. Frontend читает только этот MeView; initDataUnsafe не становится источником профиля или прав. Изображение имеет фиксированный круглый контейнер, object-fit:cover и referrerPolicy=no-referrer; при отсутствии/ошибке - SVG fallback. CSP разрешает текущий CDN фото MAX https://i.oneme.ru только для img-src. Смена CDN потребует явного пересмотра CSP; произвольные внешние источники не открыты. Отдельного username у пользователя может не быть, тогда показывается имя из MAX. Данные профиля обновляются при следующем проверенном входе.

## Приглашение и восстановление доступа

После успешного claim использовать возвращённый MeView для session cache. Потерянный ответ требует GET `/me`, потому что код одноразовый и не поддерживает идемпотентный replay. «Доступ обновлён» после GET означает только чтение списка, не доказательство погашения конкретного кода. Новый ввод убирает старое сообщение об успехе.

Новый валидный код корректно обрабатывает существующее членство. Активная роль сохраняется; отозванное членство возвращается только как RESIDENT. Приглашение не восстанавливает прежние права диспетчера. Откат транзакции сохраняет код неиспользованным.
