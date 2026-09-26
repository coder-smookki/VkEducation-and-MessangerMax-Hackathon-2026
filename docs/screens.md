# Все экраны и их backend-контракты

## Что доступно в runtime

| Группа | Реальная реализация | Предел проверки |
|---|---|---|
| M01..M05, M07/M08 | Вход/сессия, приглашение, контекст из /me, настройки, недоступная карточка | MAX Bridge в локальных тестах синтетический |
| A01..A10 | Создание, ручной список, правка исходника/пунктов, review/confirm, квитанция | A04/A05: автоматических extract/clarify и вопросов модели нет; используются обязательные поля и ручное уточнение только автором |
| A11..A18 | Три наблюдения автора, отдельный остаток, история, отзыв с причиной | Настоящие Java/PostgreSQL, не физический MAX |
| D01..D10 | Очередь/фильтры, приём, шаг, отчёт, цитаты, явные связи, архив пункта | Ответственный nextAction - текущий диспетчер; справочника других сотрудников нет |
| X01..X10 | Loading, empty, denied, ошибки, pending, неизвестный исход, конфликт и повтор входа | Автоматизированные UI fixtures плюс реальные negative API checks |
| R01..R04, C01..C06 | Адаптивные компоненты, темы, большой текст, широкая очередь-таблица и отчёт в две колонки | Chromium 320/390/768/1440; реальные устройства ожидают проверки |
| M06 | Экран справки с настоящими support/privacy данными не опубликован | Нужны согласованные контакты/политика; не подставлять конкурсную поддержку как поддержку продукта |
| B01..B04 | Серверная логика чата существует | Настоящий MaxSender отсутствует, G2 не пройден |
| S01/S02 | Should выключен | UI поиска похожих обращений не заявлен |

Маршруты runtime: `/#/`, `/#/access`, `/#/new`, `/#/cases/:caseId`, редактор `/edit` или `/source`, подтверждение `/review`, действие `/items/:itemId/:action`, отчёты `/reports/new` и `/reports/:reportId`. Один маршрут покрывает несколько состояний матрицы. Корневую ссылку организаторам передавать без hash: `https://hackathon.lumnex.ru/`.

62 ID, включая ошибки/адаптивность/бот/библиотеку/расширение. Один ID не требует отдельного route или endpoint. Все пути API ниже относительно `/api/v1`, кроме MAX webhook. Буква A в screen ID не обозначает QA-A.

Статус на 26.09: таблицы ниже остаются полной матрицей проектных состояний. Основной runtime уже реализован; точное покрытие и ограничения приведены ниже и в [протоколе](qa/kirill-2026-09-26.md). Статус серверной операции указан в машинной схеме x-implementation-status; PNG не доказывает готовность.

Каждый экран также обязан обрабатывать loading/denied/error/смену контекста; командный экран - pending/409/unknown outcome. Все реальные значения приходят с сервера; synthetic placeholders не замена API.

## Общие

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| M01 Запуск и восстановление входа<br>[dark](../design/images/mobile/M01_launch_dark.png) / [light](../design/images/mobile/M01_launch_light.png) | Вход: initData → auth/max, затем /me | Сессия, memberships; нет доверия initDataUnsafe | S-02/K-01; QA-A01 |
| M02 Нет доступа к дому<br>[dark](../design/images/mobile/M02_no-house-access_dark.png) / [light](../design/images/mobile/M02_no-house-access_light.png) | GET /me → нет действующих memberships | Проверенный пользователь без доступа; данные дома скрыты | S-02/K-01; QA-A02 |
| M03 Ввод приглашения<br>[dark](../design/images/mobile/M03_invitation_dark.png) / [light](../design/images/mobile/M03_invitation_light.png) | POST /memberships/claim | invitationCode; безопасное одноразовое потребление | S-02/K-02; QA-A04 |
| M04 Выбор доступного дома и контекста<br>[dark](../design/images/mobile/M04_available-contexts_dark.png) / [light](../design/images/mobile/M04_available-contexts_light.png) | GET /me, выбор существующего context | Только memberships пользователя; role не изменяется | S-02/K-01; QA-A02 |
| M05 Настройки<br>[dark](../design/images/mobile/M05_settings_dark.png) / [light](../design/images/mobile/M05_settings_light.png) | Локальные theme/text prefs | System/light/dark; normal/large; не API прав/уведомлений | K-01/K-06; QA-E12 |
| M06 Помощь и о сервисе<br>[dark](../design/images/mobile/M06_help_dark.png) / [light](../design/images/mobile/M06_help_light.png) | Публичная фактическая справка | Контакты поддержки/политика должны быть заполнены | K-08; QA-R09 |
| M07 Сессия закончилась<br>[dark](../design/images/mobile/M07_session-expired_dark.png) / [light](../design/images/mobile/M07_session-expired_light.png) | 401 → повторный MAX-вход | Старый auth context сбрасывается; ввод не публикуется | S-02/K-05; QA-A01 |
| M08 Карточка недоступна<br>[dark](../design/images/mobile/M08_unavailable-card_dark.png) / [light](../design/images/mobile/M08_unavailable-card_light.png) | 403/404 чтения карточки | Без номера/адреса чужой сущности | S-02/K-06; QA-A02 |

## Автор

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| A01 Мои обращения — пусто<br>[dark](../design/images/mobile/A01_my-appeals-empty_dark.png) / [light](../design/images/mobile/A01_my-appeals-empty_light.png) | GET /cases → items=[] | Успешная пустота отличается от load error | S-02/K-02; QA-E01 |
| A02 Мои обращения — список<br>[dark](../design/images/mobile/A02_my-appeals_dark.png) / [light](../design/images/mobile/A02_my-appeals_light.png) | GET /cases с пагинацией | CaseView/progress; synthetic №124 не константа | S-02/K-02; QA-E07 |
| A03 Новое обращение<br>[dark](../design/images/mobile/A03_new-appeal_dark.png) / [light](../design/images/mobile/A03_new-appeal_light.png) | POST /cases; PATCH source | sourceText/houseId; actor из сессии | S-02/K-02; QA-E01 |
| A04 Уточнение только автора<br>[dark](../design/images/mobile/A04_author-clarification_dark.png) / [light](../design/images/mobile/A04_author-clarification_light.png) | Контракт02 через Java; PATCH source/items | missing fields, recipient AUTHOR; sourceRevision | S-06/K-02; QA-E01 |
| A05 Проверка предложенных пунктов<br>[dark](../design/images/mobile/A05_review-proposed-items_dark.png) / [light](../design/images/mobile/A05_review-proposed-items_light.png) | Контракты01/03; PATCH items → confirm | proposals nullable; сохранённый полный items[]; версии | S-02/K-02; QA-E02 |
| A06 Редактирование одного пункта<br>[dark](../design/images/mobile/A06_edit-draft-item_dark.png) / [light](../design/images/mobile/A06_edit-draft-item_light.png) | PATCH /cases/{id}/items | category/location/symptom одного пункта в полном draft-списке | S-02/K-02; QA-E02 |
| A07 Ручной список без подсказки<br>[dark](../design/images/mobile/A07_manual-items_dark.png) / [light](../design/images/mobile/A07_manual-items_light.png) | PATCH items ручным вводом | STUB/MANUAL; исходник и все пункты доступны | S-02/K-02; QA-E10 |
| A08 Список подтверждён — обращение сохранено<br>[dark](../design/images/mobile/A08_appeal-saved_dark.png) / [light](../design/images/mobile/A08_appeal-saved_light.png) | POST confirm → GET case | CONFIRMED/confirmedAt [расширение]; принятие отдельно | S-02/S-03/K-02; QA-E02 |
| A09 Карточка до приёма<br>[dark](../design/images/mobile/A09_awaiting-acceptance_dark.png) / [light](../design/images/mobile/A09_awaiting-acceptance_light.png) | GET case: acceptedAt=null | Содержание подтверждено, сотрудник ещё не принял | S-03/K-02; QA-E03 |
| A10 Карточка после приёма<br>[dark](../design/images/mobile/A10_accepted-appeal_dark.png) / [light](../design/images/mobile/A10_accepted-appeal_light.png) | GET case: receipt + nextAction | acceptedBy/At; действие/owner/дата обновления | S-03/K-02; QA-E03 |
| A11 Есть отчёт по свету — ждём автора<br>[dark](../design/images/mobile/A11_light-awaiting-observation_dark.png) / [light](../design/images/mobile/A11_light-awaiting-observation_light.png) | GET case/report после link | REPORTED_DONE текущего пункта; observation NOT_RECEIVED | S-04/K-04; QA-E05 |
| A12 Наблюдение по одному пункту<br>[dark](../design/images/mobile/A12_item-observation_dark.png) / [light](../design/images/mobile/A12_item-observation_light.png) | POST /items/{id}/observations | value/source, expectedCase/ItemVersion, contentVersion/round | S-05/K-04; QA-E06 |
| A13 Частичный результат — 1 из 2<br>[dark](../design/images/mobile/A13_partial-result_dark.png) / [light](../design/images/mobile/A13_partial-result_light.png) | GET case / progress | total2/confirmed1/remaining1; дверь и nextAction | S-05/K-04; QA-E07 |
| A14 Подробности двери<br>[dark](../design/images/mobile/A14_door-details_dark.png) / [light](../design/images/mobile/A14_door-details_light.png) | GET case и разрешённая проекция пункта | Дверь имеет собственный статус/шаг/версии | S-03/K-04; QA-E07 |
| A15 История обращения<br>[dark](../design/images/mobile/A15_appeal-history_dark.png) / [light](../design/images/mobile/A15_appeal-history_light.png) | GET /cases/{id}/history | Пагинация; event actor/time/target/round; старые наблюдения | S-05/K-04; QA-E09 |
| A16 По обоим пунктам всё в порядке<br>[dark](../design/images/mobile/A16_both-observed-ok_dark.png) / [light](../design/images/mobile/A16_both-observed-ok_light.png) | GET case после второй положительной проверки | total2/confirmed2/allConfirmed=true; отдельная поздняя ветка | S-05/K-04; QA-E08 |
| A17 Отзыв своего пункта<br>[dark](../design/images/mobile/A17_withdraw-item_dark.png) / [light](../design/images/mobile/A17_withdraw-item_light.png) | POST administrative-decisions WITHDRAW | Только автор; причина и обе lock-версии | S-05/K-04; QA-E11 |
| A18 Один пункт в порядке, другой отозван<br>[dark](../design/images/mobile/A18_ok-and-withdrawn_dark.png) / [light](../design/images/mobile/A18_ok-and-withdrawn_light.png) | GET case после WITHDRAW | total2/confirmed1/withdrawn1/allConfirmed=false | S-05/K-04; QA-E11 |

## Диспетчер

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| D01 Рабочая очередь<br>[dark](../design/images/mobile/D01_work-queue_dark.png) / [light](../design/images/mobile/D01_work-queue_light.png) | GET /cases с серверным queueState | Только CONFIRMED разрешённых домов; draft скрыт | S-03/K-03; QA-A02 |
| D02 Фильтры очереди<br>[dark](../design/images/mobile/D02_queue-filters_dark.png) / [light](../design/images/mobile/D02_queue-filters_light.png) | GET /cases с фильтром/новым cursor | Причина пустоты; фильтр не расширяет scope | S-03/K-03; QA-A02 |
| D03 Обращение ждёт приёма<br>[dark](../design/images/mobile/D03_accept-appeal_dark.png) / [light](../design/images/mobile/D03_accept-appeal_light.png) | POST /cases/{id}/acceptance | expectedCaseVersion; отдельный receipt | S-03/K-03; QA-E03 |
| D04 Следующий шаг по двери<br>[dark](../design/images/mobile/D04_door-next-step_dark.png) / [light](../design/images/mobile/D04_door-next-step_light.png) | PUT /items/{id}/next-action | text/ownerId/updateDueAt, expectedCase/ItemVersion | S-03/K-03; QA-E03 |
| D05 Сохранение отчёта<br>[dark](../design/images/mobile/D05_save-report_dark.png) / [light](../design/images/mobile/D05_save-report_light.png) | POST /cases/{id}/reports | reportText/targetItemIds; сохранение без link | S-04/K-03; QA-E04 |
| D06 Проверка подсказки и выбор связей<br>[dark](../design/images/mobile/D06_review-report-links_dark.png) / [light](../design/images/mobile/D06_review-report-links_light.png) | GET report + контракт05 через Java | suggestions/evidence или MANUAL; флажки пустые | S-04/S-06/K-03; QA-E04 |
| D07 Подтверждение точных связей<br>[dark](../design/images/mobile/D07_confirm-exact-links_dark.png) / [light](../design/images/mobile/D07_confirm-exact-links_light.png) | POST /reports/{id}/links | Явные пары и content/round/lock версии; только свет | S-04/K-03; QA-E05 |
| D08 Карточка после связи отчёта<br>[dark](../design/images/mobile/D08_linked-report-card_dark.png) / [light](../design/images/mobile/D08_linked-report-card_light.png) | GET case после link | Свет ждёт автора; дверь не затронута | S-04/K-03; QA-E05 |
| D09 Архивирование пункта с причиной<br>[dark](../design/images/mobile/D09_archive-with-reason_dark.png) / [light](../design/images/mobile/D09_archive-with-reason_light.png) | POST item administrative-decisions ARCHIVE | Только выбранная дверь; причина/версии; не массовая операция | S-05/K-04; QA-E11 |
| D10 Пункт в архиве с неподтверждённым результатом<br>[dark](../design/images/mobile/D10_archive-unconfirmed-remainder_dark.png) / [light](../design/images/mobile/D10_archive-unconfirmed-remainder_light.png) | GET case с архивным пунктом | Архив двери, её last plan исторический; свет прежний | S-05/K-04; QA-E11 |

## Состояния

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| X01 Ошибка обязательного поля<br>[dark](../design/images/states/X01_required-field-error_dark.png) / [light](../design/images/states/X01_required-field-error_light.png) | 422 / frontend required field | Согласованные лимиты и field errors | S-02/K-06; QA-A10 |
| X02 Конфликт изменения<br>[dark](../design/images/states/X02_version-conflict_dark.png) / [light](../design/images/states/X02_version-conflict_light.png) | 409 + GET актуальной карточки | Сохранить локальный ввод; новый key не обходит конфликт | S-02/K-06; QA-A05 |
| X03 Потеря сети — неизвестный исход<br>[dark](../design/images/states/X03_unknown-save-outcome_dark.png) / [light](../design/images/states/X03_unknown-save-outcome_light.png) | Повтор исходного key/body + GET | Неизвестный исход; ни success, ни потеря не утверждаются | S-02/K-06; QA-R02 |
| X04 Подсказка недоступна — ручные связи<br>[dark](../design/images/states/X04_manual-links-fallback_dark.png) / [light](../design/images/states/X04_manual-links-fallback_light.png) | Python timeout/STUB/MANUAL | Сохранённый текст + ручной выбор без ложной цитаты | S-06/K-03; QA-E10 |
| X05 Карточка не загрузилась<br>[dark](../design/images/states/X05_card-load-error_dark.png) / [light](../design/images/states/X05_card-load-error_light.png) | Ошибка GET → повтор чтения | Ошибка отличается от пустоты/удаления | K-06; QA-R01 |
| X06 Пустая очередь и пустой фильтр<br>[dark](../design/images/states/X06_empty-queue-and-filter_dark.png) / [light](../design/images/states/X06_empty-queue-and-filter_light.png) | GET list → пустая очередь / фильтр | Два разных контекстных текста; сброс фильтра | S-03/K-03; QA-E03 |
| X07 Долгий ввод и клавиатура<br>[dark](../design/images/states/X07_keyboard-and-long-input_dark.png) / [light](../design/images/states/X07_keyboard-and-long-input_light.png) | Редактор с открытой клавиатурой | Текущий unsaved input; действие доступно | K-06; QA-R07 |
| X08 Отправка — защита от двойного нажатия<br>[dark](../design/images/states/X08_submitting-links_dark.png) / [light](../design/images/states/X08_submitting-links_light.png) | Одна pending-команда | Disabled+busy до ответа; сервер dedup независимо | S-04/K-06; QA-A06 |
| X09 Проблема осталась / невозможно проверить<br>[dark](../design/images/states/X09_negative-and-unable-observations_dark.png) / [light](../design/images/states/X09_negative-and-unable-observations_light.png) | POST observation PERSISTS/CANNOT_CHECK | Альтернативные ответы, оба не успех | S-05/K-04; QA-E06 |
| X10 Ошибки приглашения<br>[dark](../design/images/states/X10_invitation-errors_dark.png) / [light](../design/images/states/X10_invitation-errors_light.png) | Claim 422/409/429 [коды закрепить S-01] | Просрочено/использовано/неверно; без назначения роли | S-02/K-02; QA-A04 |

## Адаптивность

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| R01 320 px · текст 200%<br>[dark](../design/images/responsive/R01_320px-text-200_dark.png) / [light](../design/images/responsive/R01_320px-text-200_light.png) | Тот же A13, 320px/200% | Без изменения бизнес-данных | K-06; QA-E12 |
| R02 768 × 1024 · web-контейнер<br>[dark](../design/images/responsive/R02_tablet-max-container_dark.png) / [light](../design/images/responsive/R02_tablet-max-container_light.png) | Тот же A13, tablet | Внешняя рамка условная, не native MAX chrome | K-06; QA-R07 |
| R03 1440 × 1000 · широкая очередь<br>[dark](../design/images/responsive/R03_wide-work-queue_dark.png) / [light](../design/images/responsive/R03_wide-work-queue_light.png) | Тот же D01, wide | Пагинация/фильтр не локальная сортировка закрытых данных | K-03; QA-E03 |
| R04 1440 × 1000 · связи отчёта<br>[dark](../design/images/responsive/R04_wide-report-links_dark.png) / [light](../design/images/responsive/R04_wide-report-links_light.png) | Тот же D06, wide | Две колонки; точная цитата и явный выбор | K-03; QA-E05 |

## Текстовый бот

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| B01 Бот — старт<br>[dark](../design/images/bot/B01_bot-start_dark.png) / [light](../design/images/bot/B01_bot-start_light.png) | MAX webhook /start → Java | Текущая сессия диалога/пользователь; меню | S-05/K-05; QA-E01 |
| B02 Бот — текст, уточнение, подтверждение<br>[dark](../design/images/bot/B02_bot-create-appeal_dark.png) / [light](../design/images/bot/B02_bot-create-appeal_light.png) | Те же create/patch/confirm через адаптер | Серверное состояние диалога, вопросы только автору | S-05; QA-E01 |
| B03 Бот — отчёт, наблюдение, остаток<br>[dark](../design/images/bot/B03_bot-observation_dark.png) / [light](../design/images/bot/B03_bot-observation_light.png) | Те же observation + outbox | Текущий callback item/content/round; дверь остаётся | S-05; QA-E07 |
| B04 Бот — мои обращения и устаревшая кнопка<br>[dark](../design/images/bot/B04_bot-my-and-stale-action_dark.png) / [light](../design/images/bot/B04_bot-my-and-stale-action_light.png) | Разрешённый список / startapp / stale callback | Один caseId; номер только подпись; без обхода прав | S-05/K-05; QA-A07 |

## Расширение после core

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| S01 Похожие пункты в разрешённой области<br>[dark](../design/extension/S01_similar-permitted-items_dark.png) / [light](../design/extension/S01_similar-permitted-items_light.png) | Контракт04 через Java, Should | Разрешённые candidates/точные item pairs; не auto-merge | S-06; QA-A12 |
| S02 Сравнение / поиск не выполнен<br>[dark](../design/extension/S02_similarity-detail-and-unavailable_dark.png) / [light](../design/extension/S02_similarity-detail-and-unavailable_light.png) | Контракт04 результат или NOT_EVALUATED | Нет данных ≠ нет совпадений; продолжение доступно | S-06; QA-A12 |

## Компоненты

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| C01 Основы<br>[both](../design/images/components/C01_foundations_both.png) | Библиотека UI, нет собственной команды | Те же semantic tokens/компоненты, все состояния | K-01/K-06; QA-E12 |
| C02 Действия и ввод<br>[both](../design/images/components/C02_actions-and-inputs_both.png) | Библиотека UI, нет собственной команды | Те же semantic tokens/компоненты, все состояния | K-01/K-06; QA-E12 |
| C03 Навигация и оформление<br>[both](../design/images/components/C03_navigation-and-preferences_both.png) | Библиотека UI, нет собственной команды | Те же semantic tokens/компоненты, все состояния | K-01/K-06; QA-E12 |
| C04 Предметные компоненты<br>[both](../design/images/components/C04_domain-components_both.png) | Библиотека UI, нет собственной команды | Те же semantic tokens/компоненты, все состояния | K-01/K-06; QA-E12 |
| C05 Обратная связь<br>[both](../design/images/components/C05_feedback_both.png) | Библиотека UI, нет собственной команды | Те же semantic tokens/компоненты, все состояния | K-01/K-06; QA-E12 |
| C06 Подтверждения и история<br>[both](../design/images/components/C06_confirmation-and-history_both.png) | Библиотека UI, нет собственной команды | Те же semantic tokens/компоненты, все состояния | K-01/K-06; QA-E12 |

## Приёмка связности

Кирилл отмечает реальный API для каждого command screen, Семён подтверждает DTO/права/версии, София повторяет по другому аккаунту и после reload. До совпадения всех трёх экран остаётся fixture/prototype. Скриншоты D09/D10 исправлены под отдельный item, а fields receipt/nextAction должны появиться в коде до показа их как фактов.
