# Контракты и статус API

## Потребитель Mini App (27.09)

Все запросы идут на `/api/v1` того же origin `https://hackathon.lumnex.ru`. Вход отправляет сырой initData в `/auth/max`, затем читает `/me`. CSRF берётся из JSON `/csrf`; после `CSRF_TOKEN_INVALID` допускается один повтор с обновлённым токеном и прежним ключом команды.

Сетевой сбой/5xx после команды означает неизвестный исход, не гарантированный отказ. UI сохраняет точное тело и Idempotency-Key для ручного повтора. При 409 серверные данные загружаются отдельно от пользовательского ввода. 404 не раскрывает чужой объект. Logout очищает session cache.

Release proxy добавляет JSON 503 `SERVICE_UNAVAILABLE` при недоступном upstream и JSON 413 при своём лимите тела. Это поведение nginx, не новые Java endpoints. `api:check` в CI запрещает расхождение generated TypeScript и OpenAPI.

Нумерация использует серверный CASE_NUMBER_PREFIX (по умолчанию пустой); UI показывает displayNumber без самостоятельного добавления «ДЕМО».

Сверено с кодом dev 27.09.2026 (S-01..S-09 и выпуск профиля MAX). Всё, что ниже названо работающим, отвечает на PostgreSQL за настоящей сессией и покрыто тестами. Машинная схема - [author-review.openapi.json](../contracts/author-review.openapi.json): у каждой операции поле `x-implementation-status`. `ContractRoutesTest` при каждом прогоне сверяет её с маршрутами, которые реально регистрирует Spring, в обе стороны: маршрут без описания и описание без маршрута роняют сборку. Остальные контракты - [contracts/README](../contracts/README.md).

## Что работает

| Метод и путь | Вход | Ответ | Задача |
|---|---|---|---|
| GET `/actuator/health` | - | 200 без внутренних деталей | - |
| GET `/actuator/info` | - | 200 с `release.sha` для сверки выпуска | S-09 |
| GET `/api/v1/csrf` | без сессии | 200 `{headerName,parameterName,token}` | S-02 |
| POST `/api/v1/auth/max` | `{initData}`, CSRF | 200 MeView, новая серверная сессия | S-02 |
| POST `/api/v1/auth/logout` | CSRF | 204, сессия уничтожена | S-02 |
| GET `/api/v1/me` | сессия | 200 MeView | S-02 |
| POST `/api/v1/memberships/claim` | `{invitationCode}` | 200 MeView | S-02 |
| POST `/api/v1/cases` | `{houseId,sourceText}` | 201 CaseView (черновик) | S-02 |
| GET `/api/v1/cases?houseId=&queueState=&cursor=&limit=` | сессия | 200 `{items,nextCursor}` | S-02, S-03 |
| GET `/api/v1/cases/{caseId}` | сессия | 200 CaseView | S-02 |
| PATCH `/api/v1/cases/{caseId}/source` | `{expectedCaseVersion,sourceText}` | 200 CaseView, новая sourceRevision | S-02 |
| PATCH `/api/v1/cases/{caseId}/items` | `{expectedCaseVersion,items:[{itemId?,category,location,symptom}]}` | 200 CaseView | S-02 |
| POST `/api/v1/cases/{caseId}/confirm` | `{expectedCaseVersion,sourceRevision}` | 200 CaseView, CONFIRMED | S-02 |
| POST `/api/v1/cases/{caseId}/acceptance` | `{expectedCaseVersion}` | 200 CaseView с receipt | S-03 |
| PUT `/api/v1/items/{itemId}/next-action` | `{expectedCaseVersion,expectedItemVersion,text,ownerId,updateDueAt}` | 200 CaseView | S-03 |
| POST `/api/v1/cases/{caseId}/reports` | `{expectedCaseVersion,reportText,targetItemIds}` | 201 `{report,case,analysis:{status}}` | S-04 |
| GET `/api/v1/reports/{reportId}` | сессия | 200 WorkReportView | S-04 |
| POST `/api/v1/reports/{reportId}/links` | `{expectedCaseVersion,links:[{itemId,expectedItemVersion,contentVersion,expectedWorkRound,workStatus}]}` | 200 CaseView | S-04 |
| GET `/api/v1/reports/{reportId}/suggestions` | сессия | 200 `{status,analysisId,result}` | S-06 |
| POST `/api/v1/items/{itemId}/observations` | `{expectedCaseVersion,expectedItemVersion,contentVersion,workRound,value,source}` | 200 CaseView | S-05 |
| POST `/api/v1/items/{itemId}/administrative-decisions` | `{expectedCaseVersion,expectedItemVersion,action,reason}` | 200 CaseView | S-05 |
| GET `/api/v1/cases/{caseId}/history?cursor=&limit=` | сессия | 200 `{items,nextCursor}` | S-05 |
| POST `/max/webhook` | update MAX и секрет в заголовке | 200 после записи во входящий ящик | S-05 |

Только в профиле `demo`: GET `/api/v1/demo/case` и `/api/v1/demo/compare`. Это учебный пример, не часть рабочего API; вне demo они закрыты. Рабочий frontend больше не использует DemoCase/Comparison: `src/api/schema.d.ts` сгенерирован из `author-review.openapi.json`, а `src/api/client.ts` экспортирует его прикладные типы.

## Чего нет

Не реализовано и в схеме не описано; UI не должен на это рассчитывать:

- назначение исполнителя: `GET /houses/{houseId}/executors`, `POST /items/{itemId}/assignment`. Отчёт в MVP сохраняет только диспетчер; `assignedExecutorId` всегда null, `visibility` всегда FULL;
- настройки уведомлений `GET/PUT /me/notification-settings`;
- загрузка фотографий/вложений, справочник исполнителей, приоритеты и публичные Java-маршруты extract/clarify/similar;
- возврат из архива или отзыва, массовый архив обращения;
- заголовок `Location` в ответах 201;
- ограничение частоты запросов к API.

Рабочий `HttpMaxSender` реализован: он отправляет ответы и уведомления из durable outbox в MAX Bot API. Webhook, ответы бота и переход в ту же карточку Mini App проверены в MAX Web; подробные границы - [эксплуатационный журнал](operations-log.md). Для доставки нужны настоящий токен и `MAX_WORKERS_ENABLED=true`. Полный G2 с двумя аккаунтами и физические mobile-клиенты остаются отдельной приёмкой.

## Общие правила

Префикс `/api/v1`. UUID - строки, время - UTC ISO 8601. `caseVersion` и `itemVersion` выдаёт сервер, клиент их не увеличивает. Все команды обращения, кроме create, принимают `expectedCaseVersion`; команда пункта - ещё и `expectedItemVersion`. contentVersion и workRound проверяются отдельно в links и observations.

Каждая команда предметной области требует `Idempotency-Key` (1-200 печатных ASCII без пробелов; UUID подходит). Уникальность - actor + operation + key; повтор с тем же телом возвращает сохранённый ответ, тот же key с другим телом - 409 `IDEMPOTENCY_KEY_REUSED`. Повтор сначала проверяет права: потерявший доступ сохранённый ответ не получает, в том числе для source/items/confirm своего черновика. Срок updateDueAt проверяется только у новой команды next-action: тот же key/body после наступления срока возвращает ранее сохранённый результат. Actor и роль берутся из сессии, не из тела.

Вход, выход и все cookie-команды защищены CSRF. Клиент берёт `token` из тела ответа GET `/csrf` и шлёт его в заголовке `headerName`; сырое значение из cookie `XSRF-TOKEN` не подходит. Вход токен не меняет: полученный до входа токен действует и после. Проверка CSRF идёт раньше проверки сессии, поэтому команда без сессии и без токена получает 403 `CSRF_TOKEN_INVALID`, а не 401. Webhook вместо CSRF проверяет секрет.

Каждый ответ, включая ошибки, несёт `X-Request-Id`. Свой id клиента из 8-64 символов `A-Z a-z 0-9 -` сохраняется, иначе выдаётся новый. Тот же id стоит в каждой строке лога этого запроса.

## Проекции

MeView: `{userId,displayName,username,avatarUrl,memberships:[{houseId,address,role}]}`. Имя, ник и фото извлекаются только после проверки подписи MAX initData и сохраняются в серверной сессии; login, `/me` и claim возвращают один профиль. `username`/`avatarUrl` nullable; имя составляется из first_name/last_name. При отсутствии имени UI использует ник, затем нейтральную подпись. Avatar URL - только абсолютный HTTPS без credentials; некорректное optional поле становится null. Java изображение не скачивает, raw user JSON в сессии не хранится. UUID principal и Membership остаются единственными источниками прав. Пустой memberships - проверенный аккаунт без доступа, не роль RESIDENT по умолчанию. Роль относится к одному дому.

CaseView: `{caseId,houseId,authorId,submissionStatus,caseVersion,sourceRevision,sourceText,displayNumber,receipt,visibility,items,progress,createdAt}`. Пункт (в порядке ввода автора): `{itemId,itemVersion,contentVersion,category,location,symptom,workRound,workStatus,observation,administrativeStatus,administrativeReason,assignedExecutorId,nextAction}`. receipt: `{confirmedAt,acceptedAt,acceptedBy}`. nextAction: `{text,ownerId,ownerDisplayName,updateDueAt,updatedAt}` или null; ownerDisplayName пока null. progress: `{total,confirmed,withdrawn,remaining,allConfirmed}`. Точные поля и nullable - в схеме.

DRAFT видит только автор; CONFIRMED - автор и диспетчер дома. Недоступное и несуществующее одинаково отвечают 404: иначе ответ выдал бы факт существования чужого обращения. Действие над видимым объектом, на которое нет права, - 403 `ACTION_FORBIDDEN`.

WorkReportView: `{reportId,caseId,actorId,reportText,targetItemIds,createdAt,links}`; link: `{itemId,contentVersion,workRound,workStatus,confirmedBy,createdAt}`.

History: `{items:[{eventId,actorId,eventType,createdAt,details}],nextCursor}`, по возрастанию `createdAt,id`, не больше 50 событий. details - белый список полей каждого типа события, не сырой payload.

## Правила состояния

PATCH items заменяет весь draft-список: пункт без itemId создаётся, неизвестный или чужой itemId отклоняется. Смысловая правка пункта увеличивает contentVersion, правка текста - sourceRevision. Confirm требует хотя бы один пункт; после CONFIRMED правка source и items - 409 `CASE_ALREADY_CONFIRMED`.

Сохранение отчёта не меняет ни одного пункта. Раунд открывает только связь, подтверждённая диспетчером, и только у своего пункта; пункт должен быть в targetItemIds отчёта. workStatus связи - PLANNED или REPORTED_DONE. Набор связей применяется целиком или не применяется вовсе. Повтор пары отчёт-пункт - 409 `LINK_ALREADY_EXISTS`; новые сведения - новым отчётом.

Наблюдение: value RESTORED, PERSISTS или CANNOT_CHECK; source AFTER_REPORT (нужен REPORTED_DONE в этом раунде) или INDEPENDENT. Только автор, только текущие contentVersion и workRound. NOT_RECEIVED - отсутствие ответа, а не команда.

Административное решение по одному пункту: ARCHIVE - диспетчер, WITHDRAW - автор; причина обязательна, оба необратимы. Отзыв идёт в progress.withdrawn, архив сохраняет прежнее наблюдение: архивный пункт без RESTORED остаётся в progress.remaining, уже подтверждённый сохраняется в progress.confirmed. total не меняется; само административное решение не создаёт allConfirmed=true.

Список: сортировка createdAt DESC, id DESC; limit по умолчанию 20, допустимо 1-50, иначе 422 - страница никогда не урезается молча. queueState (только диспетчер дома): NEEDS_ACCEPTANCE, NEEDS_ACTION, AWAITING_AUTHOR, ARCHIVED - пересекающиеся фильтры по фактам. Cursor непрозрачен и привязан к фильтру; права проверяются на каждой странице.

## Подсказки анализа

Сохранение отчёта ставит анализ в очередь той же транзакцией, если на сервере `ANALYSIS_ENABLED=true`; значение по умолчанию false. При выключенном анализе новый отчёт получает MANUAL_REQUIRED. GET suggestions читает существующую задачу независимо от текущего флага: готовый результат прежней задачи не теряется при отключении новых задач. В опубликованном выпуске 27.09 включены analysis и Python RULES; точный SHA и результат проверки - в [журнале](operations-log.md). Статусы GET `/reports/{reportId}/suggestions`: QUEUED, RUNNING, READY, STALE, MANUAL_REQUIRED. result есть только у READY. STALE - подсказка получена, но пункт с тех пор сменил contentVersion или workRound (или обращение - sourceRevision): она не отдаётся. Ответ STUB отдаётся как MANUAL_REQUIRED. Недоступный Python - повтор через 15 и 60 с, после трёх попыток MANUAL_REQUIRED. Подсказка никогда не создаёт связь: READY не значит, что работа сделана. Отзыв и архив пункта подсказку не устаревают - UI не показывает подсказку у пункта, который уже не ACTIVE.

## Лимиты и ошибки

Тело `/api` больше 256 КиБ отклоняется до чтения (413), тело без Content-Length (chunked) - 411. sourceText и reportText 1-5000 символов, location 1-300, symptom 1-1000, reason 1-1000; длина считается в Unicode code points (в UI - `Array.from(text)`). Draft - 0-20 пунктов, confirm - 1-20; targetItemIds и links непусты, без дублей, не больше 20.

Любая ошибка - `application/problem+json` с `{type,title,status,code,requestId}`; `requestId` совпадает с заголовком `X-Request-Id`. Ни текста исключения, ни класса, ни введённых значений в ответе нет.

| HTTP | code | Когда | Реакция UI |
|---|---|---|---|
| 400 | BAD_REQUEST | нечитаемый JSON, неверный UUID или параметр, нет Idempotency-Key | исправить запрос, не повторять автоматически |
| 401 | AUTH_REQUIRED | нет сессии | войти заново, сохранить незавершённый ввод |
| 401 | INVALID_MAX_INIT_DATA | подпись initData не подтверждена | войти заново из MAX |
| 403 | CSRF_TOKEN_INVALID | нет или неверен CSRF-токен | взять токен из GET `/csrf` и повторить с тем же Idempotency-Key |
| 403 | ACTION_FORBIDDEN | объект виден, действие не разрешено | показать запрет |
| 403 | INVITATION_REJECTED | код приглашения использован, истёк или не существует (причина не раскрывается) | попросить новый код у диспетчера |
| 404 | RESOURCE_NOT_FOUND | объект не существует или недоступен | не намекать на существование чужого |
| 404 | NOT_FOUND | такого маршрута нет (с сессией) | ошибка клиента |
| 409 | CASE_VERSION_CONFLICT, ITEM_VERSION_CONFLICT, STALE_CONTEXT | версия или раунд устарели | перечитать карточку, сохранить ввод, спросить пользователя |
| 409 | IDEMPOTENCY_KEY_REUSED, LINK_ALREADY_EXISTS, CASE_ALREADY_CONFIRMED | правило, а не гонка | не обходить новым ключом |
| 411 | LENGTH_REQUIRED | тело без Content-Length | слать обычное тело |
| 413 | PAYLOAD_TOO_LARGE | тело больше 256 КиБ | сократить ввод |
| 422 | INVALID_FIELDS, INVALID_TRANSITION | поля, размер страницы или переход недопустимы | показать допустимую правку |
| 500 | INTERNAL_ERROR | сбой сервера, в том числе недоступная БД | показать requestId; сохранение не подтверждено, повтор той же команды с тем же ключом безопасен |

Коды 409 и 422 приходят из команд `CaseService`; список кодов шире не расширять без схемы. Ошибка анализа никогда не превращает успешное сохранение в ошибку.

## Java → Python

Каталог [01-05](../contracts/README.md): извлечение пунктов, уточнение у автора, сравнение отчёта, похожие обращения. Python доступен только во внутренней сети. Истина схем - `ml/app/features/inference/schemas/` и `ml/app/features/intake/contracts.py`; OpenAPI экспортируется `uv run python -m scripts.export_openapi` и сверяется тестом с `app.openapi()`. Менять вместе Python, Java-контракты, общий JSON и потребителей.

| Метод и путь Python | Режим сейчас |
|---|---|
| GET `/healthz` | действующий режим и modelVersion |
| POST `/internal/v1/compare` | STUB по умолчанию Python; local Compose читает `COMPARE_ANALYZER` и передаёт `KOREN_COMPARE_ANALYZER`. В release-профиле `analysis` задан RULES `rules-baseline-v2` |
| POST `/internal/v1/extract-items` | STUB: разбор свободной фразы не подключён |
| POST `/internal/v1/clarify-items` | RULES: вопросы автору о недостающих полях |
| POST `/internal/v1/similar-cases` | сходство отдельных пунктов по проверенной Java-выборке |

Тела Python ограничены 512 КиБ до разбора, включая chunked: 413 `{detail:"REQUEST_TOO_LARGE"}`. Неверный вход - 422 `INVALID_COMPARE_REQUEST` или `INVALID_INTAKE_REQUEST` без эха текста. Ответ compare: те же ID и версии, modelVersion, mode (STUB, RULES, NLI, MANUAL) и ровно одна подсказка на каждый пункт; relation - REPORTED_DONE, PLANNED, SYMPTOM_PERSISTS, INSUFFICIENT_INFORMATION, UNRELATED, CONFLICT. Evidence `{start,end,quote}` - `[start,end)` исходного reportText в code points; содержательная relation требует точной цитаты. Java проверяет принадлежность, полноту, дубли, версии и цитату (`SuggestionValidator`) и ходит в Python только вне транзакции: connect 1 с, read 3 с.

## Бот и MAX

Webhook - отдельный корневой путь `/max/webhook`: секрет в заголовке сравнивается за постоянное время, тело до 64 КиБ, дедупликация по ключу события, строка во входящем ящике записывается до ответа 200. В штатной конфигурации без непустых `MAX_WEBHOOK_SECRET` и `MAX_BOT_TOKEN` MaxConfig не создаёт WebhookIntake; MaxWebhookController регистрируется только при наличии этого bean и вне профиля no-db. Если настроенный endpoint получает отсутствующий или неверный `X-Max-Bot-Api-Secret`, ответ - 401. Пустой секрет в env и неверный заголовок запроса - разные условия. Бот зовёт те же команды `CaseService`, что и Mini App. Mini App открывается ссылкой бота со `startapp`; payload адресует обращение, но не даёт прав: доступ проверяет GET карточки после входа. Ссылки на первичные правила: [валидация initData](https://dev.max.ru/docs/webapps/validation), [подписки](https://dev.max.ru/docs-api/methods/POST/subscriptions), [MAX Bridge](https://dev.max.ru/docs/webapps/bridge). Вход и профиль повторно проверены в выпуске 27.09; старую дату чтения документации нельзя подменять доказательством работы живого клиента.

## Как это проверяется

`ContractRoutesTest` - схема и маршруты совпадают. `CaseViewContractTest` - общие примеры проходят через Java DTO без потерь. Python `test_intake_contracts.py` - примеры соответствуют схеме, операция IMPLEMENTED не отвечает схемой TARGET. `ErrorResponsesHttpTest` - коды ошибок, CSRF-рукопожатие, requestId и лимиты через настоящий порт: MockMvc не пересылает на `/error` и не маскирует CSRF. CaseHttpTest проверяет отзыв членства перед новой source/items/confirm и перед выдачей сохранённого replay. AcceptanceHttpTest проверяет повтор next-action после истечения updateDueAt: тот же key/body получает прежний результат, новая команда с просроченной датой отклоняется. История решений до 26.09 - в Git.

## Повторное подключение дома и потерянный ответ

Погашение приглашения и запись Membership выполняются одной PostgreSQL-транзакцией. Если членство уже есть, атомарный UPSERT сохраняет действующую роль. Если оно было отозвано, приглашение жильца восстанавливает только RESIDENT, даже если прежде роль была DISPATCHER: повышенные полномочия выдаёт оператор заново. Ошибка записи членства откатывает погашение кода. Использованный/истёкший код не восстанавливает доступ.

`/memberships/claim` не имеет идемпотентности команд обращения: при потере ответа клиент сначала читает `/me`, а не автоматически повторяет одноразовый код. UI показывает проверенный список домов и нейтральное сообщение об обновлении доступа. Успех подключения показывается только после успешного ответа claim и сбрасывается перед другим кодом. Регрессии - InvitationHttpTest и Playwright UI; изменение входит в текущую `dev`, публикацию сверять по журналу.
