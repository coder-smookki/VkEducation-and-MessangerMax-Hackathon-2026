# Контракты и статус API

## Что отвечает сейчас

| Метод | Адрес | Доступ | Результат |
|---|---|---|---|
| GET | Java `/actuator/health` | Публичный health | HTTP 200 при готовности, без внутренних деталей |
| GET | Java `/api/v1/demo/case` | Только profile demo/local | Фиксированный синтетический пример и рассчитанный прогресс |
| GET | Java `/api/v1/demo/compare` | Только profile demo/local | Настоящий вызов Python на фиксированных данных; STUB или MANUAL при отказе |
| GET | Python `/healthz` | Внутренняя сеть | Состояние процесса и mode=STUB |
| POST | Python `/internal/v1/compare` | Внутренняя сеть | Строгий контракт подсказок |

Любые рабочие маршруты сейчас закрыты Spring Security. Таблица ниже - **план API**, не перечень уже доступных endpoint. Заглушки с ложным 200/201 не создаются.

## Целевые рабочие endpoints

Общий prefix `/api/v1`. UUID передаются строками. Времена - UTC ISO 8601; интерфейс локализует их. Роль и actorId всегда получаются сервером. Все команды с побочным эффектом требуют `Idempotency-Key` (1-200 символов). Ожидаемые версии - положительные числа, workRound допускает 0 до работ.

| Метод и путь | Входные данные | Результат | Задача |
|---|---|---|---|
| POST `/auth/max` | `{initData}` | Серверная сессия после официальной проверки; 401 при ошибке | K1 |
| GET `/me` | Сессия | Внутренний пользователь и разрешённые memberships | K1/S1 |
| POST `/cases` | `{houseId,sourceText}` | 201: сохранённый draft `{caseId,version,sourceRevision}` | S1 |
| GET `/cases?cursor=&limit=` | Сессия, limit 1-50 | Только доступные краткие карточки; opaque cursor | S1 |
| GET `/cases/{caseId}` | Сессия | Текст, пункты, версии, 4 факта каждого, progress | S1 |
| PATCH `/cases/{caseId}/items` | `{expectedVersion,items:[{itemId?,category,location,symptom}]}` | Обновлённая версия; удаление после приёма оформляется отзывом | S1 |
| POST `/cases/{caseId}/confirm` | `{expectedVersion,sourceRevision}` | Подтверждён именно текущий список | S1 |
| POST `/cases/{caseId}/reports` | `{expectedVersion,reportText}` | 201: сохранённый отчёт и состояние задачи анализа | S2/S4 |
| POST `/reports/{reportId}/links` | `{expectedCaseVersion,links:[{itemId,contentVersion,expectedWorkRound,workStatus}]}` | Подтверждённые диспетчером связи и новые раунды | S2 |
| GET `/reports/{reportId}/suggestions` | Сессия | PENDING/READY/MANUAL_REQUIRED + актуальные подсказки | S4 |
| POST `/items/{itemId}/observations` | `{expectedVersion,contentVersion,workRound,value,source}` | Новое наблюдение и progress | S3 |
| POST `/items/{itemId}/administrative-decisions` | `{expectedVersion,action,reason}` | ARCHIVE для диспетчера, WITHDRAW для автора | S3 |
| GET `/cases/{caseId}/history` | Сессия, cursor/limit | Авторизованная история команд | S3 |
| POST `/max/webhook` | Официальный Update + secret header | 200 после durable inbox commit | K5/S5 |

Лимиты: sourceText/reportText до 5000 Unicode code points, до 20 пунктов, location до 300, symptom до 1000, reason до 1000; строки должны содержать непробельный символ. Согласовать расширение лимитов с UI и Python. Приём длинного тела ограничить до десериализации, а не только Bean Validation.

Проектный формат ошибки для S1: `application/problem+json` с `{type,title,status,code,requestId}`. Использовать 400/422 для некорректного ввода, 401 для отсутствующего входа, 403 для запрещённого действия, 404 для недоступного ресурса без раскрытия его существования, 409 для stale version/разного тела при том же key, 429 для ограничения частоты, 503 для временно недоступного обязательного компонента. Потеря optional inference возвращает сохранённый ресурс и состояние ручного разбора.

## Java → Python

**Истина схемы** - `ml/app/contracts.py`; `contracts/inference.openapi.json` генерируется через `python -m scripts.export_openapi` из каталога `ml/`. Изменение модели требует обновления OpenAPI, Java records и обоих примеров. Python-тест сравнивает сохранённый OpenAPI с `app.openapi()`.

Примеры: [запрос](../contracts/examples/compare-request.json), [ответ STUB](../contracts/examples/compare-response.json).

Запрос содержит caseId, sourceRevision, reportId, reportText и items. Каждый item содержит стабильный itemId, contentVersion, workRound, category, location, symptom. Лишние поля, bool вместо версии, пустой/слишком длинный текст и повтор itemId отклоняются с 422 без отражения исходного текста.

Ответ содержит те же caseId/sourceRevision/reportId, modelVersion, mode и suggestions **ровно по одному на каждый входной item**. Пропуск пункта не допускается, в том числе когда анализ не удался.

`Relation`: `REPORTED_DONE`, `PLANNED`, `SYMPTOM_PERSISTS`, `INSUFFICIENT_INFORMATION`, `UNRELATED`, `CONFLICT`. Это интерпретации текста, не команды изменения бизнес-статуса.

`Evidence = {start,end,quote}` - диапазон `[start,end)` в Unicode code points исходного reportText. Java использует `offsetByCodePoints`, JavaScript при выделении должен использовать `Array.from(text)`. Не нормализовать текст после вычисления диапазона. Непустая интерпретация требует фрагмента; `INSUFFICIENT_INFORMATION` может иметь `evidence=null`.

`Mode`: STUB, RULES, NLI, MANUAL. STUB/MANUAL допускают только отказ с пустым evidence. В Java проверяются принадлежность, версии, отсутствие дублей, полнота, границы и точность цитаты. Даже валидный ответ не записывает рабочие поля автоматически.

Сейчас timeout подключения 1 с и чтения 3 с. При недоступности или некорректном ответе Java возвращает MANUAL для всех пунктов. Повторных вызовов внутри demo-запроса нет. Надёжная очередь, лимит объёма ответа и управление нагрузкой - задача S4/N5. Health Java не зависит от необязательной модели.

## Контракт MAX

Mini App передаёт Java исходный `window.WebApp.initData`; `initDataUnsafe` не используется для проверки. Подпись, повтор параметров, auth_date и допустимые часы проверяются до формирования пользователя. Политику сессии и CSRF реализовать в K1; нельзя просто отключить защиту всех маршрутов.

На 12.09.2026 [официальная страница подписок](https://dev.max.ru/docs-api/methods/POST/subscriptions) указывает `platform-api2.max.ru`, HTTPS webhook на 443 и `X-Max-Bot-Api-Secret`. Перед K5 повторно сверить endpoint и требования TLS. Не отключать проверку сертификата Java и не менять глобальное trust store ради быстрого запуска. Внешних запросов с токеном из этого каркаса пока нет.
