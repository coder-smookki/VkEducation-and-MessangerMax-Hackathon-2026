# 03. Автор исправляет и подтверждает список

Владельцы - Семён (S1, Java) и Кирилл (K2, UI). Статус: [OpenAPI](author-review.openapi.json) и синтетические request/response готовы; рабочие Java endpoints, сохранение и авторизация предстоят в K1/S1. Спецификация помечена SCHEMA_ONLY. Наличие JSON не означает успешный HTTP-вызов.

## Содержание и готовность

Исходник «На третьем этаже темно, и входная дверь не закрывается» превращается в два отдельных предложения: свет и дверь. Автор может исправить формулировки, добавить или убрать пункт до подтверждения. Неполное предложение с null хранится в редактируемой форме; рабочий PATCH принимает уже заполненные category/location/symptom.

NEEDS_DETAILS и READY_FOR_REVIEW относятся к предложению 02. DRAFT/CONFIRMED - к сохранённому обращению Java. Подтверждается весь текущий непустой список, а не случайный отдельный пункт. У каждого сохранённого пункта свой itemId/contentVersion; у обращения sourceRevision и caseVersion. Модель не имеет команды confirm.

## Правка списка

`PATCH /api/v1/cases/{caseId}/items`: `{expectedCaseVersion,items}`. Сессия, CSRF и Idempotency-Key обязательны. В items - полный список от 0 до 20 пунктов. Item: `{itemId?,category,location,symptom}`.

- Без itemId создаётся новый пункт с UUID Java. Идентификатор proposalId из ответа модели сюда не передаётся.
- Известный itemId сохраняется; содержательная правка увеличивает contentVersion и itemVersion.
- Чужой/неизвестный ID и повтор itemId отклоняются. Пропущенный пункт убирается только в DRAFT.
- expectedCaseVersion проверяется в транзакции. Успех увеличивает caseVersion и возвращает 200 AuthorCaseView.

Примеры: [запрос](examples/review-items-request.json), [ответ](examples/review-items-response.json). SourceText меняется отдельной командой из [docs/api.md](../docs/api.md) и увеличивает sourceRevision; не пытаться менять его скрытым полем в PATCH items.

## Подтверждение

`POST /api/v1/cases/{caseId}/confirm`: `{expectedCaseVersion,sourceRevision}`, та же защита. Java проверяет автора, DRAFT, актуальность обеих версий, непустой корректный список и принадлежность каждого пункта. В одной транзакции сохраняет CONFIRMED, увеличивает caseVersion и itemVersion подтверждённых пунктов, фиксирует историю. ContentVersion и workRound от самого подтверждения не меняются.

Примеры: [запрос](examples/confirm-items-request.json), [ответ](examples/confirm-items-response.json). В примере caseVersion 4 → 5 и itemVersion 1 → 2. Пунктов два; workRound=0, observation=NOT_RECEIVED и progress.allConfirmed=false. Подтверждено содержание, а не выполненные работы.

После CONFIRMED обычный PATCH исходника/списка отклоняется с 409 CASE_ALREADY_CONFIRMED. Сложная редакция принятого обращения не реализуется этой заготовкой. Отзыв ошибочного пункта - отдельная команда с причиной, сохраняющая историю и знаменатель.

## Подтверждённый вход для ML

[confirmed-context.schema.json](confirmed-context.schema.json), [пример](examples/confirmed-context.json): caseId, caseVersion, sourceRevision, houseId, submissionStatus=CONFIRMED, sourceText и полный список Item с itemId/contentVersion/workRound/category/location/symptom.

Python `ConfirmedCaseContext` и Java одноимённый record отклоняют DRAFT, пустой список, неизвестные обязательные данные и дубли itemId. Java `comparison(reportId,reportText)` строит совместимый запрос контракта 05 без потери пунктов.

Такой тип проверяет форму входа. Надёжный источник - только Java, читающая авторизованный согласованный snapshot из БД. Переданный клиентом CONFIRMED не является доказательством подтверждения. Сохранённый контекст и hash будущей job нужно сверять перед выдачей ответа после изменения данных.

## Права, ошибки и повтор

Авторство проверяется отдельно от RESIDENT/DISPATCHER/EXECUTOR: сотрудник может подтвердить список своего обращения как автор. Диспетчер не подтверждает чужой список вместо жителя. Python таких прав не имеет.

Основные отказы: 401 без входа; 403 на недопустимое действие/CSRF; 404 без раскрытия чужого обращения; 409 на конфликт версии, повтор ключа с другим телом или уже подтверждённый список; 422 на неполные поля/недопустимый переход. [Пример 409](examples/review-version-conflict.json). Формат ошибки application/problem+json с безопасными type/title/status/code/requestId.

Одинаковая команда с тем же ключом после повторной проверки прав возвращает прежний результат без нового события/версии. При 409 UI сохраняет локальный ввод, перечитывает актуальную карточку и предлагает автору проверить изменения. Новый key не используется для обхода конфликта.

## Приёмка реализации S1/K2

Проверить настоящие HTTP+PostgreSQL: два пункта, исправление одного, подтверждение, старый extraction-ответ после правки, повтор команды и два конкурирующих подтверждения. Чужой автор, пустой список и подмена IDs не меняют БД. Сверка примеров со схемой, которая работает уже сейчас, эти проверки не заменяет.
