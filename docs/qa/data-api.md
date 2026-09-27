# DATA-API: полный сценарий и его воспроизводимая проверка

Исполнение плана [semyon-data-api](../team/semyon-data-api.md), этапы DAPI-01..03. Составлено 28.09.2026 по коду `dev` после `2833443` (seed apply/check). Это **наша** проверка через публичный HTTP API, а не runner организаторов и не MAX-клиент. `DATA-API.yaml` в этой работе не менялся: его окончательный вид зависит от ответа организаторов (DAPI-04).

## Как запустить

```sh
python3 scripts/check_data_api.py            # два свежих прогона
python3 scripts/check_data_api.py --runs 3   # больше прогонов
```

Нужны Docker Compose и Python 3.11+. Скрипт сам поднимает **одноразовый** стенд из `compose.release.yaml` (проект `koren-dapi-check-<случайно>`, порт на 127.0.0.1, случайный тестовый ключ подписи), создаёт синтетические данные, проходит сценарий и в `finally` удаляет проект вместе с томами. К публичному стенду и к рабочей базе не подключается. Ключ, initData, cookies, CSRF и коды приглашений не печатаются и не пишутся в протокол.

Итог: построчно `PASS/FAIL, прогон, check, фактическое значение (ожидание при FAIL)`, затем `RESULT PASS|FAIL: n/m checks` и код выхода 0/1. JSON-протокол с SHA исходников, флагом незакоммиченных изменений и всеми expected/actual пишется в `output/qa/data-api-<время>.json`. Каталог `output/` в `.gitignore`.

## Роли и подготовка (внутри одноразового стенда)

| Аккаунт | Роль | Как получена |
|---|---|---|
| author | RESIDENT дома А | одноразовое приглашение → `POST /memberships/claim`, как у настоящего жильца |
| dispatcher | DISPATCHER дома А | запись оператора в SQL, как `seed_stand.py dispatcher` |
| stranger | RESIDENT дома Б | приглашение дома Б → claim |

У каждой роли свой cookie jar и свой CSRF, полученный заново после входа (сессия меняется при логине). Вход — `POST /api/v1/auth/max` с initData, подписанным тестовым ключом этого стенда. Внутренние UUID берутся из `/me`. Проверяется точная матрица ролей: у каждого ровно одно ожидаемое членство, три `userId` различны. Дом выбирается по известному ID, не `memberships[0]`.

## Сценарий одного прогона

У каждого прогона новый `runId`, а ключи идемпотентности имеют вид `data-api-<runId>-<операция>`. Тела собираются словарями и сериализуются штатным JSON, поэтому версии и раунды уходят числами. Каждая следующая команда строится из последнего ответа сервера, версии «на глаз» не увеличиваются.

| Check | Роль и запрос | Что сравнивается |
|---|---|---|
| create-case | author: POST /cases | 201, DRAFT, дом А, authorId автора, тот же текст |
| patch-items | author: PATCH /cases/{id}/items | ровно два пункта: LIGHTING «Подъезд 1, этаж 3» «Не горит свет», затем ENTRANCE_DOOR «Подъезд 1, вход» «Дверь не закрывается» |
| confirm-case | author: POST /confirm (caseVersion, sourceRevision) | CONFIRMED, `receipt.confirmedAt`, progress = 2/0/0/2/false, JSON-типы |
| read-case | author: GET /cases/{id} | те же itemId, 0/2 |
| dispatcher-queue | dispatcher: GET /cases?houseId=A&queueState=NEEDS_ACCEPTANCE, с пагинацией | обращение в очереди; пустой список — FAIL |
| accept-case | dispatcher: POST /acceptance | `acceptedAt`, `acceptedBy` = UUID диспетчера, 0/2 |
| queue-after-accept | dispatcher: та же очередь | обращения там больше нет |
| set-door-next-action | dispatcher: **PUT** /items/{door}/next-action | шаг только у двери, owner — диспетчер, срок совпал, оба пункта NOT_ASSIGNED/NOT_RECEIVED, 0/2 |
| save-light-report | dispatcher: POST /cases/{id}/reports, targetItemIds = свет **и** дверь | 201, `report.links = []`, scope из двух пунктов, **все пункты байт-в-байт прежние**, 0/2, анализ QUEUED или MANUAL_REQUIRED |
| read-saved-report | author: GET /reports/{id} | тот же отчёт, текст, scope, связей нет |
| link-report-to-light | dispatcher: POST /reports/{id}/links, **только свет** | свет REPORTED_DONE, раунд +1, NOT_RECEIVED; дверь идентична снимку после next-action; 0/2 |
| observe-light-restored | author: POST /items/{light}/observations, RESTORED, AFTER_REPORT | свет RESTORED в том же раунде; дверь идентична; **2/1/0/1/false** |
| read-partial-result-author/-dispatcher | GET /cases/{id} обеими ролями | ответ полностью совпадает с ответом наблюдения |

Снимок двери берётся **после** next-action, потому что сам шаг законно меняет её `itemVersion`. Любое отличие двери после отчёта, связи или наблюдения — FAIL.

## Повторы и отказы

После каждого отказа автор перечитывает карточку. Она должна полностью совпадать с состоянием 1/2, иначе FAIL (`…-no-effect`).

| Check | Запрос | Ожидание |
|---|---|---|
| replay-observe | тот же автор, тот же ключ и тело | 200, сохранённый ответ; состояние не изменилось |
| same-key-other-body | тот же ключ, `value=PERSISTS` | 409 IDEMPOTENCY_KEY_REUSED |
| stranger-read-case / -report / -history | stranger со своей сессией и CSRF | 404 RESOURCE_NOT_FOUND, в теле нет полей обращения |
| stranger-observe | stranger: наблюдение по свету дома А | 404 RESOURCE_NOT_FOUND |
| dispatcher-observe | диспетчер отвечает вместо автора | 403 ACTION_FORBIDDEN |
| stale-case-version | автор: независимое наблюдение по двери со старой caseVersion | 409 CASE_VERSION_CONFLICT |
| accept-again-new-key | повторный приём с новым ключом | 422 INVALID_TRANSITION (не 409) |
| replay-accept | тот же ключ и тело первого приёма | 200, сохранённый первый ответ |
| case-history | dispatcher: вся история с пагинацией | ровно CASE_CREATED, CASE_ITEMS_PATCHED, CASE_CONFIRMED, CASE_ACCEPTED, ITEM_NEXT_ACTION_SET, REPORT_SAVED, REPORT_LINK_CONFIRMED, OBSERVATION_RECORDED — по одному, в этом порядке; точные details (itemId/ownerId, reportId/targetCount=2, раунд и статус связи, значение и источник наблюдения); свободного текста нет |

У каждого отказа проверяются статус, `application/problem+json`, стабильный `code`, заголовок `X-Request-Id` и отсутствие посторонних полей (допустимы `type, title, status, code, requestId, instance`).

Общие проверки: публичные `/csrf`, 401 `AUTH_REQUIRED` для `/me` и `/cases` без сессии, SHA стенда из `/actuator/info` совпадает с исходниками; все прогоны завершились и создали разные обращения; в логах стенда нет ключа, cookies, CSRF, кодов приглашений и текста обращения или отчёта.

## Найденный и исправленный дефект: requestId в теле доменных отказов

[api.md](../api.md) обещает `requestId` в теле любой ошибки. Отказы из фильтров (401/403 CSRF/404/411/413) и `/error` его несли. Отказы команд (`CaseExceptionHandler`: 404/403/409/422) и `AuthExceptionHandler` (приглашение, initData) отдавали его **только в заголовке** `X-Request-Id`. Воспроизведение до исправления: прогон 28.09 01:07 — `FAIL all problem-body-request-id missing in 16`, остальной сценарий PASS.

Исправление в этой же ветке, бизнес-логика, контракт и DTO не менялись:
- `RequestIdFilter.addTo(problem, request)` кладёт id текущего запроса в тело, то же значение, что и в заголовке;
- `CaseExceptionHandler` и оба обработчика `AuthExceptionHandler` вызывают его;
- `ErrorResponsesHttpTest.domainRefusalsNameTheirRequestInTheBodyToo`: 404 `RESOURCE_NOT_FOUND`, 403 `INVITATION_REJECTED`, 401 `INVALID_MAX_INIT_DATA` несут в теле тот же `requestId`, что в заголовке.

## Что остаётся OPEN

- **Формат организаторов (DAPI-04/05).** Неизвестно, поддерживает ли их runner зависимые запросы, извлечение значений, типизированные подстановки, новые ключи на прогон и сравнение значений. Текущий `DATA-API.yaml` (11 checks) проходит известный валидатор, но в нём постоянные ключи и `memberships[0]`, и он заканчивается на приёме. Расширять его до ответа организаторов — значит выдумывать DSL.
- **Доступ оценщика.** Сессия живёт 30 минут без активности, initData по нашей политике — 5 минут. Заранее выданные cookies не дают доступа «завтра». Нужен согласованный способ нового входа (см. вопросы).
- **Настоящий MAX и G2.** Скрипт подписывает запуск тестовым ключом одноразового стенда. Это не проверка клиента MAX. G2 на телефонах принимает София отдельно.
- **Публичный стенд.** Скрипт к нему не подключается. Роли там готовит `seed_stand.py apply/check` по [release](../release.md#тестовые-роли-для-проверки-r24).

## Два вопроса организаторам (передать капитану, самим не отправлять)

1. Пришлите, пожалуйста, официальный шаблон/JSON Schema и валидатор `DATA-API.yaml`. Поддерживает ли проверка последовательные зависимые запросы с извлечением UUID и версий из ответа, типизированными подстановками, переменными нового прогона и сравнением значений и массивов? Если нет, в каком виде приложить к файлу воспроизводимый stateful-сценарий API?
2. Наше Mini App авторизует пользователя подписанным MAX initData и серверной cookie-сессией с CSRF; роли зависят от тестового дома. Как предоставить оценщику воспроизводимый новый вход для автора, диспетчера и постороннего на весь период проверки: поддерживает ли среда запуск из MAX и отдельные cookie jars или нужен согласованный ручной bootstrap? Через какой закрытый канал передавать тестовые доступы и процедуру их обновления?

## Протокол прогонов

Заполняется по факту запуска на машине разработчика: SHA, время, среда, итог `RESULT`, список FAIL. Самопроверка логики скрипта на имитации API в эту таблицу не входит: она проверяет только сам скрипт.

| Дата | SHA исходников | Прогонов | Итог | FAIL |
|---|---|---|---|---|
| 28.09.2026 01:07 МСК, MacBook, Docker Desktop | `09bd804` + незакоммиченные `check_data_api.py` и этот файл | 2 | 77/78 | `problem-body-request-id` — дефект выше; сценарий 0/2 → 1/2, дверь, повторы, отказы и история PASS в обоих прогонах |
| 28.09.2026 01:14 МСК, MacBook, Docker Desktop | `09bd804` + незакоммиченные изменения этой ветки (скрипт, исправление requestId) | 2 | **78/78** | нет; Java 234 PASS (2 skip), включая новый `domainRefusalsNameTheirRequestInTheBodyToo` |
