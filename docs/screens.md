# Все экраны и их backend-контракты

Редакция ДомПульс от 27.09: существующие состояния Mini App оформлены по десяти новым листам пользователя и `DomPulse_Logos.zip`. PNG22.09 в таблицах ниже - историческая иллюстрация сценария, а не инструкция копировать прежний вид. Действующие правила - [design](design.md). Предыдущий выпуск `0635314` и его интеграция подтверждены [протоколом профиля и экранов](qa/profile-visual-2026-09-27.md): 45 UI-проверок, 4 сценария через настоящий nginx/Java/PostgreSQL, 48 состояний всех 12 route patterns и отдельная проверка MAX Web. Текущий выпуск `2833443` добавляет M06, маркировку и исправления сессии/форм: [QA выпуска](qa/backend-polish-2026-09-27.md). [QA прежнего редизайна](qa/redesign-2026-09-27.md) относится к более ранней редакции.

## Новое оформление и границы экранов

- Публичное имя Mini App - ДомПульс; внутреннее имя КОРЕНЬ и backend-контракты сохранены. Текстовый бот и баннеры в этой задаче не изменены.
- Нижняя навигация на телефоне: «Обращения», «Доступ к дому», «Профиль». С 960 px - боковая панель 224 px. Основной столбец до 680 px, широкая очередь и отчёт используют дополнительную ширину. Крупный текст 20 px вместо базовых 16 px; обе темы и уменьшение движения сохраняются локально.
- Дополнительный runtime-маршрут `/#/profile` показывает имя, username и аватар MAX при наличии, дома и роли из существующего `/me`, переход к дому и выход. Отсутствующее или недоступное фото заменяется SVG, отсутствующий nickname не придумывается. Поля профиля проверены и сохранены Java в сессии; это не доверие к `initDataUnsafe`. Профиль дополняет исходную матрицу из 62 ID, не меняя их смысл.
- Заставка и loading анимированы только при реальном ожидании. Процент готовности не выдумывается, OS/user reduced motion останавливает движение. Декоративные иллюстрации повторяют стиль листов; архив содержит только логотипы, а не все рисунки/фото экранов.
- Поиск и фильтры автора работают по уже загруженным разрешённым карточкам; счётчики не глобальные. При наличии следующей страницы видна подсказка об области и кнопка загрузки. Серверные фильтры `queueState` диспетчера сохраняют прежние проверки доступа.
- Фото/файлы, приоритеты, выбор произвольного исполнителя и внешние контакты из коллажей не представлены действующим API и не имитируются. Новые LLM/автоматическое улучшение исходника не добавлены; редактирование пунктов остаётся ручным. `409` показывает конфликт версии данных, а не экран обновления приложения. Офлайн-хранилище обращений не заявлено.
- Принятие обращения, подтверждение содержания, отчёт, наблюдение автора и архив остаются разными фактами. Цвет и подпись из рисунка не могут превратить одно событие в другое. Подробные отличия и причины - [границы API в design](design.md#границы-макета-и-действующего-api).

## Что доступно в runtime

| Группа | Реальная реализация | Предел проверки |
|---|---|---|
| M01..M05, M07/M08, профиль | Вход/сессия, приглашение, контекст и профиль из /me, настройки, недоступная карточка | В локальных тестах MAX Bridge подписан тестовым ключом; отдельно подтверждены настоящий вход и профиль в MAX Web |
| A01..A10 | Создание, ручной список, правка исходника/пунктов, review/confirm, квитанция | A04/A05: автоматических extract/clarify и вопросов модели нет; используются обязательные поля и ручное уточнение только автором |
| A11..A18 | Три наблюдения автора, отдельный остаток, история, отзыв с причиной | Настоящие Java/PostgreSQL, не физический MAX |
| D01..D10 | Очередь/фильтры, приём, шаг, отчёт, цитаты, явные связи, архив пункта | Ответственный nextAction - текущий диспетчер; справочника других сотрудников нет |
| X01..X10 | Loading, empty, denied, ошибки, pending, неизвестный исход, конфликт и повтор входа | Автоматизированные UI fixtures плюс реальные negative API checks |
| R01..R04, C01..C06 | Адаптивные компоненты, темы, большой текст, широкая очередь-таблица и отчёт в две колонки | Chromium 320/390/768/1440; реальные устройства ожидают проверки |
| M06 | `/#/about` доступен до входа и из профиля: реальные категории данных, запрос удаления, поддержка `smookki@yandex.ru`, предупреждение про 112 | Опубликовано в `2833443`, контакт и страница проверены на домене; детали в протоколе выпуска. Это не автоматическое удаление и не утверждённая полная политика хранения |
| B01..B04 | Ранее действующий бот и MAX Web проверялись 27.09; редизайн ДомПульс их не меняет | Прежняя проверка не подтверждает новые баннеры; полный G2 с двумя аккаунтами и mobile остаётся независимой приёмке |
| S01/S02 | Should выключен | UI поиска похожих обращений не заявлен |

Маршруты runtime: `/#/`, `/#/access`, `/#/profile`, `/#/about`, `/#/logout`, `/#/new`, `/#/cases/:caseId`, редактор `/edit` или `/source`, подтверждение `/review`, действие `/items/:itemId/:action`, отчёты `/reports/new` и `/reports/:reportId`. Новая справка добавляет тринадцатый route pattern; 48 снимков 12 маршрутов из прежнего протокола не считаются её проверкой. Один маршрут покрывает несколько состояний матрицы. Корневую ссылку организаторам передавать без hash: `https://hackathon.lumnex.ru/`.

Исходная матрица содержит 62 ID, включая ошибки/адаптивность/бот/библиотеку/расширение; профиль добавлен как композиция существующего `/me`. Один ID не требует отдельного route или endpoint. Все пути API ниже относительно `/api/v1`, кроме MAX webhook. Буква A в screen ID не обозначает QA-A.

Статус на 27.09: таблицы ниже сохраняют 62 ID проектных состояний. Основной runtime реализован и проверен в указанном выше объёме; история первой интеграции - в [протоколе 26.09](qa/kirill-2026-09-26.md). Статус серверной операции указан в машинной схеме `x-implementation-status`; PNG не доказывает готовность отсутствующей функции.

При сборке с `VITE_TRAINING_MODE=true` общий баннер «Учебный дом, данные тестовые» виден и до входа, и на рабочих страницах. В release флаг приходит из `TRAINING_MODE`; имя/адрес дома не определяют его автоматически. Баннер не меняет доступ и не заменяет данные API.

Каждый экран также обязан обрабатывать loading/denied/error/смену контекста; командный экран - pending/409/unknown outcome. Все реальные значения приходят с сервера; synthetic placeholders не замена API.

## Общие

| Экран и макеты | Команда / чтение | Обязательные данные и смысл | Реализация / проверка |
|---|---|---|---|
| M01 Запуск и восстановление входа<br>[dark](../design/images/mobile/M01_launch_dark.png) / [light](../design/images/mobile/M01_launch_light.png) | Вход: initData → auth/max, затем /me | Сессия, memberships; нет доверия initDataUnsafe | S-02/K-01; QA-A01 |
| M02 Нет доступа к дому<br>[dark](../design/images/mobile/M02_no-house-access_dark.png) / [light](../design/images/mobile/M02_no-house-access_light.png) | GET /me → нет действующих memberships | Проверенный пользователь без доступа; данные дома скрыты | S-02/K-01; QA-A02 |
| M03 Ввод приглашения<br>[dark](../design/images/mobile/M03_invitation_dark.png) / [light](../design/images/mobile/M03_invitation_light.png) | POST /memberships/claim | invitationCode; безопасное одноразовое потребление | S-02/K-02; QA-A04 |
| M04 Выбор доступного дома и контекста<br>[dark](../design/images/mobile/M04_available-contexts_dark.png) / [light](../design/images/mobile/M04_available-contexts_light.png) | GET /me, выбор существующего context | Только memberships пользователя; role не изменяется | S-02/K-01; QA-A02 |
| M05 Настройки<br>[dark](../design/images/mobile/M05_settings_dark.png) / [light](../design/images/mobile/M05_settings_light.png) | Локальные theme/text prefs | System/light/dark; normal/large; не API прав/уведомлений | K-01/K-06; QA-E12 |
| M06 Помощь и о сервисе<br>[dark](../design/images/mobile/M06_help_dark.png) / [light](../design/images/mobile/M06_help_light.png) | `/#/about`, без команды API | Категории данных и назначение; `smookki@yandex.ru`; запрос удаления по почте, без секретов; архив/выход не удаляют данные; при опасности 112 | K-08; QA-R09; `about.spec.ts`, результат сверять по протоколу |
| M07 Сессия закончилась<br>[dark](../design/images/mobile/M07_session-expired_dark.png) / [light](../design/images/mobile/M07_session-expired_light.png) | 401 → вход при свежем initData, иначе закрыть/открыть через бот и `openMaxLink` | Ввод остаётся до закрытия страницы; перед перезапуском скопировать. Refresh страницы не обновляет запуск MAX; серверный TTL не ослаблен | S-02/K-05; QA-A01 |
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
| X02 Конфликт изменения<br>[dark](../design/images/states/X02_version-conflict_dark.png) / [light](../design/images/states/X02_version-conflict_light.png) | 409 + успешный GET актуальной карточки + явное сравнение | Ввод не стирается; после ошибки GET нельзя принять старый кэш. После сравнения исключить недоступные пункты/уже существующие связи из выбора | S-02/K-06; QA-A05 |
| X03 Потеря сети — неизвестный исход<br>[dark](../design/images/states/X03_unknown-save-outcome_dark.png) / [light](../design/images/states/X03_unknown-save-outcome_light.png) | Повтор исходного key/body + GET | Неизвестный исход; ни success, ни потеря не утверждаются | S-02/K-06; QA-R02 |
| X04 Подсказка недоступна — ручные связи<br>[dark](../design/images/states/X04_manual-links-fallback_dark.png) / [light](../design/images/states/X04_manual-links-fallback_light.png) | Python timeout/STUB/MANUAL | Сохранённый текст + ручной выбор без ложной цитаты | S-06/K-03; QA-E10 |
| X05 Карточка не загрузилась<br>[dark](../design/images/states/X05_card-load-error_dark.png) / [light](../design/images/states/X05_card-load-error_light.png) | Ошибка GET → повтор чтения | Ошибка отличается от пустоты/удаления | K-06; QA-R01 |
| X06 Пустая очередь и пустой фильтр<br>[dark](../design/images/states/X06_empty-queue-and-filter_dark.png) / [light](../design/images/states/X06_empty-queue-and-filter_light.png) | GET list → пустая очередь / фильтр | Два разных контекстных текста; сброс фильтра | S-03/K-03; QA-E03 |
| X07 Долгий ввод и клавиатура<br>[dark](../design/images/states/X07_keyboard-and-long-input_dark.png) / [light](../design/images/states/X07_keyboard-and-long-input_light.png) | Редактор с открытой клавиатурой | Текущий unsaved input; действие доступно | K-06; QA-R07 |
| X08 Отправка — защита от двойного нажатия<br>[dark](../design/images/states/X08_submitting-links_dark.png) / [light](../design/images/states/X08_submitting-links_light.png) | Одна pending-команда | Disabled+busy до ответа; сервер dedup независимо | S-04/K-06; QA-A06 |
| X09 Проблема осталась / невозможно проверить<br>[dark](../design/images/states/X09_negative-and-unable-observations_dark.png) / [light](../design/images/states/X09_negative-and-unable-observations_light.png) | POST observation PERSISTS/CANNOT_CHECK | Альтернативные ответы, оба не успех | S-05/K-04; QA-E06 |
| X10 Ошибки приглашения<br>[dark](../design/images/states/X10_invitation-errors_dark.png) / [light](../design/images/states/X10_invitation-errors_light.png) | Claim: пустой код 422 INVALID_FIELDS; неподходящий/истёкший/использованный 403 INVITATION_REJECTED | Причины отказа не различаются снаружи; успешный claim выдаёт RESIDENT, выбрать роль нельзя | S-02/K-02; QA-A04 |

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

`receipt`, `nextAction`, отчёты, ссылки, наблюдения и административные решения уже приходят из Java через `frontend/src/api/client.ts`; команды подтверждены сквозными сценариями и reload. PNG D09/D10 остаются историческими изображениями отдельного item, а не данными сервера. Независимая приёмка Софии на другом MAX-аккаунте и физических Android/iOS остаётся отдельным этапом, её не заменяет автоматизация Chromium.

При изменении command screen Кирилл проверяет реальный API, Семён согласует DTO/права/версии, София повторяет приёмку. Для отсутствующих функций макета сохраняется статус проектного состояния. По прямому решению пользователя существующие тестовый дом и обращения на живом стенде сохраняются; отсутствие заглушек означает реальные API-ответы и отсутствие фиктивного успеха, а не удаление этих записей.
