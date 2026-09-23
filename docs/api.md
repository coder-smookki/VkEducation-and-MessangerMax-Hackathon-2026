# Контракты и статус API

Обновлено: 22.09.2026. Раздел «Сейчас» и внутренний inference API описывают код; рабочий Java REST - **согласуемый контракт будущего MVP** для параллельной реализации. Новые рабочие endpoints ещё закрыты. REST-примеры Java не являются результатами HTTP-вызовов. Подробности: [пять контрактов](../contracts/README.md).

## Сейчас

| Метод | Адрес | Доступ | Результат |
|---|---|---|---|
| GET | Java `/actuator/health` | Health | При готовности 200, без внутренних деталей |
| GET | Java `/api/v1/demo/case` | Только demo/local | Фиксированный synthetic пример |
| GET | Java `/api/v1/demo/compare` | Только demo/local | Реальный вызов Python, STUB либо MANUAL |
| GET | Python `/healthz` | Внутренняя сеть | Процесс и состояние Chroma |
| POST | Python `/internal/v1/compare` | Внутренняя сеть | Строгий контракт подсказок |
| POST | Python `/internal/v1/extract-items` | Внутренняя сеть | STUB предложения пунктов; фактическое извлечение не подключено |
| POST | Python `/internal/v1/clarify-items` | Внутренняя сеть | RULES: недостающие поля, вопросы только автору |
| POST | Python `/internal/v1/similar-cases` | Внутренняя сеть | Сходство отдельных пунктов: query/candidates/limit из проверенной Java-выборки |

`DemoCase` и `Comparison` из `frontend/src/api/client.ts` - типы примера, не готовые DTO авторизованного приложения. Не расширять `/demo/**` рабочими командами.

## Общие правила целевого REST

Prefix `/api/v1`. UUID - строки, времена - UTC ISO 8601. Версии - строгие целые положительные числа, workRound допускает 0. Backend выдаёт `caseVersion` из `service_case.version`, `itemVersion` из `case_item.version`. Клиент не увеличивает их самостоятельно.

Любая команда предметной области требует `Idempotency-Key` длиной 1-200 ASCII-символов без пробелов/управляющих символов. Для UI достаточно UUID. Область уникальности - actor + operation + key; target и каноническое тело входят в request_hash. Повтор передаёт прежние версии/тело; тот же key для другого target в той же operation даёт конфликт. Actor и роль берутся сервером. Обработка конкуренции и replay - [implementation.md](implementation.md).

`/auth/max`, `/auth/logout` и webhook не используют `processed_command` бизнес-операций: у них собственный протокол входа/выхода или event key. Cookie-команды, включая вход/выход, защищены CSRF; webhook использует отдельную проверку секрета.

Все команды обращения, кроме create, принимают `expectedCaseVersion`. Прямая команда пункта также принимает `expectedItemVersion`. ContentVersion и workRound проверяются отдельно для links/observations. Термин `expectedVersion` без указания сущности в новых DTO не использовать.

## C1. Вход и черновик

Реальный `CaseView` на исходном dev содержит caseId/houseId/authorId/submissionStatus/caseVersion/sourceRevision/sourceText/items/progress/createdAt. S-01: visibility (сейчас всегда FULL) и assignedExecutorId (сейчас всегда null, назначений нет) добавлены в Java-record; CaseViewContractTest проверяет, что review/confirm примеры проходят круговое чтение Java без потерь. Новые поля ниже (receipt, nextAction, allowedActions, displayNumber) - целевые расширения, которых в текущем record нет. `author-review.openapi.json` также SCHEMA_ONLY. На S-01 синхронизировать новые fixtures/схемы, на S-02 реализовать и проверить HTTP; не приводить UI к виду успешного ответа через подставленные константы.

Правка/подтверждение списка дополнительно описаны машинно в [author-review.openapi.json](../contracts/author-review.openapi.json), статус SCHEMA_ONLY. [Контракт 03](../contracts/03-author-review.md) объясняет уровни NEEDS_DETAILS → READY_FOR_REVIEW → авторское CONFIRMED. Неполные proposals с null остаются в форме; PATCH принимает полные пункты. READY_FOR_REVIEW не означает готовые работы.

| Метод и путь | Вход | Успешный результат | Владелец |
|---|---|---|---|
| GET `/csrf` | До/после входа, та же сессия | 200 `{headerName,token}`; Cache-Control: no-store | S-02 |
| POST `/auth/max` | `{initData}`, CSRF | 200 MeView + серверная cookie; после входа обновить CSRF | S-02 |
| POST `/auth/logout` | CSRF | 204, сессия недействительна | S-02 |
| GET `/me` | Сессия | 200 MeView | S-02 |
| POST `/cases` | `{houseId,sourceText}` | 201 CaseView, Location карточки | S-02 |
| GET `/cases?houseId=&cursor=&limit=` | Сессия, необязательный фильтр дома | 200 `{items,nextCursor}` | S-02 |
| GET `/cases/{caseId}` | Сессия | 200 CaseView в доступной проекции | S-02 |
| PATCH `/cases/{caseId}/source` | `{expectedCaseVersion,sourceText}` | 200 CaseView с новой sourceRevision, только DRAFT | S-02 |
| PATCH `/cases/{caseId}/items` | `{expectedCaseVersion,items:[{itemId?,category,location,symptom}]}` | 200 CaseView, полная замена draft-списка | S-02 |
| POST `/cases/{caseId}/confirm` | `{expectedCaseVersion,sourceRevision}` | 200 CaseView, CONFIRMED | S-02 |

MeView: `{userId,displayName,memberships:[{houseId,address,role}]}`. displayName может быть null. Роль относится к конкретному Membership и не является разрешением действовать во всех домах. Пустой memberships - проверенный аккаунт без доступа, а не автоматическая роль RESIDENT.

CaseView: `{caseId,houseId,authorId,submissionStatus,caseVersion,sourceRevision,sourceText,visibility,items,progress,createdAt}`. Item: `{itemId,itemVersion,contentVersion,category,location,symptom,workRound,workStatus,observation,administrativeStatus,administrativeReason,assignedExecutorId}`. Nullable: причина для ACTIVE и отсутствие назначения. Четыре факта/enum определены в [domain.md](domain.md).

`visibility=FULL` для автора и диспетчера, которому доступно подтверждённое обращение. Исполнитель видит `ASSIGNED_ITEMS`: только назначенные ему пункты, sourceText=null, progress=null, без общей истории/полного текста чужих отчётов. Если пользователь сам автор, он получает FULL независимо от основной роли. Не вычислять «выполнено всё обращение» по урезанному списку исполнителя.

DRAFT видит только автор; CONFIRMED - автор, диспетчер дома, исполнитель в пределах назначения. Создать своё обращение может участник разрешённого дома независимо от основной роли; это не разрешает создавать от чужого лица.

PATCH items содержит полный draft-список: неизвестный/чужой itemId отклоняется, item без ID создаётся, пропущенный draft-item убирается только до confirm. Новая содержательная редакция увеличивает contentVersion, смена sourceText - sourceRevision. Confirm требует хотя бы один пункт. После CONFIRMED source/items отвечают 409 `CASE_ALREADY_CONFIRMED`; сложный пересмотр не входит в обязательный MVP.

Список сортируется стабильно по createdAt DESC, id DESC; limit по умолчанию 20, максимум 50. Cursor opaque, проверяется сервером и привязан к фильтру. Повторная Membership-проверка обязательна на каждой странице; cursor не является полномочием. Изменения между страницами не обещают snapshot-consistency.

## C2. Работы и наблюдения

Назначения EXECUTOR (`executors`/`assignment`) - Should, не условие основного выпуска. В Must отчёт ведёт диспетчер. Урезанную проекцию исполнителя реализовать и проверить до включения его кабинета; enum в БД недостаточно.

| Метод и путь | Вход | Результат | Владелец |
|---|---|---|---|
| GET `/houses/{houseId}/executors` | Диспетчер дома | 200 `{items:[{userId,displayName}]}` | S-04 |
| POST `/items/{itemId}/assignment` | `{expectedCaseVersion,expectedItemVersion,executorId}`; null снимает назначение | 200 CaseView | S-04 |
| POST `/cases/{caseId}/reports` | `{expectedCaseVersion,reportText,targetItemIds}` | 201 `{report,case,analysis:{status}}`, Location отчёта | S-04 |
| GET `/reports/{reportId}` | Автор/диспетчер либо допустимый собственный отчёт исполнителя | 200 WorkReportView | S-04 |
| POST `/reports/{reportId}/links` | `{expectedCaseVersion,links:[{itemId,expectedItemVersion,contentVersion,expectedWorkRound,workStatus}]}` | 200 CaseView | S-04 |
| POST `/items/{itemId}/observations` | `{expectedCaseVersion,expectedItemVersion,contentVersion,workRound,value,source}` | 200 CaseView | S-05 |
| POST `/items/{itemId}/administrative-decisions` | `{expectedCaseVersion,expectedItemVersion,action,reason}` | 200 CaseView | S-05 |
| GET `/cases/{caseId}/history?cursor=&limit=` | Автор/диспетчер | 200 `{items,nextCursor}` | S-05 |

WorkReportView: `{reportId,caseId,actorId,reportText,targetItemIds,createdAt,links}`. Link: `{itemId,contentVersion,workRound,workStatus,confirmedBy,createdAt}`. Время и подтверждающий всегда серверные.

Назначение и новый отчёт допускаются для CONFIRMED. Отчёт может сохранить DISPATCHER дома либо EXECUTOR с назначением на все перечисленные targetItemIds. Target - область допустимой будущей связи, не сама связь и не сообщение о выполнении. После снятия назначения исполнитель не получает новые права из того, что когда-то был автором отчёта.

Сохранение отчёта не меняет workStatus/наблюдение. `links` подтверждает только DISPATCHER; item должен входить в targetItemIds и то же case. WorkStatus здесь только PLANNED или REPORTED_DONE. Пустой набор/дубли отклоняются. Повтор report/item с новым key - 409 LINK_ALREADY_EXISTS; новые сведения добавляются новым отчётом. Весь набор links применяется атомарно.

Наблюдение: value RESTORED/PERSISTS/CANNOT_CHECK; source AFTER_REPORT/INDEPENDENT. Сервер проверяет автора и версии. NOT_RECEIVED - отсутствие ответа, не команда. Administrative action: ARCHIVE или WITHDRAW; причина обязательна. Возврат из архива/отзыва не входит в текущий контракт.

Наблюдения и административные решения относятся к CONFIRMED. До подтверждения автор исправляет draft-список. В рабочем UI назначение исполнителя показывается отдельно от WorkStatus; исходное NOT_ASSIGNED не является источником истины о поле assignedExecutorId.

History item: `{eventId,actorId,eventType,createdAt,details}`. Details - разрешённые поля события с версиями/раундом и ссылкой на report/item, без cookies, секретов, сырого initData и внутренних логов. Сортировка createdAt ASC, id ASC; пагинация обязательна, максимум 50 событий. Исполнитель не получает полную историю чужих пунктов.

## C3. Задача анализа в Java

GET `/reports/{reportId}/suggestions` доступен автору/диспетчеру этого обращения, возвращает `{status,analysisId,result}`. Status: QUEUED, RUNNING, READY, STALE, MANUAL_REQUIRED. analysisId может быть null, если анализ не подключён; result присутствует только для актуального READY, иначе null. READY означает валидное предложение, а не выполненные работы.

Result соответствует Python CompareResponse ниже. В MVP GET не ставит новые задачи. Анализ создаётся после сохранения отчёта атомарно с ним в S-06; до S-06 состояние явно MANUAL_REQUIRED. STUB не выдаётся за содержательный анализ даже при корректной форме ответа. Фактический mode всегда виден UI.

Python получает полный проверенный snapshot пунктов обращения, включённых Java в сравнение; пропуск внутри этого набора недопустим. UI всегда сохраняет общий разрешённый список и проверяет допустимость links через targetItemIds. Даже подходящая подсказка по пункту вне области отчёта не разрешает связать его.

## Настройки уведомлений и C4

GET `/me/notification-settings` возвращает `{version,enabled}`. PUT того же пути принимает `{expectedSettingsVersion,enabled}` и Idempotency-Key, возвращает новое состояние. Это собственная версия настроек, не caseVersion. Отсутствующая настройка означает enabled=true, version=1 по согласованной политике тестового MVP; opt-out должен быть доступен до рассылки. Реализация - S-05/S-07/K-04.

POST `/max/webhook` принимает ограниченный официальный Update и `X-Max-Bot-Api-Secret`, возвращает 200 только после durable inbox commit. Контекст callback, dedup и обработка несовпадения hash - [implementation.md](implementation.md). Не предполагать универсальный update_id: точный ключ выводится в K-07a из реально поддержанного типа события.

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

Ошибка optional ML не превращает успешное сохранение в 503. Для cookie POST без сессии и CSRF фильтры могут отклонить CSRF раньше проверки входа; S-02 фиксирует фактический приоритет и тестирует его. Для нового login клиент сначала получает `/csrf`, для текущего состояния входа использует GET `/me`.

## Java → Python: существующий контракт

Общий каталог [01-05](../contracts/README.md) добавляет извлечение, уточнение у автора и сравнение похожих обращений. Типы новых операций находятся в `ml/app/features/intake/contracts.py` и Java `IntakeContract`, runtime - в `ml/app/features/intake/service.py` и `InferenceClient`. Контекст после подтверждения экспортируется в [confirmed-context.schema.json](../contracts/confirmed-context.schema.json). Формат `/compare` ниже сохранён.

POST-тела Python ограничены 512 KiB до JSON-разбора, включая chunked. Ошибка 413 - `{detail:"REQUEST_TOO_LARGE"}`. Новые операции при неверном входе возвращают 422 `{detail:"INVALID_INTAKE_REQUEST"}`; `/compare` сохраняет `{detail:"INVALID_COMPARE_REQUEST"}`. OpenAPI описывает эти фактические ответы, тест проверяет их соответствие схеме.

Истина схемы inference - `ml/app/features/inference/schemas/`, а intake-контрактов - `ml/app/features/intake/contracts.py`; OpenAPI генерируется из `ml/` командой `uv run python -m scripts.export_openapi`. Менять вместе Python, Java-контракты, общий JSON и потребителей. Сохранённый OpenAPI проверяется тестом против `app.openapi()`.

Примеры: [запрос](../contracts/examples/compare-request.json), [STUB-ответ](../contracts/examples/compare-response.json). В запросе caseId/sourceRevision/reportId/reportText/items; item содержит itemId/contentVersion/workRound/category/location/symptom. Лишние поля, bool вместо версии, неверная длина и повтор itemId отклоняются с 422 без отражения текста.

В ответе те же IDs/версии, modelVersion, mode и ровно одна suggestion на каждый входной item. Relation: REPORTED_DONE, PLANNED, SYMPTOM_PERSISTS, INSUFFICIENT_INFORMATION, UNRELATED, CONFLICT. Mode: STUB, RULES, NLI, MANUAL. Reason: MODEL_NOT_CONFIGURED, ANALYSIS_UNAVAILABLE, ANALYZED. Mode/reason не являются бизнес-статусами.

Evidence `{start,end,quote}` - диапазон `[start,end)` исходного reportText в Unicode code points. Java применяет offsetByCodePoints, JavaScript - Array.from(text). Нельзя нормализовать текст после расчёта offsets. Содержательная relation требует точной цитаты; INSUFFICIENT_INFORMATION может иметь null. STUB/MANUAL возвращают только отказ без evidence.

Java проверяет принадлежность, полноту, дубли, версии и цитату. Проверка актуальности БД выполняется отдельно в S-06. Сегодня connect timeout 1 с, read timeout 3 с; fallback MANUAL для всех пунктов, без retry внутри demo-запроса. Надёжные задачи/ограничения ответа - будущий S-06, безопасность анализатора и независимая оценка - S-06/Q-06.

## Проверенные источники MAX

На 12.09.2026 [валидация MAX](https://dev.max.ru/docs/webapps/validation) описывает исходный initData и HMAC; [подписки](https://dev.max.ru/docs-api/methods/POST/subscriptions) указывают platform-api2.max.ru, HTTPS 443 и secret header. Перед реальной интеграцией сверить актуальность и TLS. Не отключать проверку сертификата и не использовать старые параметры PDF как действующую конфигурацию.

## Дополнение для макетов 22.09: обязательные недостающие команды

Все пути ниже **TARGET / TODO**, не существующие endpoints. Вместе реализуются DTO, SQL, авторизация, тесты и UI. Команды используют общие CSRF/Idempotency-Key/expectedCaseVersion и возвращают актуальную CaseView.

| Метод и путь относительно /api/v1 | Вход / права | Ответ / экран | Задача |
|---|---|---|---|
| POST `/memberships/claim` | `{invitationCode}`; проверенная сессия; лимит попыток | 200 MeView; M03 → M04/список | S-02/K-02 |
| POST `/cases/{caseId}/acceptance` | `{expectedCaseVersion}`; диспетчер дома, CONFIRMED | 200 CaseView, receipt.acceptedAt/acceptedBy; D03/A10 | S-03 |
| PUT `/items/{itemId}/next-action` | `{expectedCaseVersion,expectedItemVersion,text,ownerId,updateDueAt}`; диспетчер дома | 200 CaseView; D04/A14 | S-03 |

`invitationCode` не содержит назначаемые клиентом роль/дом. В БД хранится hash одноразового высокоэнтропийного кода, срок и разрешённое назначение RESIDENT, потребление атомарно. Повтор с тем же Idempotency-Key допускает безопасное восстановление результата после проверки прав. Тот же код с другим actor не переиспользуется. Повышенные роли задаёт технический оператор согласованной серверной операцией, не этот endpoint. Не раскрывать чужое членство в тексте ошибки. Лимит длины кода и публичные error codes закрепить в машинной схеме до UI-интеграции.

### Поля для единой экранной модели

- `displayNumber` - серверный человекочитаемый номер, не UUID и не полномочие; до реализации UI показывает короткую безопасную подпись существующего ID, не постоянное №124.
- `receipt: {confirmedAt, acceptedAt, acceptedBy}`: confirmedAt после авторского подтверждения; acceptedAt/acceptedBy nullable до приёма. Приём не меняет workStatus/observation. Имя сотрудника в проекции доступно только разрешённому читателю.
- `items[].nextAction: {text,ownerId,ownerDisplayName,updateDueAt,updatedAt} | null`. text 1..1000 Unicode code points. ownerId - активный диспетчер этого дома. updateDueAt - UTC timestamp; UI показывает местную дату и зону. При записи дата не в прошлом; просроченный существующий план отображается честно до обновления. Нет искусственного переноса срока вперёд.
- `allowedActions` - серверный список допустимых действий для текущего actor/состояния, удобство интерфейса; сервер повторно проверяет команду. Не источник полномочий из клиента.
- Очередь строит Java: непринятые CONFIRMED; активные пункты без положительного наблюдения; ожидание ответа; архивные пункты отдельным фильтром. Фильтры/курсор проверяются сервером. Обращение может содержать активные и архивные пункты одновременно.

После архива nextAction сохраняется как исторический план и так подписывается. Текущий архив относится к **одному itemId**. Нет команды массового архива всего обращения в Must. D09/D10 адаптированы под этот контракт. Архив не меняет observed result, не удаляет исходный пункт и не уменьшает progress.total.

### Переход бот → Mini App

Публичный origin https://hackathon.lumnex.ru/. Формат MAX открытия - ссылка бота с `startapp`; конкретное имя бота берётся из конфигурации. Payload задаёт адресуемый caseId или непрозрачный серверный ключ, но не даёт прав. После входа GET карточки повторно проверяет доступ. `?appeal=124` из первоначального макета не переносить в рабочий протокол. [MAX Bridge](https://dev.max.ru/docs/webapps/bridge).

Webhook имеет отдельный корневой путь `/max/webhook`, собственную проверку секрета/размера тела/dedup. Все ответы бота об изменении состояния строятся из результата тех же Java application-команд, что обслуживают Mini App.

### Согласование перед включением

Семён обновляет машинную REST-схему/Java DTO и валидируемые примеры, Кирилл - типы/формы, София - HTTP и доступ. До этого новые строки имеют статус TARGET. Таблица [screens.md](screens.md) связывает каждый экран с контрактом, задачей и проверкой. Python-схема не описывает REST-команды Java.

### Фильтр диспетчерской очереди (TARGET)

На GET `/cases` необязательный `queueState`: NEEDS_ACCEPTANCE, NEEDS_ACTION, AWAITING_AUTHOR, ARCHIVED. Только DISPATCHER в разрешённом houseId; неизвестное значение 422. NEEDS_ACCEPTANCE: CONFIRMED и нет acceptedAt. NEEDS_ACTION: принят и есть ACTIVE пункт без RESTORED, для которого нет актуального nextAction либо наблюдение PERSISTS/истёк срок обновления. AWAITING_AUTHOR: есть ACTIVE REPORTED_DONE с NOT_RECEIVED/CANNOT_CHECK. ARCHIVED: есть ARCHIVED пункт. Категории могут пересекаться, это фильтры по фактам, а не новый взаимоисключающий статус. Cursor привязан к houseId/queueState. Без фильтра серверный список не раскрывает DRAFT другого автора. Схему параметра и fixtures закрепить на S-01/S-03.


## S-01, 23.09: сверка полей и несовместимости

Проверено на реальном коде (`CaseView`, `CaseItemView`, `CasePage`, `CaseException`), а не по макетам.

| Поле экрана | В БД | В Java | Решение |
|---|---|---|---|
| `visibility` | не нужно | **добавлено** (всегда FULL) | S-01 |
| `assignedExecutorId` | нет | **добавлено** (всегда null) | назначения - S-04 |
| `receipt.confirmedAt` | **есть**: `item_revision.confirmed_at` | нет | вывести в S-02, миграция не нужна |
| `receipt.acceptedAt/acceptedBy` | нет | нет | миграция S-03 |
| `items[].nextAction` | нет | нет | миграция S-03 |
| `displayNumber` | нет | нет | миграция S-03 |
| порядок пунктов | нет | сортировка по случайному UUID | миграция S-03 |
| `allowedActions` | не нужно | нет | вычисляется в Java, S-02 |
| `queueState` | - | - | параметр фильтра, S-03 |

Найденные несовместимости и что сделано:

1. Схема требовала `visibility`, примеры содержали `visibility` и `assignedExecutorId`, Java их не отдавала. Python сверял пример со схемой, Java - ни с чем. Поля добавлены в record; `CaseViewContractTest` читает общие примеры через `CaseView`/`CasePage` и сравнивает круговой прогон.
2. Коды ошибок Java расходились с этим документом: `NOT_FOUND`, `FORBIDDEN`, `VERSION_CONFLICT`. Выровнены на `RESOURCE_NOT_FOUND`, `ACTION_FORBIDDEN`, `CASE_VERSION_CONFLICT`. Фронтенд их ещё не использовал.
3. Пункты возвращаются в порядке случайного UUID: `ORDER BY i.id`. Из-за этого `patchItemsReplacesDraftListWithoutFictitiousRevisions` падал примерно в половине запусков; тест исправлен на поиск по категории. Сам дефект остаётся: автор видит пункты не в том порядке, в котором вводил. Severity P2, чинится колонкой позиции в S-03.
4. Java читает неизвестные поля молча, без ошибки. Поэтому расхождение схемы и record не было заметно до кругового теста; по той же причине старые сохранённые ответы в `processed_command` останутся читаемыми после добавления полей.

Новые машинные контракты: `MeView`, `Membership`, `CasePage` и пути `/me`, `/cases` со статусом SCHEMA_ONLY; `CaseReceipt`, `NextAction`, `TargetCaseView`, `TargetCaseItemView` и команды приёма, следующего шага, обмена приглашения и административного решения по одному пункту - со статусом TARGET. Примеры: me-response, me-no-access, case-list-response, case-list-empty, unauthorized-401, forbidden-403, not-found-404, invalid-fields-422, case-view-target.
