# Контракты и статус API

Обновлено: 12.09.2026. Раздел «Сейчас» описывает код; остальные разделы - **согласуемый контракт будущего MVP** для параллельной реализации. Новые рабочие endpoints ещё закрыты. Примеры не являются результатами HTTP-вызовов.

## Сейчас

| Метод | Адрес | Доступ | Результат |
|---|---|---|---|
| GET | Java `/actuator/health` | Health | При готовности 200, без внутренних деталей |
| GET | Java `/api/v1/demo/case` | Только demo/local | Фиксированный synthetic пример |
| GET | Java `/api/v1/demo/compare` | Только demo/local | Реальный вызов Python, STUB либо MANUAL |
| GET | Python `/healthz` | Внутренняя сеть | Процесс и mode=STUB |
| POST | Python `/internal/v1/compare` | Внутренняя сеть | Строгий контракт подсказок |

`DemoCase` и `Comparison` из `frontend/src/api/client.ts` - типы примера, не готовые DTO авторизованного приложения. Не расширять `/demo/**` рабочими командами.

## Общие правила целевого REST

Prefix `/api/v1`. UUID - строки, времена - UTC ISO 8601. Версии - строгие целые положительные числа, workRound допускает 0. Backend выдаёт `caseVersion` из `service_case.version`, `itemVersion` из `case_item.version`. Клиент не увеличивает их самостоятельно.

Любая команда предметной области требует `Idempotency-Key` длиной 1-200 ASCII-символов без пробелов/управляющих символов. Для UI достаточно UUID. Область уникальности - actor + operation + key; target и каноническое тело входят в request_hash. Повтор передаёт прежние версии/тело; тот же key для другого target в той же operation даёт конфликт. Actor и роль берутся сервером. Обработка конкуренции и replay - [implementation.md](implementation.md).

`/auth/max`, `/auth/logout` и webhook не используют `processed_command` бизнес-операций: у них собственный протокол входа/выхода или event key. Cookie-команды, включая вход/выход, защищены CSRF; webhook использует отдельную проверку секрета.

Все команды обращения, кроме create, принимают `expectedCaseVersion`. Прямая команда пункта также принимает `expectedItemVersion`. ContentVersion и workRound проверяются отдельно для links/observations. Термин `expectedVersion` без указания сущности в новых DTO не использовать.

## C1. Вход и черновик

| Метод и путь | Вход | Успешный результат | Владелец |
|---|---|---|---|
| GET `/csrf` | До/после входа, та же сессия | 200 `{headerName,token}`; Cache-Control: no-store | K1 |
| POST `/auth/max` | `{initData}`, CSRF | 200 MeView + серверная cookie; после входа обновить CSRF | K1 |
| POST `/auth/logout` | CSRF | 204, сессия недействительна | K1 |
| GET `/me` | Сессия | 200 MeView | K1/S1 |
| POST `/cases` | `{houseId,sourceText}` | 201 CaseView, Location карточки | S1 |
| GET `/cases?houseId=&cursor=&limit=` | Сессия, необязательный фильтр дома | 200 `{items,nextCursor}` | S1 |
| GET `/cases/{caseId}` | Сессия | 200 CaseView в доступной проекции | S1 |
| PATCH `/cases/{caseId}/source` | `{expectedCaseVersion,sourceText}` | 200 CaseView с новой sourceRevision, только DRAFT | S1 |
| PATCH `/cases/{caseId}/items` | `{expectedCaseVersion,items:[{itemId?,category,location,symptom}]}` | 200 CaseView, полная замена draft-списка | S1 |
| POST `/cases/{caseId}/confirm` | `{expectedCaseVersion,sourceRevision}` | 200 CaseView, CONFIRMED | S1 |

MeView: `{userId,displayName,memberships:[{houseId,address,role}]}`. displayName может быть null. Роль относится к конкретному Membership и не является разрешением действовать во всех домах. Пустой memberships - проверенный аккаунт без доступа, а не автоматическая роль RESIDENT.

CaseView: `{caseId,houseId,authorId,submissionStatus,caseVersion,sourceRevision,sourceText,visibility,items,progress,createdAt}`. Item: `{itemId,itemVersion,contentVersion,category,location,symptom,workRound,workStatus,observation,administrativeStatus,administrativeReason,assignedExecutorId}`. Nullable: причина для ACTIVE и отсутствие назначения. Четыре факта/enum определены в [domain.md](domain.md).

`visibility=FULL` для автора и диспетчера, которому доступно подтверждённое обращение. Исполнитель видит `ASSIGNED_ITEMS`: только назначенные ему пункты, sourceText=null, progress=null, без общей истории/полного текста чужих отчётов. Если пользователь сам автор, он получает FULL независимо от основной роли. Не вычислять «выполнено всё обращение» по урезанному списку исполнителя.

DRAFT видит только автор; CONFIRMED - автор, диспетчер дома, исполнитель в пределах назначения. Создать своё обращение может участник разрешённого дома независимо от основной роли; это не разрешает создавать от чужого лица.

PATCH items содержит полный draft-список: неизвестный/чужой itemId отклоняется, item без ID создаётся, пропущенный draft-item убирается только до confirm. Новая содержательная редакция увеличивает contentVersion, смена sourceText - sourceRevision. Confirm требует хотя бы один пункт. После CONFIRMED source/items отвечают 409 `CASE_ALREADY_CONFIRMED`; сложный пересмотр не входит в обязательный MVP.

Список сортируется стабильно по createdAt DESC, id DESC; limit по умолчанию 20, максимум 50. Cursor opaque, проверяется сервером и привязан к фильтру. Повторная Membership-проверка обязательна на каждой странице; cursor не является полномочием. Изменения между страницами не обещают snapshot-consistency.

## C2. Работы и наблюдения

| Метод и путь | Вход | Результат | Владелец |
|---|---|---|---|
| GET `/houses/{houseId}/executors` | Диспетчер дома | 200 `{items:[{userId,displayName}]}` | S2 |
| POST `/items/{itemId}/assignment` | `{expectedCaseVersion,expectedItemVersion,executorId}`; null снимает назначение | 200 CaseView | S2 |
| POST `/cases/{caseId}/reports` | `{expectedCaseVersion,reportText,targetItemIds}` | 201 `{report,case,analysis:{status}}`, Location отчёта | S2/S4 |
| GET `/reports/{reportId}` | Автор/диспетчер либо допустимый собственный отчёт исполнителя | 200 WorkReportView | S2 |
| POST `/reports/{reportId}/links` | `{expectedCaseVersion,links:[{itemId,contentVersion,expectedWorkRound,workStatus}]}` | 200 CaseView | S2 |
| POST `/items/{itemId}/observations` | `{expectedCaseVersion,expectedItemVersion,contentVersion,workRound,value,source}` | 200 CaseView | S3 |
| POST `/items/{itemId}/administrative-decisions` | `{expectedCaseVersion,expectedItemVersion,action,reason}` | 200 CaseView | S3 |
| GET `/cases/{caseId}/history?cursor=&limit=` | Автор/диспетчер | 200 `{items,nextCursor}` | S3 |

WorkReportView: `{reportId,caseId,actorId,reportText,targetItemIds,createdAt,links}`. Link: `{itemId,contentVersion,workRound,workStatus,confirmedBy,createdAt}`. Время и подтверждающий всегда серверные.

Назначение и новый отчёт допускаются для CONFIRMED. Отчёт может сохранить DISPATCHER дома либо EXECUTOR с назначением на все перечисленные targetItemIds. Target - область допустимой будущей связи, не сама связь и не сообщение о выполнении. После снятия назначения исполнитель не получает новые права из того, что когда-то был автором отчёта.

Сохранение отчёта не меняет workStatus/наблюдение. `links` подтверждает только DISPATCHER; item должен входить в targetItemIds и то же case. WorkStatus здесь только PLANNED или REPORTED_DONE. Пустой набор/дубли отклоняются. Повтор report/item с новым key - 409 LINK_ALREADY_EXISTS; новые сведения добавляются новым отчётом. Весь набор links применяется атомарно.

Наблюдение: value RESTORED/PERSISTS/CANNOT_CHECK; source AFTER_REPORT/INDEPENDENT. Сервер проверяет автора и версии. NOT_RECEIVED - отсутствие ответа, не команда. Administrative action: ARCHIVE или WITHDRAW; причина обязательна. Возврат из архива/отзыва не входит в текущий контракт.

Наблюдения и административные решения относятся к CONFIRMED. До подтверждения автор исправляет draft-список. В рабочем UI назначение исполнителя показывается отдельно от WorkStatus; исходное NOT_ASSIGNED не является источником истины о поле assignedExecutorId.

History item: `{eventId,actorId,eventType,createdAt,details}`. Details - разрешённые поля события с версиями/раундом и ссылкой на report/item, без cookies, секретов, сырого initData и внутренних логов. Сортировка createdAt ASC, id ASC; пагинация обязательна, максимум 50 событий. Исполнитель не получает полную историю чужих пунктов.

## C3. Задача анализа в Java

GET `/reports/{reportId}/suggestions` доступен автору/диспетчеру этого обращения, возвращает `{status,analysisId,result}`. Status: QUEUED, RUNNING, READY, STALE, MANUAL_REQUIRED. analysisId может быть null, если анализ не подключён; result присутствует только для актуального READY, иначе null. READY означает валидное предложение, а не выполненные работы.

Result соответствует Python CompareResponse ниже. В MVP GET не ставит новые задачи. Анализ создаётся после сохранения отчёта атомарно с ним в S4; до S4 состояние явно MANUAL_REQUIRED. STUB не выдаётся за содержательный анализ даже при корректной форме ответа. Фактический mode всегда виден UI.

Python получает полный проверенный snapshot пунктов обращения, включённых Java в сравнение; пропуск внутри этого набора недопустим. UI всегда сохраняет общий разрешённый список и проверяет допустимость links через targetItemIds. Даже подходящая подсказка по пункту вне области отчёта не разрешает связать его.

## Настройки уведомлений и C4

GET `/me/notification-settings` возвращает `{version,enabled}`. PUT того же пути принимает `{expectedSettingsVersion,enabled}` и Idempotency-Key, возвращает новое состояние. Это собственная версия настроек, не caseVersion. Отсутствующая настройка означает enabled=true, version=1 по согласованной политике тестового MVP; opt-out должен быть доступен до рассылки. Реализация - S5/K4.

POST `/max/webhook` принимает ограниченный официальный Update и `X-Max-Bot-Api-Secret`, возвращает 200 только после durable inbox commit. Контекст callback, dedup и обработка несовпадения hash - [implementation.md](implementation.md). Не предполагать универсальный update_id: точный ключ выводится в K5a из реально поддержанного типа события.

## Лимиты и ошибки

SourceText/reportText 1-5000 Unicode code points; category - enum; location 1-300, symptom 1-1000, reason 1-1000; непробельный символ обязателен. Draft допускает 0-20 items, confirm и inference - 1-20. TargetItemIds/links непусты, без дублей, максимум 20. JSON-тело ограничивается до разбора, см. [implementation.md](implementation.md).

Java-валидация должна считать code points, UI - Array.from(text). Проверить ровно граничное число emoji и следующий символ, чтобы UTF-16 длина не создавала другой лимит. Python получает тот же исходный текст без дополнительной нормализации.

Проектный формат ошибки: `application/problem+json` с `{type,title,status,code,requestId}`. Допустимы безопасные fieldErrors без отражения введённых значений. В версии/конфликте можно вернуть currentCaseVersion/currentItemVersion только после проверки доступа. Не возвращать чужой объект в details ошибки.

| HTTP | Code / ситуация | Реакция UI |
|---|---|---|
| 400 | INVALID_JSON, отсутствует/неверен Idempotency-Key, UUID/cursor | Исправить запрос, не повторять автоматически |
| 401 | AUTH_REQUIRED / INVALID_MAX_INIT_DATA | Новый вход; сохранить незавершённый ввод |
| 403 | ACTION_FORBIDDEN / CSRF_INVALID | Показать запрет либо обновить CSRF; не подменять пользователя |
| 404 | RESOURCE_NOT_FOUND для отсутствующего/недоступного ресурса | Не раскрывать существование чужого объекта |
| 409 | CASE_VERSION_CONFLICT / ITEM_VERSION_CONFLICT / STALE_CONTEXT | Перечитать карточку, сохранить локальный ввод, получить новое решение пользователя |
| 409 | IDEMPOTENCY_KEY_REUSED / LINK_ALREADY_EXISTS / CASE_ALREADY_CONFIRMED | Не повторять с новым key ради обхода правила |
| 413 | BODY_TOO_LARGE | Сократить ввод |
| 422 | INVALID_FIELDS / INVALID_TRANSITION | Показать конкретную допустимую коррекцию |
| 429 | RATE_LIMITED | Ограниченный retry чтения с учётом Retry-After |
| 503 | Обязательная БД/сервис недоступен | Сохранение не подтверждено; повтор той же команды с тем же key |

Ошибка optional ML не превращает успешное сохранение в 503. Для cookie POST без сессии и CSRF фильтры могут отклонить CSRF раньше проверки входа; K1 фиксирует фактический приоритет и тестирует его. Для нового login клиент сначала получает `/csrf`, для текущего состояния входа использует GET `/me`.

## Java → Python: существующий контракт

Истина схемы - `ml/app/features/inference/schemas/`; OpenAPI генерируется из `ml/` командой `uv run python -m scripts.export_openapi`. Менять вместе Python, Java `InferenceContract`, общий JSON и потребителей. Сохранённый OpenAPI проверяется тестом против `app.openapi()`.

Примеры: [запрос](../contracts/examples/compare-request.json), [STUB-ответ](../contracts/examples/compare-response.json). В запросе caseId/sourceRevision/reportId/reportText/items; item содержит itemId/contentVersion/workRound/category/location/symptom. Лишние поля, bool вместо версии, неверная длина и повтор itemId отклоняются с 422 без отражения текста.

В ответе те же IDs/версии, modelVersion, mode и ровно одна suggestion на каждый входной item. Relation: REPORTED_DONE, PLANNED, SYMPTOM_PERSISTS, INSUFFICIENT_INFORMATION, UNRELATED, CONFLICT. Mode: STUB, RULES, NLI, MANUAL. Reason: MODEL_NOT_CONFIGURED, ANALYSIS_UNAVAILABLE, ANALYZED. Mode/reason не являются бизнес-статусами.

Evidence `{start,end,quote}` - диапазон `[start,end)` исходного reportText в Unicode code points. Java применяет offsetByCodePoints, JavaScript - Array.from(text). Нельзя нормализовать текст после расчёта offsets. Содержательная relation требует точной цитаты; INSUFFICIENT_INFORMATION может иметь null. STUB/MANUAL возвращают только отказ без evidence.

Java проверяет принадлежность, полноту, дубли, версии и цитату. Проверка актуальности БД выполняется отдельно в S4. Сегодня connect timeout 1 с, read timeout 3 с; fallback MANUAL для всех пунктов, без retry внутри demo-запроса. Надёжные задачи/ограничения ответа - будущий S4, готовность модели - N2/N5.

## Проверенные источники MAX

На 12.09.2026 [валидация MAX](https://dev.max.ru/docs/webapps/validation) описывает исходный initData и HMAC; [подписки](https://dev.max.ru/docs-api/methods/POST/subscriptions) указывают platform-api2.max.ru, HTTPS 443 и secret header. Перед реальной интеграцией сверить актуальность и TLS. Не отключать проверку сертификата и не использовать старые параметры PDF как действующую конфигурацию.
